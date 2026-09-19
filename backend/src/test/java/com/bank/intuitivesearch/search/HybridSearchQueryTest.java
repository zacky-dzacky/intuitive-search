package com.bank.intuitivesearch.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.model.Feature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Query construction and ranking for Stage 1 — the part that runs on every keystroke. */
class HybridSearchQueryTest {

    /** Every term is unique across this fixture, so IDF is a no-op here. */
    private static final HybridSearchService.DoubleUnaryTermWeight FLAT_IDF = term -> 1.0;

    private static final Feature TRANSFER = feature("transfer", "Transfer Money",
            List.of("transfer", "send", "money"), List.of("trf"));
    private static final Feature TRANSFER_RECEIPT = feature("proof_of_transfer", "Transfer Receipt",
            List.of("receipt", "proof of transfer", "transfer slip"), List.of());
    private static final Feature SPENDING = feature("spending_insights", "Spending Insights",
            List.of("insights", "spending", "where my money"), List.of());
    private static final Feature BLOCK_CARD = feature("block_card", "Block Card",
            List.of("block card", "freeze card"), List.of());
    private static final Feature CARD_LIST = feature("card_list", "My Cards",
            List.of("cards", "debit card"), List.of("card"));

    private static double containment(Feature feature, String query) {
        return HybridSearchService.containmentScore(
                feature, List.of(HybridSearchService.normalise(query).split("[^a-z0-9]+")), FLAT_IDF);
    }

    private static Feature feature(String id, String name, List<String> keywords, List<String> aliases) {
        return new Feature(id, name, "", "test", "/" + id, keywords, aliases, false, List.of());
    }

    @Test
    @DisplayName("parameter text cannot dilute the feature the query names")
    void namingSurvivesParameterPayload() {
        // The whole point of this channel: "transfer" still names Transfer
        // Money however much payload follows it, while Transfer Receipt —
        // whose terms are longer phrases — is not named at all.
        String query = "transfer to mom's 10000 usd";
        assertThat(containment(TRANSFER, query)).isGreaterThan(0.7);
        assertThat(containment(TRANSFER_RECEIPT, query)).isZero();
    }

    @Test
    @DisplayName("a longer, more specific term outranks a generic keyword")
    void specificPhraseBeatsGenericKeyword() {
        // "money" is a keyword of Transfer; "where my money" is a phrase of
        // Spending Insights. The longer term must win.
        String query = "where did my money go";
        assertThat(containment(SPENDING, query))
                .isGreaterThan(containment(TRANSFER, query));
    }

    @Test
    @DisplayName("term tokens need not be adjacent in the query")
    void matchesNonAdjacentTokens() {
        // Users write "block my card" for the term "block card".
        String query = "block my card";
        assertThat(containment(BLOCK_CARD, query))
                .isGreaterThan(containment(CARD_LIST, query));
    }

    @Test
    @DisplayName("a term is not named unless every one of its tokens appears")
    void requiresCompleteTerms() {
        assertThat(containment(BLOCK_CARD, "freeze")).isZero();
        assertThat(containment(TRANSFER_RECEIPT, "transfer")).isZero();
    }

    @Test
    @DisplayName("mid-word typing earns partial credit on complete terms only")
    void prefixTypingRanksTheRightFeature() {
        // "tran" half-covers Transfer Money's own keyword, and covers none of
        // Transfer Receipt's multi-word terms.
        assertThat(containment(TRANSFER, "tran"))
                .isGreaterThan(0)
                .isLessThan(containment(TRANSFER, "transfer"));
        assertThat(containment(TRANSFER_RECEIPT, "tran")).isZero();
    }

    @Test
    @DisplayName("fragments shorter than three characters are not evidence")
    void ignoresVeryShortFragments() {
        assertThat(containment(TRANSFER, "tr")).isZero();
    }

    @Test
    @DisplayName("tsquery uses prefix matching so typeahead matches partial words")
    void buildsPrefixTsQuery() {
        assertThat(HybridSearchService.toTsQuery("transfer money"))
                .isEqualTo("transfer:* | money:*");
    }

    @Test
    @DisplayName("punctuation cannot inject tsquery syntax")
    void sanitisesTsQuery() {
        assertThat(HybridSearchService.toTsQuery("e-statement & !x"))
                .isEqualTo("e:* | statement:* | x:*");
        assertThat(HybridSearchService.toTsQuery("'; drop table features--"))
                .doesNotContain(";", "'", "-");
    }

    @Test
    @DisplayName("empty input produces an empty tsquery, not a broken one")
    void handlesEmptyQuery() {
        assertThat(HybridSearchService.toTsQuery("")).isEmpty();
        assertThat(HybridSearchService.normalise("  Transfer   MONEY ")).isEqualTo("transfer money");
        assertThat(HybridSearchService.normalise(null)).isEmpty();
    }
}
