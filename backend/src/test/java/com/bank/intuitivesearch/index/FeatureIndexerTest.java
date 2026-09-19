package com.bank.intuitivesearch.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.embedding.EmbeddingBackend;
import com.bank.intuitivesearch.embedding.EmbeddingClient;
import com.bank.intuitivesearch.model.Feature;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The indexer embeds only what changed, and never lets an embedding outage take the index down. */
class FeatureIndexerTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final FeatureIndex index = new FeatureIndex();
    private final CountingBackend backend = new CountingBackend();
    private final FeatureIndexer indexer = new FeatureIndexer(index,
            new EmbeddingClient(backend, properties(), executor), properties());

    @AfterEach
    void tearDown() {
        index.close();
        executor.shutdown();
    }

    @Test
    @DisplayName("a cold start embeds the whole catalogue in one batch")
    void coldStart() {
        FeatureIndexer.SyncReport report = indexer.sync(List.of(feature("a", "Alpha"), feature("b", "Beta")), false);
        assertThat(report.rebuilt()).isTrue();
        assertThat(report.embedded()).isEqualTo(2);
        assertThat(report.withVector()).isEqualTo(2);
        assertThat(backend.calls).isEqualTo(1);
        assertThat(index.stats().withVector()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unchanged registry costs nothing — no embedding call, no rebuild")
    void unchangedIsNoop() {
        List<Feature> features = List.of(feature("a", "Alpha"), feature("b", "Beta"));
        indexer.sync(features, false);
        FeatureIndexer.SyncReport report = indexer.sync(features, false);
        assertThat(report.rebuilt()).isFalse();
        assertThat(backend.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("editing one feature re-embeds one feature and keeps the rest")
    void incrementalReembed() {
        indexer.sync(List.of(feature("a", "Alpha"), feature("b", "Beta")), false);
        FeatureIndexer.SyncReport report =
                indexer.sync(List.of(feature("a", "Alpha"), feature("b", "Beta, renamed")), false);
        assertThat(report.rebuilt()).isTrue();
        assertThat(report.embedded()).isEqualTo(1);
        assertThat(report.withVector()).isEqualTo(2);
        assertThat(backend.lastInputs).hasSize(1).first().asString().startsWith("Beta, renamed");
    }

    @Test
    @DisplayName("force re-embeds everything")
    void force() {
        indexer.sync(List.of(feature("a", "Alpha"), feature("b", "Beta")), false);
        FeatureIndexer.SyncReport report = indexer.sync(List.of(feature("a", "Alpha"), feature("b", "Beta")), true);
        assertThat(report.embedded()).isEqualTo(2);
        assertThat(backend.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("an embedding outage still builds the index, records the error, and retries next time")
    void outageDegradesThenRecovers() {
        backend.failing = true;
        FeatureIndexer.SyncReport report = indexer.sync(List.of(feature("a", "Alpha")), false);
        assertThat(report.rebuilt()).isTrue();
        assertThat(report.withVector()).isZero();
        assertThat(report.error()).contains("provider down");
        assertThat(index.lexicalSearch("alpha", 5)).extracting(ChannelHit::featureId).containsExactly("a");

        backend.failing = false;
        FeatureIndexer.SyncReport retry = indexer.sync(List.of(feature("a", "Alpha")), false);
        assertThat(retry.rebuilt()).isTrue();
        assertThat(retry.withVector()).isEqualTo(1);
        assertThat(retry.error()).isNull();
    }

    @Test
    @DisplayName("features removed from the registry leave the index and the vector cache")
    void removal() {
        indexer.sync(List.of(feature("a", "Alpha"), feature("b", "Beta")), false);
        FeatureIndexer.SyncReport report = indexer.sync(List.of(feature("a", "Alpha")), false);
        assertThat(report.features()).isEqualTo(1);
        assertThat(report.embedded()).isZero();
        assertThat(index.lexicalSearch("beta", 5)).isEmpty();
    }

    private static SearchProperties properties() {
        SearchProperties p = new SearchProperties();
        p.getEmbedding().setDimensions(3);
        return p;
    }

    private static Feature feature(String id, String name) {
        return new Feature(id, name, "", "test", "/" + id, List.of(), List.of(), false, List.of());
    }

    /** Deterministic stand-in for a provider: counts calls and can be switched off. */
    static final class CountingBackend implements EmbeddingBackend {
        int calls;
        boolean failing;
        List<String> lastInputs = List.of();

        @Override
        public float[] embedQuery(String text) {
            return new float[] {1, 0, 0};
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            calls++;
            lastInputs = new ArrayList<>(texts);
            if (failing) {
                throw new IllegalStateException("provider down");
            }
            return texts.stream().map(t -> new float[] {1, 0, 0}).toList();
        }

        @Override
        public String describe() {
            return "counting";
        }
    }
}
