package com.bank.intuitivesearch.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stage 2 against Claude Haiku on the Anthropic API — the option for
 * deployments where on-prem hosting is not a requirement.
 *
 * <p>Extraction is expressed as a <em>tool call</em>: the feature's slot
 * schema becomes the tool's input schema, so the model returns a structured,
 * validated object instead of free text. That keeps generation short (a few
 * dozen tokens) which is what makes the &lt;400ms budget reachable over a
 * network hop. The text path is kept only as a fallback for the rare turn
 * where the model answers in prose.
 *
 * <p>Haiku is the right tier here: the task is short-input, short-output
 * structured extraction with no reasoning depth required.
 */
@Component
@ConditionalOnProperty(name = "search.llm.provider", havingValue = "anthropic")
public class AnthropicSlotExtractor implements SlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(AnthropicSlotExtractor.class);
    private static final String TOOL_NAME = "extract_slots";

    private final SearchProperties properties;
    private final PromptBuilder promptBuilder;
    private final SlotJsonParser parser;
    private final ObjectMapper objectMapper;
    private volatile AnthropicClient client;

    public AnthropicSlotExtractor(SearchProperties properties,
                                  PromptBuilder promptBuilder,
                                  SlotJsonParser parser,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExtractionResult extract(Feature feature, String query) {
        AnthropicClient anthropic;
        try {
            anthropic = client();
        } catch (Exception e) {
            log.warn("Anthropic client unavailable (no credentials?): {}", e.toString());
            return ExtractionResult.empty(providerName(), "anthropic client not configured");
        }

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(properties.getLlm().getAnthropic().getModel())
                    .maxTokens((long) properties.getLlm().getMaxOutputTokens())
                    .system(promptBuilder.systemPrompt())
                    .addTool(buildTool(feature))
                    .addUserMessage(promptBuilder.userPrompt(feature, query))
                    .build();

            Message response = anthropic.messages().create(params);

            Optional<String> toolInput = firstToolInput(response);
            if (toolInput.isPresent()) {
                return parser.parse(toolInput.get(), feature, providerName());
            }
            // Fallback: the model answered in prose. SlotJsonParser is built to
            // dig a JSON object out of that.
            return parser.parse(firstText(response), feature, providerName());
        } catch (Exception e) {
            log.warn("Anthropic extraction failed for feature '{}': {}",
                    feature.featureId(), e.toString());
            return ExtractionResult.empty(providerName(),
                    "anthropic call failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    /**
     * Builds the tool from the feature's slot definitions at request time.
     * One generic tool, parameterised by data — never one tool per feature.
     */
    private Tool buildTool(Feature feature) {
        Map<String, Object> schema = promptBuilder.jsonSchema(feature);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema.getOrDefault("properties", Map.of());

        Tool.InputSchema.Properties.Builder propertiesBuilder =
                Tool.InputSchema.Properties.builder();
        properties.forEach((name, definition) ->
                propertiesBuilder.putAdditionalProperty(name, JsonValue.from(definition)));

        return Tool.builder()
                .name(TOOL_NAME)
                .description("Record the parameters found in the user's query for the "
                        + feature.displayName() + " screen. Omit any parameter the query "
                        + "does not state.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(propertiesBuilder.build())
                        .required(List.of())
                        .build())
                .build();
    }

    private Optional<String> firstToolInput(Message response) {
        for (ContentBlock block : response.content()) {
            Optional<String> json = block.toolUse()
                    .filter(toolUse -> TOOL_NAME.equals(toolUse.name()))
                    .map(toolUse -> writeJson(toolUse._input()));
            if (json.isPresent()) {
                return json;
            }
        }
        return Optional.empty();
    }

    private static String firstText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElse("");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("Could not serialise tool input", e);
            return "";
        }
    }

    /** Built lazily so a missing API key doesn't break application startup. */
    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    // Credentials resolve from ANTHROPIC_API_KEY or an
                    // `ant auth login` profile — never hardcoded.
                    local = AnthropicOkHttpClient.builder()
                            .fromEnv()
                            .timeout(Duration.ofMillis(properties.getLlm().getTimeoutMs()))
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }
}
