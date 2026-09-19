package com.bank.intuitivesearch.registry;

import com.bank.intuitivesearch.model.Feature;
import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory snapshot of the feature registry.
 *
 * <p>The retrieval SQL returns only ids and scores; the full feature rows are
 * hydrated from here. ~50 rows is nothing to hold in memory, and it keeps the
 * hot path to a single round trip per channel.
 *
 * <p>Refreshed on a timer, so inserting feature #51 into Postgres makes it
 * searchable without a deploy or a restart.
 */
@Component
public class FeatureRegistry {

    private static final Logger log = LoggerFactory.getLogger(FeatureRegistry.class);

    private final FeatureRepository repository;
    private final AtomicReference<Map<String, Feature>> snapshot =
            new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, Double>> termIdf =
            new AtomicReference<>(Map.of());

    public FeatureRegistry(FeatureRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void loadOnStartup() {
        try {
            refresh();
        } catch (Exception e) {
            // Don't block startup on a cold database — the scheduled refresh
            // will pick it up, and /actuator/health reports the degradation.
            log.error("Initial feature registry load failed; search will return "
                    + "no matches until the next refresh succeeds", e);
        }
    }

    @Scheduled(fixedDelayString = "${search.registry-refresh-ms:60000}")
    public void refresh() {
        List<Feature> features = repository.findAllEnabled();
        Map<String, Feature> byId = features.stream()
                .collect(Collectors.toUnmodifiableMap(Feature::featureId, Function.identity()));
        termIdf.set(computeTermIdf(features));
        Map<String, Feature> previous = snapshot.getAndSet(byId);
        if (previous.size() != byId.size()) {
            log.info("Feature registry loaded: {} features (was {})", byId.size(), previous.size());
        }
    }

    /**
     * Inverse document frequency over the registry's naming terms.
     *
     * <p>A term that names exactly one feature ("e-statement") identifies it
     * outright. A term shared by many ("money", "account") barely narrows
     * anything down, and must not be allowed to name a feature confidently
     * just because it appears in one feature's keyword list. Computed from the
     * registry itself, so it re-tunes automatically as features are added.
     */
    private static Map<String, Double> computeTermIdf(List<Feature> features) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Feature feature : features) {
            // Count each term once per feature.
            for (String key : feature.namingTerms().stream()
                    .map(tokens -> String.join(" ", tokens))
                    .collect(Collectors.toSet())) {
                documentFrequency.merge(key, 1, Integer::sum);
            }
        }
        int total = Math.max(features.size(), 1);
        double ceiling = Math.log1p(total);
        Map<String, Double> idf = new HashMap<>(documentFrequency.size());
        documentFrequency.forEach((term, df) ->
                idf.put(term, Math.log1p((double) total / df) / ceiling));
        return Map.copyOf(idf);
    }

    /**
     * How much a naming term narrows the registry down, in 0..1.
     * Unknown terms are treated as maximally specific.
     */
    public double idf(String termKey) {
        return termIdf.get().getOrDefault(termKey, 1.0);
    }

    public Optional<Feature> byId(String featureId) {
        return Optional.ofNullable(snapshot.get().get(featureId));
    }

    public Collection<Feature> all() {
        return snapshot.get().values();
    }

    public int size() {
        return snapshot.get().size();
    }
}
