package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Defensive parsing of whatever the model actually returned.
 *
 * <p>Small instruct models wrap JSON in code fences, prepend "Sure!", emit
 * trailing commas, or return a value of the wrong type. None of that is
 * allowed to produce a 500 or, worse, a wrong pre-filled form. Anything that
 * cannot be understood is dropped and recorded as a warning.
 */
@Component
public class SlotJsonParser {

    private static final Logger log = LoggerFactory.getLogger(SlotJsonParser.class);

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);
    private static final Pattern FIRST_OBJECT =
            Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern NUMERIC_SUFFIX =
            Pattern.compile("^([0-9]*\\.?[0-9]+)\\s*([kmb])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_CLEANUP =
            Pattern.compile("[^0-9.\\-]");

    private final ObjectMapper objectMapper;

    public SlotJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param rawText  the model's raw completion
     * @param feature  supplies the slot schema used to validate and coerce
     * @param provider name recorded on the result
     */
    public ExtractionResult parse(String rawText, Feature feature, String provider) {
        List<String> warnings = new ArrayList<>();

        String json = isolateJson(rawText, warnings);
        if (json == null) {
            return ExtractionResult.empty(provider, "model returned no JSON object");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception first) {
            String repaired = repair(json);
            try {
                root = objectMapper.readTree(repaired);
                warnings.add("repaired malformed JSON before parsing");
            } catch (Exception second) {
                log.debug("Unparseable extraction output from {}: {}", provider, rawText, second);
                return ExtractionResult.empty(provider, "model returned malformed JSON");
            }
        }

        if (root == null || !root.isObject()) {
            return ExtractionResult.empty(provider, "model output was not a JSON object");
        }

        Map<String, Object> slots = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            SlotDefinition definition = feature.slot(key);
            if (definition == null) {
                // The model invented a parameter the feature does not declare.
                // Drop it — never pass an unknown key to a pre-filled form.
                warnings.add("dropped unknown slot '" + key + "'");
                return;
            }
            Object value = coerce(entry.getValue(), definition, warnings);
            if (value != null) {
                slots.put(definition.name(), value);
            }
        });

        return new ExtractionResult(true, Map.copyOf(slots), provider, List.copyOf(warnings));
    }

    /** Pull the JSON object out of prose, code fences, or a bare object. */
    private static String isolateJson(String rawText, List<String> warnings) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = rawText.trim();

        Matcher fenced = CODE_FENCE.matcher(text);
        if (fenced.find()) {
            warnings.add("stripped markdown code fence from model output");
            text = fenced.group(1).trim();
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        Matcher object = FIRST_OBJECT.matcher(text);
        if (object.find()) {
            warnings.add("extracted JSON object from surrounding prose");
            return object.group();
        }
        return null;
    }

    /** Last-resort fixes for the mistakes small models actually make. */
    private static String repair(String json) {
        return json
                .replaceAll(",\\s*}", "}")      // trailing comma before }
                .replaceAll(",\\s*]", "]")      // trailing comma before ]
                .replaceAll("'([^']*)'\\s*:", "\"$1\":")  // single-quoted keys
                .replace("“", "\"")        // smart quotes
                .replace("”", "\"");
    }

    /** Type-coerce a value into what the slot declares, or drop it. */
    private static Object coerce(JsonNode node, SlotDefinition slot, List<String> warnings) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray() || node.isObject()) {
            // Only scalars pre-fill a form field.
            String flattened = node.isArray() && node.size() > 0 ? node.get(0).asText() : null;
            if (flattened != null && !slot.isNumeric()) {
                warnings.add("flattened array value for slot '" + slot.name() + "'");
                return flattened;
            }
            warnings.add("dropped non-scalar value for slot '" + slot.name() + "'");
            return null;
        }

        if (slot.isNumeric()) {
            Double number = toNumber(node);
            if (number == null) {
                warnings.add("dropped non-numeric value for numeric slot '" + slot.name() + "'");
                return null;
            }
            // Emit an integral value as a long so "10000" doesn't render "10000.0".
            if (number == Math.rint(number) && !number.isInfinite()
                    && Math.abs(number) < 9.0e15) {
                return number.longValue();
            }
            return number;
        }

        if (slot.isBoolean()) {
            if (node.isBoolean()) {
                return node.booleanValue();
            }
            String text = node.asText("").trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("yes")) {
                return Boolean.TRUE;
            }
            if (text.equals("false") || text.equals("no")) {
                return Boolean.FALSE;
            }
            warnings.add("dropped non-boolean value for slot '" + slot.name() + "'");
            return null;
        }

        String text = node.asText("").trim();
        if (text.isEmpty() || text.equalsIgnoreCase("null") || text.equalsIgnoreCase("none")) {
            return null;
        }
        if (!slot.enumValues().isEmpty()) {
            for (String allowed : slot.enumValues()) {
                if (allowed.equalsIgnoreCase(text)) {
                    return allowed;
                }
            }
            warnings.add("dropped out-of-enum value '" + text + "' for slot '" + slot.name() + "'");
            return null;
        }
        return text;
    }

    static Double toNumber(JsonNode node) {
        if (node.isNumber()) {
            return node.doubleValue();
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        Matcher suffix = NUMERIC_SUFFIX.matcher(text.replace(",", ""));
        if (suffix.matches()) {
            double base = Double.parseDouble(suffix.group(1));
            return switch (suffix.group(2).toLowerCase(Locale.ROOT)) {
                case "k" -> base * 1_000;
                case "m" -> base * 1_000_000;
                case "b" -> base * 1_000_000_000;
                default -> base;
            };
        }
        String cleaned = NUMERIC_CLEANUP.matcher(text).replaceAll("");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return null;
        }
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
