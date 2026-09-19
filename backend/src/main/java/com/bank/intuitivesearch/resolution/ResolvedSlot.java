package com.bank.intuitivesearch.resolution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A slot value after Stage 3.
 *
 * <p>Scalars (amount, currency) serialise as bare JSON values. Anything that
 * was resolved against customer data serialises as an object carrying both the
 * raw text and the resolution, so the form can show the user exactly what was
 * understood — and so an ambiguous match can offer a choice instead of
 * silently picking one.
 */
public record ResolvedSlot(
        String name,
        Object rawValue,
        String resolvedId,
        String resolvedName,
        String resolvedDetail,
        double matchScore,
        boolean ambiguous,
        List<Candidate> candidates) {

    public record Candidate(String id, String name, String detail, double score) {}

    public static ResolvedSlot scalar(String name, Object value) {
        return new ResolvedSlot(name, value, null, null, null, 0, false, List.of());
    }

    public static ResolvedSlot unresolved(String name, Object rawValue) {
        return new ResolvedSlot(name, rawValue, null, null, null, 0, false, List.of());
    }

    public boolean isResolved() {
        return resolvedId != null;
    }

    /**
     * Shape used in the API response. A plain scalar stays a scalar so the
     * published contract holds:
     * {@code "amount": 10000, "recipient": { "raw": ..., ... }}.
     */
    public Object toJsonValue() {
        if (!isResolved() && candidates.isEmpty()) {
            return rawValue;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw", rawValue);
        if (resolvedId != null) {
            out.put("resolved_account_id", resolvedId);
        }
        if (resolvedName != null) {
            out.put("resolved_name", resolvedName);
        }
        if (resolvedDetail != null) {
            out.put("resolved_detail", resolvedDetail);
        }
        if (matchScore > 0) {
            out.put("match_score", round(matchScore));
        }
        if (ambiguous) {
            out.put("ambiguous", true);
        }
        if (!candidates.isEmpty()) {
            out.put("candidates", candidates.stream()
                    .map(c -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("id", c.id());
                        entry.put("name", c.name());
                        entry.put("detail", c.detail());
                        entry.put("score", round(c.score()));
                        return entry;
                    })
                    .toList());
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
