package com.bank.intuitivesearch.search;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.FeatureMatch;
import com.bank.intuitivesearch.registry.FeatureRegistry;
import com.bank.intuitivesearch.registry.FeatureRepository;
import com.bank.intuitivesearch.registry.FeatureRepository.ChannelHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stage 1 — hybrid retrieval.
 *
 * <p>Runs the lexical (full-text + trigram) and vector channels concurrently,
 * then merges them with configurable weights. This stage always runs and must
 * stay fast: it is the whole budget for a pure navigation query.
 *
 * <p>If the vector channel is unavailable, its weight is dropped and the
 * remaining weights are renormalised, so a degraded search still produces
 * confidence scores on the same 0..1 scale.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]+");
    /** Shorter fragments than this match too much to be evidence of intent. */
    private static final int MIN_PREFIX = 3;

    private final FeatureRepository repository;
    private final FeatureRegistry registry;
    private final EmbeddingClient embeddingClient;
    private final SearchProperties properties;
    private final ExecutorService executor;

    public HybridSearchService(FeatureRepository repository,
                               FeatureRegistry registry,
                               EmbeddingClient embeddingClient,
                               SearchProperties properties,
                               ExecutorService searchExecutor) {
        this.repository = repository;
        this.registry = registry;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.executor = searchExecutor;
    }

    public List<FeatureMatch> search(String rawQuery) {
        String normalised = normalise(rawQuery);
        if (normalised.isEmpty()) {
            return List.of();
        }
        int limit = properties.getCandidateLimit();
        String tsQuery = toTsQuery(normalised);

        // Fan out: the embedding round trip and the lexical SQL overlap.
        Future<List<ChannelHit>> lexicalFuture =
                executor.submit(() -> repository.lexicalSearch(tsQuery, normalised, limit));
        Future<Optional<float[]>> embeddingFuture =
                executor.submit(() -> embeddingClient.embedQuery(rawQuery));

        List<ChannelHit> lexical = await(lexicalFuture, List.of(), "lexical search");
        Optional<float[]> embedding = await(embeddingFuture, Optional.empty(), "query embedding");

        List<ChannelHit> vector = embedding
                .map(vec -> await(executor.submit(() -> repository.vectorSearch(vec, limit)),
                        List.<ChannelHit>of(), "vector search"))
                .orElse(List.of());

        boolean vectorAvailable = !vector.isEmpty();
        return merge(lexical, vector, normalised, vectorAvailable, limit);
    }

    private List<FeatureMatch> merge(List<ChannelHit> lexical,
                                     List<ChannelHit> vector,
                                     String normalisedQuery,
                                     boolean vectorAvailable,
                                     int limit) {

        Map<String, double[]> scores = new HashMap<>();
        for (ChannelHit hit : lexical) {
            double[] slot = scores.computeIfAbsent(hit.featureId(), k -> new double[4]);
            slot[0] = Math.max(slot[0], clamp(hit.keywordScore()));
            slot[1] = Math.max(slot[1], clamp(hit.trigramScore()));
        }
        for (ChannelHit hit : vector) {
            double[] slot = scores.computeIfAbsent(hit.featureId(), k -> new double[4]);
            slot[2] = Math.max(slot[2], clamp(hit.vectorScore()));
        }

        // Containment runs over the whole in-memory registry, not just the SQL
        // hits: ~67 features is nothing to scan, and it lets a feature the
        // lexical query missed still surface if the query names it outright.
        List<String> queryTokens = List.of(NON_ALNUM.split(normalisedQuery));
        for (Feature feature : registry.all()) {
            double containment = containmentScore(feature, queryTokens, registry::idf);
            if (containment > 0) {
                scores.computeIfAbsent(feature.featureId(), k -> new double[4])[3] = containment;
            }
        }

        SearchProperties.Weights w = properties.getWeights();
        double totalWeight = w.getKeyword() + w.getTrigram() + w.getContainment()
                + (vectorAvailable ? w.getVector() : 0);
        if (totalWeight <= 0) {
            totalWeight = 1;
        }

        List<FeatureMatch> matches = new ArrayList<>(scores.size());
        for (Map.Entry<String, double[]> entry : scores.entrySet()) {
            Feature feature = registry.byId(entry.getKey()).orElse(null);
            if (feature == null) {
                // Registry snapshot is older than the DB — skip rather than
                // return a half-populated result.
                continue;
            }
            double[] s = entry.getValue();
            double weighted = (w.getKeyword() * s[0]
                    + w.getTrigram() * s[1]
                    + w.getContainment() * s[3]
                    + (vectorAvailable ? w.getVector() * s[2] : 0)) / totalWeight;

            boolean exact = feature.exactForms().contains(normalisedQuery);
            double confidence = exact
                    ? Math.max(weighted, properties.getExactMatchConfidence())
                    : weighted;

            matches.add(new FeatureMatch(feature, s[0], s[1], s[2], s[3], clamp(confidence), exact));
        }

        matches.sort(Comparator
                .<FeatureMatch>comparingDouble(FeatureMatch::confidence).reversed()
                .thenComparing(FeatureMatch::featureId));
        return matches.size() > limit ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
    }

    /**
     * How strongly the query <em>names</em> this feature.
     *
     * <p>A naming term counts when <em>every</em> one of its tokens appears
     * somewhere in the query. Adjacency is not required, because users write
     * "block my card" for the term "block card" — but completeness is, so
     * "Transfer Money" earns nothing from a query that only says "transfer".
     *
     * <p>Longer matched terms score higher: "where my money" naming Spending
     * Insights is far stronger evidence than the bare token "money" naming
     * Transfer. That length preference is what resolves queries where a
     * generic keyword of one feature collides with the specific phrasing of
     * another.
     *
     * <p>This is the channel that keeps ranking correct when a command query
     * carries parameter text the registry has never seen — the payload
     * dilutes every similarity measure, but it cannot dilute this one.
     */
    static double containmentScore(Feature feature,
                                   List<String> queryTokens,
                                   DoubleUnaryTermWeight idf) {
        double best = 0;
        for (List<String> term : feature.namingTerms()) {
            double coverage = coverage(term, queryTokens);
            if (coverage <= 0) {
                continue;
            }
            double lengthWeight = Math.min(1.0, 0.6 + 0.15 * term.size());
            // A term that names many features narrows nothing down, however
            // literally the query contains it.
            double specificity = idf.weightOf(String.join(" ", term));
            best = Math.max(best, lengthWeight * specificity * coverage);
        }
        return best;
    }

    /**
     * How completely the query covers a naming term, in 0..1, or 0 if any of
     * the term's tokens is missing entirely.
     *
     * <p>A token counts in full when the query contains it exactly, and
     * partially when a query token is a prefix of it — "tran" covers half of
     * "transfer". Partial credit is what lets mid-word typing rank the
     * feature the user is reaching for above its neighbours: on "tran",
     * Transfer Money scores on its own name while Transfer Receipt, whose
     * terms are "transfer slip" and "proof of transfer", covers none of its
     * terms completely and scores nothing.
     */
    private static double coverage(List<String> term, List<String> queryTokens) {
        double total = 0;
        for (String termToken : term) {
            double bestForToken = 0;
            for (String queryToken : queryTokens) {
                if (queryToken.equals(termToken)) {
                    bestForToken = 1.0;
                    break;
                }
                if (queryToken.length() >= MIN_PREFIX && termToken.startsWith(queryToken)) {
                    bestForToken = Math.max(bestForToken,
                            (double) queryToken.length() / termToken.length());
                }
            }
            if (bestForToken == 0) {
                return 0;   // an unmatched token means the term isn't named
            }
            total += bestForToken;
        }
        return total / term.size();
    }

    /** Lets the containment scorer be unit-tested without the registry. */
    @FunctionalInterface
    interface DoubleUnaryTermWeight {
        double weightOf(String termKey);
    }

    /**
     * Builds a prefix {@code to_tsquery} expression from the user's words.
     *
     * <p>Prefix matching ({@code tran:*}) is what makes search-as-you-type
     * feel instant. Tokens are stripped to alphanumerics first, so nothing the
     * user types can be interpreted as tsquery syntax.
     */
    static String toTsQuery(String normalisedQuery) {
        List<String> tokens = new ArrayList<>();
        for (String token : NON_ALNUM.split(normalisedQuery)) {
            if (!token.isBlank()) {
                tokens.add(token + ":*");
            }
        }
        return String.join(" | ", tokens);
    }

    static String normalise(String query) {
        if (query == null) {
            return "";
        }
        return query.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private <T> T await(Future<T> future, T fallback, String what) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            log.warn("{} failed; continuing without it: {}", what, e.getMessage());
            return fallback;
        }
    }
}
