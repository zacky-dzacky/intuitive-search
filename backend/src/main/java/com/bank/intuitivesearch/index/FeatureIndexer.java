package com.bank.intuitivesearch.index;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.embedding.EmbeddingClient;
import com.bank.intuitivesearch.model.Feature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link FeatureIndex} in step with the feature registry.
 *
 * <p>Called after every registry refresh with the full feature list. Most
 * calls do nothing: the registry is fingerprinted (ids + document hashes),
 * and an unchanged fingerprint with no missing vectors is an early return.
 * When something did change, only the features whose text changed are sent
 * to the embedding provider — one batch call — and every other vector is
 * carried over from the previous generation. Editing one row costs one
 * embedding; a cold start costs one call for the whole catalogue.
 *
 * <p>Embedding failure is not index failure. The generation is still built
 * with whatever vectors exist, the error is recorded on
 * {@link IndexStats#lastError()}, and the next refresh retries the missing
 * ones. That is the same "degrade to lexical" contract the request path has.
 */
@Component
public class FeatureIndexer {

    private static final Logger log = LoggerFactory.getLogger(FeatureIndexer.class);
    /** Inputs per embedding request. Providers cap batch size; 64 is under every limit we target. */
    static final int EMBED_BATCH = 64;

    private final FeatureIndex index;
    private final EmbeddingClient embeddingClient;
    private final SearchProperties properties;

    /** Vectors from the previous generation, keyed by feature id, with the hash of the text they embed. */
    private final Map<String, CachedVector> vectors = new HashMap<>();
    private String lastFingerprint = "";
    private boolean lastBuildComplete = false;

    private record CachedVector(String hash, float[] vector) {}

    /** What one sync did, for the admin endpoint and the log. */
    public record SyncReport(boolean rebuilt, int features, int embedded, int withVector,
                             long tookMs, String error) {}

    public FeatureIndexer(FeatureIndex index, EmbeddingClient embeddingClient,
                          SearchProperties properties) {
        this.index = index;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
    }

    /**
     * @param features the enabled features, as just loaded from Postgres
     * @param force    re-embed everything even if nothing changed
     */
    public synchronized SyncReport sync(Collection<Feature> features, boolean force) {
        long started = System.nanoTime();

        Map<String, String> hashes = new TreeMap<>();
        Map<String, String> texts = new HashMap<>();
        for (Feature feature : features) {
            String text = FeatureDocument.embeddingText(feature);
            texts.put(feature.featureId(), text);
            hashes.put(feature.featureId(), FeatureDocument.hash(text));
        }
        String fingerprint = hashes.toString();
        if (!force && fingerprint.equals(lastFingerprint) && lastBuildComplete && index.isReady()) {
            return new SyncReport(false, features.size(), 0,
                    index.stats() == null ? 0 : index.stats().withVector(), 0, null);
        }

        // Forget vectors of features that no longer exist.
        vectors.keySet().retainAll(hashes.keySet());

        List<Feature> toEmbed = new ArrayList<>();
        for (Feature feature : features) {
            CachedVector cached = vectors.get(feature.featureId());
            if (force || cached == null || !cached.hash().equals(hashes.get(feature.featureId()))) {
                toEmbed.add(feature);
            }
        }

        String error = null;
        int embedded = 0;
        if (!toEmbed.isEmpty() && embeddingClient.isEnabled()) {
            try {
                for (int from = 0; from < toEmbed.size(); from += EMBED_BATCH) {
                    List<Feature> batch = toEmbed.subList(from, Math.min(from + EMBED_BATCH, toEmbed.size()));
                    List<float[]> results = embeddingClient.embedDocuments(
                            batch.stream().map(f -> texts.get(f.featureId())).toList());
                    for (int i = 0; i < batch.size(); i++) {
                        String id = batch.get(i).featureId();
                        vectors.put(id, new CachedVector(hashes.get(id), results.get(i)));
                        embedded++;
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("Embedding {} feature(s) for the index failed ({}); building with {} "
                        + "vector(s) and retrying on the next refresh",
                        toEmbed.size() - embedded, error, vectors.size());
            }
        }

        List<FeatureIndex.IndexDocument> documents = new ArrayList<>(features.size());
        int withVector = 0;
        for (Feature feature : features) {
            CachedVector cached = vectors.get(feature.featureId());
            float[] vector = cached == null ? null : cached.vector();
            if (vector != null) {
                withVector++;
            }
            documents.add(new FeatureIndex.IndexDocument(feature, vector));
        }

        long tookMs = (System.nanoTime() - started) / 1_000_000;
        SearchProperties.Embedding config = properties.getEmbedding();
        index.rebuild(documents, new IndexStats(features.size(), withVector, config.getDimensions(),
                embeddingClient.isEnabled() ? embeddingClient.describeBackend() : "disabled",
                Instant.now(), tookMs, embedded, error));

        lastFingerprint = fingerprint;
        lastBuildComplete = error == null && (withVector == features.size() || !embeddingClient.isEnabled());
        log.info("Feature index rebuilt: {} features, {} with vectors, {} embedded, {}ms{}",
                features.size(), withVector, embedded, tookMs, error == null ? "" : " (embedding error: " + error + ")");
        return new SyncReport(true, features.size(), embedded, withVector, tookMs, error);
    }
}
