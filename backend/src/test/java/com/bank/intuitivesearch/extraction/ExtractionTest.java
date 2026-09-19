package com.bank.intuitivesearch.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExtractionTest {

    private static final Feature TRANSFER = new Feature(
            "transfer", "Transfer Money", "Send money", "payments", "/payments/transfer",
            List.of("transfer", "send", "money"), List.of("trf"), true,
            List.of(
                    new SlotDefinition("recipient", "string", "who to pay", true, List.of(), "payee"),
                    new SlotDefinition("amount", "number", "how much", true, List.of(), "amount"),
                    new SlotDefinition("currency", "string", "iso code", false, List.of(), "currency")));

    private static final Feature STATEMENT = new Feature(
            "download_e_statement", "Download e-Statement", "Get a PDF statement", "accounts",
            "/accounts/statements", List.of("statement"), List.of("e-stmt"), true,
            List.of(new SlotDefinition("period", "string", "which month", false, List.of(), "period")));

    @Nested
    @DisplayName("Prompt construction")
    class Prompting {

        private final PromptBuilder builder = new PromptBuilder();

        @Test
        @DisplayName("one template, parameterised by the feature's own slots")
        void injectsSlotSchema() {
            String prompt = builder.userPrompt(TRANSFER, "transfer to mom's 10000 usd");

            assertThat(prompt)
                    .contains("Transfer Money")
                    .contains("recipient (string, required)")
                    .contains("amount (number, required)")
                    .contains("transfer to mom's 10000 usd");
        }

        @Test
        @DisplayName("one system prompt is shared by every feature")
        void systemPromptIsFeatureIndependent() {
            // The invariant that makes feature #51 free: the instruction half
            // of the prompt is a constant, and only the user half varies.
            assertThat(builder.systemPrompt())
                    .isEqualTo(builder.systemPrompt())
                    .doesNotContain("Transfer Money", "transfer");

            String forTransfer = builder.userPrompt(TRANSFER, "q");
            String forStatement = builder.userPrompt(STATEMENT, "q");
            assertThat(forTransfer).isNotEqualTo(forStatement);
            assertThat(forStatement).contains("period").doesNotContain("recipient");
        }

        @Test
        @DisplayName("the JSON schema mirrors the slot definitions")
        void buildsJsonSchema() {
            Map<String, Object> schema = builder.jsonSchema(TRANSFER);

            assertThat(schema)
                    .containsEntry("type", "object")
                    .containsEntry("additionalProperties", false);

            assertThat(schema.get("properties"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsOnlyKeys("recipient", "amount", "currency");
        }

        @Test
        @DisplayName("no slot is marked required, so the model never invents a value")
        void schemaRequiresNothing() {
            assertThat(builder.jsonSchema(TRANSFER).get("required"))
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Heuristic fallback extractor")
    class Heuristic {

        private final HeuristicSlotExtractor extractor =
                new HeuristicSlotExtractor(properties());

        @Test
        @DisplayName("reads recipient, amount and currency off the worked example")
        void extractsWorkedExample() {
            ExtractionResult result = extractor.extract(TRANSFER, "transfer to mom's 10000 usd");

            assertThat(result.slots())
                    .containsEntry("recipient", "mom")
                    .containsEntry("amount", 10000L)
                    .containsEntry("currency", "USD");
        }

        @Test
        @DisplayName("fills nothing when the query carries nothing")
        void extractsNothingFromBareCommand() {
            assertThat(extractor.extract(TRANSFER, "transfer").slots()).isEmpty();
        }

        @Test
        @DisplayName("'with' introduces a recipient just as 'to' does")
        void readsRecipientAfterWith() {
            assertThat(extractor.extract(TRANSFER, "transfer with mom 500").slots())
                    .containsEntry("recipient", "mom")
                    .containsEntry("amount", 500L);
        }

        @Test
        @DisplayName("a currency symbol implies the ISO code")
        void mapsCurrencySymbol() {
            assertThat(extractor.extract(TRANSFER, "send $250 to dad").slots())
                    .containsEntry("currency", "USD")
                    .containsEntry("amount", 250L);
        }

        private SearchProperties properties() {
            SearchProperties properties = new SearchProperties();
            properties.getSignal().setCurrencyCodes("usd,eur,gbp,sgd,idr");
            properties.getSignal().setStopWords("a,the,to,for,my,me,i,please,s");
            return properties;
        }
    }
}
