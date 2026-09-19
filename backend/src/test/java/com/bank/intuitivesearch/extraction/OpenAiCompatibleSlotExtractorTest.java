package com.bank.intuitivesearch.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Stage 2 over the OpenAI chat-completions wire format. */
class OpenAiCompatibleSlotExtractorTest {

    private static final String BASE = "https://provider.example/v1";
    private static final Feature TRANSFER = new Feature("transfer", "Transfer Money",
            "Send money to a payee", "payments", "/transfer",
            List.of("transfer"), List.of("trf"), true,
            List.of(new SlotDefinition("recipient", "string", "who receives it", false, List.of(), "payee"),
                    new SlotDefinition("amount", "number", "how much", false, List.of(), "amount"),
                    new SlotDefinition("currency", "string", "ISO code", false, List.of("USD", "IDR"), "currency")));

    private MockRestServiceServer server;
    private OpenAiCompatibleSlotExtractor extractor;
    private SearchProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        properties.getLlm().setProvider("openai");
        properties.getLlm().getOpenai().setBaseUrl(BASE);
        properties.getLlm().getOpenai().setApiKey("k-123");
        properties.getLlm().getOpenai().setModel("gemini-3.6-flash");

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("Authorization", "Bearer k-123");
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper();
        extractor = new OpenAiCompatibleSlotExtractor(builder.build(),
                new PromptBuilder(), new SlotJsonParser(mapper), properties);
    }

    @Test
    @DisplayName("the request is plain chat-completions with the slot schema as response_format")
    void requestShape() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer k-123"))
                .andExpect(jsonPath("$.model").value("gemini-3.6-flash"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("extract_slots"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.amount").exists())
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant",
                          "content":"{\\"recipient\\":\\"mom\\",\\"amount\\":10000,\\"currency\\":\\"USD\\"}"},
                          "finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        ExtractionResult result = extractor.extract(TRANSFER, "transfer to mom's 10000 usd");
        assertThat(result.succeeded()).isTrue();
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.slots()).containsEntry("recipient", "mom").containsEntry("currency", "USD");
        server.verify();
    }

    @Test
    @DisplayName("reasoning_effort is sent only when configured — a non-reasoning model would reject it")
    void reasoningEffortOptional() {
        String ok = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}";
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));

        extractor.extract(TRANSFER, "send 50");
        properties.getLlm().getOpenai().setReasoningEffort("low");
        extractor.extract(TRANSFER, "send 50");
        server.verify();
    }

    @Test
    @DisplayName("a model that answers in prose still yields slots — the parser digs them out")
    void proseFallback() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant",
                          "content":"Sure! ```json\\n{\\"amount\\": 50}\\n```"}}]}
                        """, MediaType.APPLICATION_JSON));
        ExtractionResult result = extractor.extract(TRANSFER, "send 50");
        assertThat(result.succeeded()).isTrue();
        assertThat(result.slots()).containsKey("amount");
    }

    @Test
    @DisplayName("provider errors degrade to navigation, never to a failed search")
    void errorsDegrade() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        ExtractionResult result = extractor.extract(TRANSFER, "send 50");
        assertThat(result.succeeded()).isFalse();
        assertThat(result.hasSlots()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("model call failed"));
    }

    @Test
    @DisplayName("an empty choices list is an empty result, not an exception")
    void emptyChoices() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertThat(extractor.extract(TRANSFER, "send 50").succeeded()).isFalse();
    }

    @Test
    @DisplayName("missing configuration is reported without touching the network")
    void unconfigured() {
        properties.getLlm().getOpenai().setApiKey("");
        ExtractionResult result = extractor.extract(TRANSFER, "send 50");
        assertThat(result.succeeded()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("not configured"));
        server.verify();   // nothing was expected, nothing was called
    }
}
