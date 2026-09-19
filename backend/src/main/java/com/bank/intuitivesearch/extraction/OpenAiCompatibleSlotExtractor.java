package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Stage 2 against any provider that speaks the OpenAI chat-completions wire
 * format: Gemini today, Azure AI Foundry when the sandbox lands. Both accept
 * a plain API key, so moving between them is three configuration values
 * ({@code base-url}, {@code api-key}, {@code model}) and no code.
 *
 * <p>The feature's slot schema goes in {@code response_format} as a JSON
 * schema, which both providers use to constrain decoding. Should a provider
 * ignore it and answer in prose, {@link SlotJsonParser} digs the object out.
 */
@Component
@ConditionalOnProperty(name = "search.llm.provider", havingValue = "openai")
public class OpenAiCompatibleSlotExtractor implements SlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleSlotExtractor.class);
    private static final String SCHEMA_NAME = "extract_slots";

    private final RestClient restClient;
    private final PromptBuilder promptBuilder;
    private final SlotJsonParser parser;
    private final SearchProperties properties;

    public OpenAiCompatibleSlotExtractor(RestClient openAiChatRestClient,
                                        PromptBuilder promptBuilder,
                                        SlotJsonParser parser,
                                        SearchProperties properties) {
        this.restClient = openAiChatRestClient;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.properties = properties;
    }

    @Override
    public ExtractionResult extract(Feature feature, String query) {
        SearchProperties.OpenAi config = properties.getLlm().getOpenai();
        if (!config.isConfigured()) {
            return ExtractionResult.empty(providerName(),
                    "openai provider not configured (base-url, api-key, model)");
        }

        String reasoning = config.getReasoningEffort();
        ChatRequest request = new ChatRequest(
                config.getModel(),
                List.of(new ChatMessage("system", promptBuilder.systemPrompt()),
                        new ChatMessage("user", promptBuilder.userPrompt(feature, query))),
                0,
                properties.getLlm().getMaxOutputTokens(),
                new ResponseFormat("json_schema",
                        new JsonSchema(SCHEMA_NAME, promptBuilder.jsonSchema(feature), false)),
                reasoning == null || reasoning.isBlank() ? null : reasoning);

        try {
            ChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().get(0).message() == null) {
                return ExtractionResult.empty(providerName(), "empty response from model");
            }
            return parser.parse(response.choices().get(0).message().content(), feature, providerName());
        } catch (Exception e) {
            // Timeout, 401, 429, provider outage — all degrade to navigation
            // rather than failing the search.
            log.warn("OpenAI-compatible extraction failed for feature '{}': {}",
                    feature.featureId(), e.toString());
            return ExtractionResult.empty(providerName(),
                    "model call failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public String providerName() {
        return "openai";
    }

    record ChatRequest(
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<ChatMessage> messages,
            @JsonProperty("temperature") double temperature,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("response_format") ResponseFormat responseFormat,
            // Only sent when configured: a non-reasoning model rejects it.
            @JsonProperty("reasoning_effort") @JsonInclude(JsonInclude.Include.NON_NULL)
            String reasoningEffort) {}

    record ChatMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content) {}

    record ResponseFormat(
            @JsonProperty("type") String type,
            @JsonProperty("json_schema") JsonSchema jsonSchema) {}

    /**
     * {@code strict} stays false: strict mode requires every property to be
     * listed as required, and the whole point of the schema is that slots the
     * user did not mention are omitted.
     */
    record JsonSchema(
            @JsonProperty("name") String name,
            @JsonProperty("schema") Map<String, Object> schema,
            @JsonProperty("strict") boolean strict) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(
            @JsonProperty("choices") List<Choice> choices,
            @JsonProperty("model") String model) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            @JsonProperty("message") ChatMessage message,
            @JsonProperty("finish_reason") String finishReason) {}
}
