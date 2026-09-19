package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Stage 2 against a locally served small instruct model (Ollama, or anything
 * exposing the same API such as a vLLM front-end).
 *
 * <p>The feature's slot schema is passed as Ollama's {@code format} parameter,
 * which constrains decoding to a conforming JSON object. That is what keeps
 * generation short enough for the &lt;400ms budget — the model cannot ramble.
 */
@Component
@ConditionalOnProperty(name = "search.llm.provider", havingValue = "ollama")
public class OllamaSlotExtractor implements SlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(OllamaSlotExtractor.class);

    private final RestClient restClient;
    private final PromptBuilder promptBuilder;
    private final SlotJsonParser parser;
    private final SearchProperties properties;

    public OllamaSlotExtractor(RestClient llmRestClient,
                               PromptBuilder promptBuilder,
                               SlotJsonParser parser,
                               SearchProperties properties) {
        this.restClient = llmRestClient;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.properties = properties;
    }

    @Override
    public ExtractionResult extract(Feature feature, String query) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0);
        options.put("num_predict", properties.getLlm().getMaxOutputTokens());

        ChatRequest request = new ChatRequest(
                properties.getLlm().getOllama().getModel(),
                List.of(new ChatMessage("system", promptBuilder.systemPrompt()),
                        new ChatMessage("user", promptBuilder.userPrompt(feature, query))),
                false,
                promptBuilder.jsonSchema(feature),
                options);

        try {
            ChatResponse response = restClient.post()
                    .uri("/api/chat")
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.message() == null) {
                return ExtractionResult.empty(providerName(), "empty response from local model");
            }
            return parser.parse(response.message().content(), feature, providerName());
        } catch (Exception e) {
            // Timeout, connection refused, model not pulled — all degrade to
            // navigation rather than failing the search.
            log.warn("Local LLM extraction failed for feature '{}': {}",
                    feature.featureId(), e.toString());
            return ExtractionResult.empty(providerName(),
                    "local model unavailable: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    record ChatRequest(
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<ChatMessage> messages,
            @JsonProperty("stream") boolean stream,
            @JsonProperty("format") Map<String, Object> format,
            @JsonProperty("options") Map<String, Object> options) {}

    record ChatMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(
            @JsonProperty("model") String model,
            @JsonProperty("message") ChatMessage message,
            @JsonProperty("done") Boolean done) {}
}
