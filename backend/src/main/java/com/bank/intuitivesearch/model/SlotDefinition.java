package com.bank.intuitivesearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One parameter a feature accepts. Comes straight from {@code features.slots}
 * JSONB — this is the contract that drives Stage 2 (what the LLM is asked to
 * extract) and Stage 3 (which resolver runs).
 *
 * @param name        slot key, e.g. {@code recipient}
 * @param type        {@code string} | {@code number} | {@code boolean}
 * @param description natural-language hint injected into the LLM prompt
 * @param required    whether the pre-filled form cannot be submitted without it
 * @param enumValues  optional closed value set
 * @param resolver    Stage-3 strategy id: payee | account | currency | amount |
 *                    date | period | phone | none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlotDefinition(
        String name,
        String type,
        String description,
        boolean required,
        @JsonProperty("enum") List<String> enumValues,
        String resolver) {

    public SlotDefinition {
        type = (type == null || type.isBlank()) ? "string" : type;
        description = description == null ? "" : description;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        resolver = (resolver == null || resolver.isBlank()) ? "none" : resolver;
    }

    public boolean isNumeric() {
        return "number".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type);
    }

    public boolean isBoolean() {
        return "boolean".equalsIgnoreCase(type);
    }

    /**
     * True when a bare word from the query could fill this slot — a name, a
     * nickname, a label. Numbers, dates and currencies each carry their own
     * signal weight; free text carries none, which is why the signal detector
     * has to know a slot like this exists.
     */
    public boolean isFreeText() {
        if (isNumeric() || isBoolean() || !enumValues.isEmpty()) {
            return false;
        }
        return switch (resolver) {
            case "payee", "account", "none" -> true;
            default -> false;   // amount, currency, date, period, phone
        };
    }
}
