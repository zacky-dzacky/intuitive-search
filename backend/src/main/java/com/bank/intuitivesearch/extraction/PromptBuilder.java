package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ONE prompt template for every feature.
 *
 * <p>The per-feature part is injected at runtime from {@code features.slots} —
 * there is no prompt file per feature and no {@code switch} on feature id.
 * Feature #51 gets a correct prompt the moment its row exists.
 */
@Component
public class PromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You extract structured parameters from a banking app user's search query.

            Rules:
            - Return ONLY a single JSON object. No prose, no markdown, no code fences.
            - Include a key ONLY if the query states or clearly implies its value.
            - Never invent, default, or infer amounts, recipients, accounts or dates.
            - Copy names and references verbatim as the user wrote them; resolving them
              to real accounts happens later in the pipeline.
            - Amounts must be plain numbers: no currency symbols, no thousand separators.
              "10k" -> 10000. "2.5m" -> 2500000.
            - Currencies must be ISO-4217 uppercase codes (USD, EUR, SGD, IDR).
            - Dates must be ISO-8601 (YYYY-MM-DD). Relative dates are resolved against
              the provided current date.
            - If nothing can be extracted, return {}.
            """;

    /** System instruction — identical for every feature, so it caches well. */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /** The per-request half: this feature's schema plus the user's text. */
    public String userPrompt(Feature feature, String query) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Feature: ").append(feature.displayName())
          .append(" (").append(feature.featureId()).append(")\n");
        if (!feature.description().isBlank()) {
            sb.append("Purpose: ").append(feature.description()).append('\n');
        }
        sb.append("Current date: ").append(LocalDate.now()).append("\n\n");
        sb.append("Parameters you may extract:\n");
        for (SlotDefinition slot : feature.slots()) {
            sb.append("- ").append(slot.name())
              .append(" (").append(slot.type());
            if (slot.required()) {
                sb.append(", required");
            }
            if (!slot.enumValues().isEmpty()) {
                sb.append(", one of: ").append(String.join(" | ", slot.enumValues()));
            }
            sb.append("): ").append(slot.description()).append('\n');
        }
        sb.append("\nUser query: \"").append(query).append("\"\n");
        sb.append("\nJSON:");
        return sb.toString();
    }

    /**
     * JSON Schema for the extraction result, built from the same slot
     * definitions. Used for constrained decoding (Ollama {@code format},
     * Anthropic tool input schema) so the model cannot emit anything but a
     * conforming object — which keeps generation short and fast.
     *
     * <p>Note that {@code required} is deliberately left empty: a user may
     * legitimately type a partial command, and a schema that forces every
     * required slot would make the model hallucinate values to satisfy it.
     * Requiredness is enforced on the form, where the user can see it.
     */
    public Map<String, Object> jsonSchema(Feature feature) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (SlotDefinition slot : feature.slots()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonType(slot));
            property.put("description", slot.description());
            if (!slot.enumValues().isEmpty()) {
                property.put("enum", List.copyOf(slot.enumValues()));
            }
            properties.put(slot.name(), property);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<String>());
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String jsonType(SlotDefinition slot) {
        if (slot.isNumeric()) {
            return "number";
        }
        if (slot.isBoolean()) {
            return "boolean";
        }
        return "string";
    }
}
