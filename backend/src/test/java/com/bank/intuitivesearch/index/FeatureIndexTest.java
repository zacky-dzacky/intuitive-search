package com.bank.intuitivesearch.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.model.Feature;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Lucene index behaves like the three Postgres channels it replaced:
 * prefix full-text, trigram fuzziness, cosine kNN — each on a 0..1 scale.
 */
class FeatureIndexTest {

    private static final Feature TRANSFER = feature("transfer", "Transfer Money",
            "Send money to a saved payee or a new account.",
            List.of("transfer", "send", "money"), List.of("trf"));
    private static final Feature TRANSFER_RECEIPT = feature("proof_of_transfer", "Transfer Receipt",
            "Download proof of a completed transfer.",
            List.of("receipt", "proof of transfer", "transfer slip"), List.of());
    private static final Feature SPENDING = feature("spending_insights", "Spending Insights",
            "See where your money went this month.",
            List.of("insights", "spending", "where my money"), List.of());
    private static final Feature ESTATEMENT = feature("e_statement", "E-Statement",
            "Download monthly account statements.",
            List.of("statement", "monthly statement"), List.of("e-stmt", "estatement"));
    private static final Feature BLOCK_CARD = feature("block_card", "Block Card",
            "Temporarily freeze a lost or stolen card.",
            List.of("block card", "freeze card"), List.of());

    private FeatureIndex index;

    @BeforeEach
    void build() {
        index = new FeatureIndex();
        index.rebuild(List.of(
                new FeatureIndex.IndexDocument(TRANSFER, unit(1, 0, 0)),
                new FeatureIndex.IndexDocument(TRANSFER_RECEIPT, unit(0.9, 0.1, 0)),
                new FeatureIndex.IndexDocument(SPENDING, unit(0, 1, 0)),
                new FeatureIndex.IndexDocument(ESTATEMENT, unit(0, 0, 1)),
                new FeatureIndex.IndexDocument(BLOCK_CARD, null)),
                new IndexStats(0, 0, 3, "test", Instant.now(), 0, 0, null));
    }

    @AfterEach
    void close() {
        index.close();
    }

    @Test
    @DisplayName("prefix typing finds the feature before the word is finished")
    void prefixMatches() {
        Map<String, ChannelHit> hits = byId(index.lexicalSearch("tran", 10));
        assertThat(hits).containsKeys("transfer", "proof_of_transfer");
        assertThat(hits.get("transfer").keywordScore()).isBetween(0.0, 1.0).isGreaterThan(0);
        assertThat(hits).doesNotContainKey("block_card");
    }

    @Test
    @DisplayName("stemming lines up inflections the user types with the registry's words")
    void stems() {
        Map<String, ChannelHit> hits = byId(index.lexicalSearch("transferring statements", 10));
        assertThat(hits.get("transfer").keywordScore()).isGreaterThan(0);
        assertThat(hits.get("e_statement").keywordScore()).isGreaterThan(0);
    }

    @Test
    @DisplayName("an alias typed exactly scores a full trigram match")
    void abbreviationViaTrigrams() {
        Map<String, ChannelHit> hits = byId(index.lexicalSearch("trf", 10));
        assertThat(hits.get("transfer").trigramScore()).isEqualTo(1.0);
        assertThat(hits).doesNotContainKey("spending_insights");
    }

    @Test
    @DisplayName("a typo still lands on the right feature through trigrams")
    void typoViaTrigrams() {
        Map<String, ChannelHit> hits = byId(index.lexicalSearch("estatment", 10));
        assertThat(hits.get("e_statement").trigramScore())
                .isGreaterThanOrEqualTo(FeatureIndex.WORD_SIMILARITY_THRESHOLD);
    }

    @Test
    @DisplayName("weak trigram overlap is filtered out, as pg_trgm's thresholds did")
    void trigramNoise() {
        // "insights" shares "s " / "ts " style fragments with plenty of
        // things; only Spending Insights should be a trigram hit.
        Map<String, ChannelHit> hits = byId(index.lexicalSearch("insights", 10));
        assertThat(hits.get("spending_insights").trigramScore()).isEqualTo(1.0);
        assertThat(hits.values().stream().filter(h -> h.trigramScore() > 0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("lexical results are capped and ordered by the better channel")
    void limitAndOrder() {
        List<ChannelHit> hits = index.lexicalSearch("transfer money", 1);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).featureId()).isEqualTo("transfer");
    }

    @Test
    @DisplayName("nearest neighbour comes first, with plain cosine as the score")
    void knn() {
        List<ChannelHit> hits = index.vectorSearch(unit(0.95, 0.05, 0), 3);
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).featureId()).isEqualTo("transfer");
        assertThat(hits.get(0).vectorScore()).isCloseTo(0.9986, org.assertj.core.data.Offset.offset(0.001));
        assertThat(hits.get(1).featureId()).isEqualTo("proof_of_transfer");
        assertThat(hits).extracting(ChannelHit::featureId).doesNotContain("block_card");
    }

    @Test
    @DisplayName("a feature without a vector is still findable lexically")
    void vectorlessFeatureStillIndexed() {
        assertThat(byId(index.lexicalSearch("block card", 10))).containsKey("block_card");
        assertThat(index.stats().features()).isEqualTo(5);
        assertThat(index.stats().withVector()).isEqualTo(4);
    }

    @Test
    @DisplayName("an empty index answers empty, never throws")
    void emptyIndex() {
        FeatureIndex empty = new FeatureIndex();
        assertThat(empty.isReady()).isFalse();
        assertThat(empty.lexicalSearch("transfer", 10)).isEmpty();
        assertThat(empty.vectorSearch(unit(1, 0, 0), 10)).isEmpty();
        empty.rebuild(List.of(), new IndexStats(0, 0, 3, "test", Instant.now(), 0, 0, null));
        assertThat(empty.lexicalSearch("transfer", 10)).isEmpty();
        assertThat(empty.vectorSearch(unit(1, 0, 0), 10)).isEmpty();
        empty.close();
    }

    @Test
    @DisplayName("a rebuild swaps generations without disturbing a search on the old one")
    void rebuildSwapsGeneration() {
        index.rebuild(List.of(new FeatureIndex.IndexDocument(SPENDING, unit(0, 1, 0))),
                new IndexStats(0, 0, 3, "test", Instant.now(), 0, 0, null));
        assertThat(byId(index.lexicalSearch("transfer", 10))).isEmpty();
        assertThat(byId(index.lexicalSearch("spending", 10))).containsKey("spending_insights");
        assertThat(index.stats().features()).isEqualTo(1);
    }

    private static Map<String, ChannelHit> byId(List<ChannelHit> hits) {
        return hits.stream().collect(Collectors.toMap(ChannelHit::featureId, Function.identity()));
    }

    private static float[] unit(double x, double y, double z) {
        double norm = Math.sqrt(x * x + y * y + z * z);
        return new float[] {(float) (x / norm), (float) (y / norm), (float) (z / norm)};
    }

    private static Feature feature(String id, String name, String description,
                                   List<String> keywords, List<String> aliases) {
        return new Feature(id, name, description, "test", "/" + id, keywords, aliases, false, List.of());
    }
}
