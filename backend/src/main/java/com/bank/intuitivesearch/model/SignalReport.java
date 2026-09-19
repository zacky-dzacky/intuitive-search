package com.bank.intuitivesearch.model;

import java.util.List;

/**
 * Output of the generic signal detector. No feature-specific logic produced
 * any of this — it is derived from the query text plus the matched feature's
 * declared schema and vocabulary.
 *
 * @param score        weighted total; compared against the configured threshold
 * @param signals      human-readable reasons, surfaced in diagnostics
 * @param extraTokens  query tokens that are neither stop-words nor part of the
 *                     feature's own vocabulary — the likely parameter payload
 * @param reason       why extraction will or won't be attempted
 */
public record SignalReport(
        boolean shouldExtract,
        int score,
        List<String> signals,
        List<String> extraTokens,
        String reason) {

    public static SignalReport noParams() {
        return new SignalReport(false, 0, List.of(), List.of(),
                "feature declares no parameters");
    }

    public static SignalReport suppressed(String reason) {
        return new SignalReport(false, 0, List.of(), List.of(), reason);
    }
}
