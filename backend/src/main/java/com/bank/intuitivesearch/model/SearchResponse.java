package com.bank.intuitivesearch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/search} response. Serialised snake_case (see
 * {@code spring.jackson.property-naming-strategy}), so this matches the
 * published contract:
 *
 * <pre>
 * {
 *   "matched_feature": "transfer",
 *   "confidence": 0.92,
 *   "params_extracted": true,
 *   "slots": { "recipient": {"raw":"mom","resolved_account_id":"acc_123", ...},
 *              "amount": 10000, "currency": "USD" },
 *   "action": "prefill_form"
 * }
 * </pre>
 *
 * <p><b>{@code action} is never "execute".</b> The only values are
 * {@code navigate}, {@code prefill_form}, {@code suggest} and {@code none} —
 * every one of them hands control back to the user.
 */
// The published contract's keys are always present, even when null — a client
// parsing this should never have to distinguish "absent" from "no match".
// (The service default is non-null inclusion; this opts out for the contract.)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SearchResponse(
        String matchedFeature,
        String displayName,
        String route,
        double confidence,
        boolean paramsExtracted,
        Map<String, Object> slots,
        String action,
        List<Suggestion> suggestions,
        Diagnostics diagnostics) {

    public static final String ACTION_NAVIGATE = "navigate";
    public static final String ACTION_PREFILL = "prefill_form";
    public static final String ACTION_SUGGEST = "suggest";
    public static final String ACTION_NONE = "none";

    public record Suggestion(
            String featureId,
            String displayName,
            String route,
            String category,
            double confidence) {}

    /**
     * Observability payload: which stages ran, how long each took, and why the
     * LLM did or did not fire. Cheap to compute and invaluable when tuning the
     * &lt;100ms / &lt;400ms budgets.
     */
    public record Diagnostics(
            Map<String, Long> timingsMs,
            long totalMs,
            boolean llmInvoked,
            String llmProvider,
            int signalScore,
            List<String> signals,
            String stoppedAt,
            List<String> warnings,
            Map<String, Double> scoreBreakdown) {}
}
