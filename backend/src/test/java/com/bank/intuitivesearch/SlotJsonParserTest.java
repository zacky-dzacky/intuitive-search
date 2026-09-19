package com.bank.intuitivesearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.extraction.ExtractionResult;
import com.bank.intuitivesearch.extraction.SlotJsonParser;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Small instruct models return imperfect JSON. Nothing they can emit is
 * allowed to produce an exception or a wrong pre-filled field.
 */
class SlotJsonParserTest {

    private final SlotJsonParser parser = new SlotJsonParser(new ObjectMapper());

    private static final Feature TRANSFER = new Feature(
            "transfer", "Transfer Money", "", "payments", "/payments/transfer",
            List.of("transfer"), List.of("trf"), true,
            List.of(
                    new SlotDefinition("recipient", "string", "", true, List.of(), "payee"),
                    new SlotDefinition("amount", "number", "", true, List.of(), "amount"),
                    new SlotDefinition("currency", "string", "", false, List.of(), "currency"),
                    new SlotDefinition("frequency", "string", "", false,
                            List.of("once", "monthly"), "none")));

    @Test
    @DisplayName("clean JSON parses into typed slots")
    void parsesCleanJson() {
        ExtractionResult result = parser.parse(
                "{\"recipient\":\"mom\",\"amount\":10000,\"currency\":\"USD\"}", TRANSFER, "test");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.slots())
                .containsEntry("recipient", "mom")
                .containsEntry("amount", 10000L)
                .containsEntry("currency", "USD");
    }

    @Test
    @DisplayName("markdown code fences are stripped")
    void stripsCodeFences() {
        ExtractionResult result = parser.parse(
                "```json\n{\"amount\": 500}\n```", TRANSFER, "test");

        assertThat(result.slots()).containsEntry("amount", 500L);
        assertThat(result.warnings()).anyMatch(w -> w.contains("code fence"));
    }

    @Test
    @DisplayName("a JSON object is recovered from surrounding prose")
    void recoversFromProse() {
        ExtractionResult result = parser.parse(
                "Sure! Here is the extraction: {\"amount\": 42} Let me know if that helps.",
                TRANSFER, "test");

        assertThat(result.slots()).containsEntry("amount", 42L);
    }

    @Test
    @DisplayName("trailing commas and single-quoted keys are repaired")
    void repairsMalformedJson() {
        ExtractionResult result = parser.parse(
                "{'recipient': \"dad\", \"amount\": 75,}", TRANSFER, "test");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.slots()).containsEntry("recipient", "dad");
    }

    @Test
    @DisplayName("slots the feature does not declare are dropped, not passed on")
    void dropsUnknownSlots() {
        ExtractionResult result = parser.parse(
                "{\"amount\": 10, \"execute_now\": true, \"otp\": \"123456\"}", TRANSFER, "test");

        assertThat(result.slots()).containsOnlyKeys("amount");
        assertThat(result.warnings()).anyMatch(w -> w.contains("execute_now"));
    }

    @Test
    @DisplayName("string amounts with separators and k/m suffixes are coerced")
    void coercesStringAmounts() {
        assertThat(parser.parse("{\"amount\": \"10,000\"}", TRANSFER, "t").slots())
                .containsEntry("amount", 10000L);
        assertThat(parser.parse("{\"amount\": \"2.5m\"}", TRANSFER, "t").slots())
                .containsEntry("amount", 2500000L);
    }

    @Test
    @DisplayName("values outside a declared enum are dropped")
    void dropsOutOfEnumValues() {
        ExtractionResult result = parser.parse(
                "{\"frequency\": \"hourly\"}", TRANSFER, "test");

        assertThat(result.slots()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("out-of-enum"));
    }

    @Test
    @DisplayName("unusable output degrades to an empty result rather than throwing")
    void degradesOnGarbage() {
        assertThat(parser.parse("I cannot help with that.", TRANSFER, "t").succeeded()).isFalse();
        assertThat(parser.parse("", TRANSFER, "t").succeeded()).isFalse();
        assertThat(parser.parse(null, TRANSFER, "t").succeeded()).isFalse();
    }

    @Test
    @DisplayName("null and empty values never reach the form")
    void skipsNullValues() {
        ExtractionResult result = parser.parse(
                "{\"recipient\": null, \"currency\": \"\", \"amount\": 5}", TRANSFER, "test");

        assertThat(result.slots()).containsOnlyKeys("amount");
    }
}
