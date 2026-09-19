package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Zero-dependency fallback extractor: no model, no network.
 *
 * <p>It exists for three reasons — local development without a GPU, CI that
 * must not call an LLM, and a safety net when the configured model is
 * unreachable. It dispatches purely on each slot's declared {@code resolver}
 * and {@code type}, so it stays feature-agnostic like everything else in the
 * pipeline.
 *
 * <p>It is deliberately conservative: it fills only what it can read directly
 * off the text. Anything it is unsure about is left blank for the user.
 */
@Component
@ConditionalOnProperty(name = "search.llm.provider", havingValue = "heuristic",
        matchIfMissing = true)
public class HeuristicSlotExtractor implements SlotExtractor {

    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![\\w.])(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kmb])?(?![\\w])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?\\d[\\d\\-\\s]{7,17}\\d)(?!\\d)");
    private static final Pattern RECIPIENT_AFTER_PREPOSITION = Pattern.compile(
            "\\b(?:to|for|with|ke|kepada|dengan|utk|untuk)\\s+([\\p{L}][\\p{L}\\-.']*(?:\\s+[\\p{L}][\\p{L}\\-.']*)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH = Pattern.compile(
            "\\b(january|february|march|april|may|june|july|august|september|october|"
                    + "november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE_PERIOD = Pattern.compile(
            "\\b(last|this|next|previous)\\s+(week|month|quarter|year)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");

    private final Set<String> currencyCodes;
    private final Set<String> stopWords;
    private final Set<String> dateWords;

    public HeuristicSlotExtractor(SearchProperties properties) {
        this.currencyCodes = properties.getSignal().currencyCodeSet();
        this.stopWords = properties.getSignal().stopWordSet();
        this.dateWords = properties.getSignal().dateWordSet();
    }

    @Override
    public ExtractionResult extract(Feature feature, String query) {
        String text = query == null ? "" : query.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> vocabulary = feature.vocabulary();

        Map<String, Object> slots = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        // A literal in the query names one thing. Without this, "transfer to
        // mom's 10000 usd" fills both `recipient` and `source_account` with
        // "mom", because both declare a name-shaped resolver.
        Set<String> claimed = new HashSet<>();
        boolean amountTaken = false;

        for (SlotDefinition slot : feature.slots()) {
            Object value = switch (slot.resolver()) {
                case "amount" -> amountTaken ? null : firstAmount(lower);
                case "currency" -> firstCurrency(lower);
                case "phone" -> firstMatch(PHONE, text);
                case "date" -> firstMatch(ISO_DATE, text);
                case "period" -> firstPeriod(text);
                case "payee", "account" -> firstRecipient(text, vocabulary, claimed);
                default -> slot.isNumeric() && !amountTaken ? firstAmount(lower) : null;
            };

            if (value == null) {
                continue;
            }
            if (slot.isNumeric()) {
                Double number = toNumber(String.valueOf(value));
                if (number == null) {
                    continue;
                }
                value = number == Math.rint(number) ? (Object) number.longValue() : number;
                amountTaken = true;
            }
            claimed.add(String.valueOf(value).toLowerCase(Locale.ROOT));
            slots.put(slot.name(), value);
        }

        if (!slots.isEmpty()) {
            warnings.add("extracted without an LLM (provider=heuristic); "
                    + "set search.llm.provider=ollama or anthropic for full coverage");
        }
        return new ExtractionResult(true, Map.copyOf(slots), providerName(), List.copyOf(warnings));
    }

    @Override
    public String providerName() {
        return "heuristic";
    }

    private static String firstAmount(String lower) {
        Matcher m = AMOUNT.matcher(lower);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1).replace(",", "");
        String suffix = m.group(2);
        return suffix == null ? digits : digits + suffix;
    }

    private String firstCurrency(String lower) {
        for (String token : lower.split("[^\\p{Alnum}]+")) {
            if (currencyCodes.contains(token)) {
                return token.toUpperCase(Locale.ROOT);
            }
        }
        if (lower.indexOf('$') >= 0) {
            return "USD";
        }
        if (lower.indexOf('€') >= 0) {
            return "EUR";
        }
        if (lower.indexOf('£') >= 0) {
            return "GBP";
        }
        return null;
    }

    private static String firstPeriod(String text) {
        String month = firstMatch(MONTH, text);
        if (month != null) {
            return month;
        }
        return firstMatch(RELATIVE_PERIOD, text);
    }

    private String firstRecipient(String text, Set<String> vocabulary, Set<String> claimed) {
        Matcher m = RECIPIENT_AFTER_PREPOSITION.matcher(text);
        while (m.find()) {
            String candidate = m.group(1).trim().replaceAll("'s$", "");
            String head = candidate.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (vocabulary.contains(head)
                    || stopWords.contains(head)
                    || currencyCodes.contains(head)
                    // "for march" is a period, not a payee.
                    || dateWords.contains(head)
                    || claimed.contains(candidate.toLowerCase(Locale.ROOT))) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static Double toNumber(String value) {
        Matcher suffix = Pattern.compile("^([0-9.]+)([kmb])$", Pattern.CASE_INSENSITIVE)
                .matcher(value);
        try {
            if (suffix.matches()) {
                double base = Double.parseDouble(suffix.group(1));
                return switch (suffix.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> base * 1_000;
                    case "m" -> base * 1_000_000;
                    case "b" -> base * 1_000_000_000;
                    default -> base;
                };
            }
            return Double.valueOf(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
