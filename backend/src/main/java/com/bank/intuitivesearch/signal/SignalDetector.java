package com.bank.intuitivesearch.signal;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SignalReport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The cheap gate between Stage 1 and Stage 2.
 *
 * <p><b>Completely feature-agnostic.</b> It knows nothing about transfers or
 * statements. It asks two questions:
 *
 * <ol>
 *   <li>Does the matched feature's <em>schema</em> even accept parameters?
 *       ({@code has_params} + a non-empty {@code slots} array.) If not, stop —
 *       the LLM can never be called.</li>
 *   <li>Does the query carry generic evidence of extractable content —
 *       numbers, currency tokens, date words, or tokens beyond the feature's
 *       own vocabulary?</li>
 * </ol>
 *
 * <p>No LLM, no network, no per-feature branching. Typical cost: microseconds.
 */
@Component
public class SignalDetector {

    private static final Pattern TOKEN = Pattern.compile("[^\\p{Alnum}$€£¥₹₩₫฿₱.,]+");
    // 10000 / 10,000 / 10.5 / 1k / 2.5m — a bare year like "2024" also counts
    // as a number, which is fine: it is still extractable content.
    private static final Pattern NUMBER = Pattern.compile(".*\\d.*");
    private static final Pattern PURE_NUMBER =
            Pattern.compile("^[\\d][\\d.,]*([kmb])?$", Pattern.CASE_INSENSITIVE);

    private final SearchProperties properties;
    private final Set<String> currencyCodes;
    private final Set<String> currencySymbols;
    private final Set<String> dateWords;
    private final Set<String> stopWords;

    public SignalDetector(SearchProperties properties) {
        this.properties = properties;
        SearchProperties.Signal cfg = properties.getSignal();
        this.currencyCodes = cfg.currencyCodeSet();
        this.currencySymbols = cfg.currencySymbolSet();
        this.dateWords = cfg.dateWordSet();
        this.stopWords = cfg.stopWordSet();
    }

    public SignalReport detect(String rawQuery, Feature feature) {
        if (feature == null || !feature.acceptsParameters()) {
            return SignalReport.noParams();
        }

        SearchProperties.Signal cfg = properties.getSignal();
        String normalised = rawQuery.toLowerCase(Locale.ROOT).trim();
        List<String> tokens = tokenise(normalised);
        Set<String> vocabulary = feature.vocabulary();

        List<String> signals = new ArrayList<>();
        Set<String> extraTokens = new LinkedHashSet<>();
        int score = 0;

        boolean sawNumber = false;
        boolean sawCurrency = false;
        boolean sawDate = false;

        for (String token : tokens) {
            String bare = stripPunctuation(token);
            if (bare.isEmpty()) {
                continue;
            }

            if (!sawNumber && (PURE_NUMBER.matcher(bare).matches() || hasEmbeddedDigits(bare))) {
                sawNumber = true;
                score += cfg.getWeightNumber();
                signals.add("number:" + bare);
            }
            if (!sawCurrency && isCurrency(bare, token)) {
                sawCurrency = true;
                score += cfg.getWeightCurrency();
                signals.add("currency:" + bare);
            }
            if (!sawDate && dateWords.contains(bare)) {
                sawDate = true;
                score += cfg.getWeightDate();
                signals.add("date:" + bare);
            }

            // "Extra" == the user typed something that is neither filler nor
            // part of the feature's own name/keywords/aliases. That residue is
            // exactly what Stage 2 has to interpret.
            boolean filler = stopWords.contains(bare) || vocabulary.contains(bare);
            boolean alreadyCounted = PURE_NUMBER.matcher(bare).matches()
                    || currencyCodes.contains(bare)
                    || dateWords.contains(bare);
            // A token that is still growing into one of the feature's own words
            // is the user mid-keystroke ("tran" -> "transfer"), not a parameter.
            boolean stillTyping = vocabulary.stream()
                    .anyMatch(word -> word.length() > bare.length() && word.startsWith(bare));
            if (!filler && !stillTyping && !alreadyCounted && bare.length() > 1) {
                extraTokens.add(bare);
            }
        }

        // Residue is only weak evidence when every slot wants a number, a date
        // or a currency — those score on their own. When a slot can hold a bare
        // word (a payee, an account nickname, a biller), that word *is* the
        // parameter, and how many words the name happens to have says nothing
        // about whether the query carries one.
        int extraWeight = feature.acceptsFreeText()
                ? cfg.getWeightExtraTokenFreeText()
                : cfg.getWeightExtraToken();
        int extraScore = Math.min(
                extraTokens.size() * extraWeight, cfg.getMaxExtraTokenScore());
        if (extraScore > 0) {
            score += extraScore;
            signals.add("extra_tokens:" + String.join(",", extraTokens));
        }

        boolean shouldExtract = score >= cfg.getThreshold();
        String reason = shouldExtract
                ? "signal score %d >= threshold %d".formatted(score, cfg.getThreshold())
                : "signal score %d < threshold %d".formatted(score, cfg.getThreshold());

        return new SignalReport(shouldExtract, score, List.copyOf(signals),
                List.copyOf(extraTokens), reason);
    }

    private boolean isCurrency(String bare, String original) {
        if (currencyCodes.contains(bare)) {
            return true;
        }
        for (String symbol : currencySymbols) {
            if (original.contains(symbol)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEmbeddedDigits(String token) {
        // "10000usd", "$10,000", "rp50000"
        return NUMBER.matcher(token).matches() && token.chars().anyMatch(Character::isDigit);
    }

    private static String stripPunctuation(String token) {
        return token.replaceAll("^[^\\p{Alnum}]+|[^\\p{Alnum}]+$", "")
                // "mom's" -> "mom"
                .replaceAll("'s$", "");
    }

    static List<String> tokenise(String normalised) {
        List<String> tokens = new ArrayList<>();
        for (String raw : TOKEN.split(normalised)) {
            if (!raw.isBlank()) {
                tokens.add(raw);
            }
        }
        return tokens;
    }
}
