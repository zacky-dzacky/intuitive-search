package com.bank.intuitivesearch.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/search} body.
 *
 * @param query   what the user typed
 * @param userId  whose payees/accounts Stage 3 resolves against. In a real
 *                deployment this comes from the authenticated principal, never
 *                from the body — it is accepted here only for the demo.
 * @param allowExtraction set false for search-as-you-type so the LLM stage can
 *                never fire on a keystroke; the client sets it true on
 *                debounce/enter.
 */
public record SearchRequest(
        @NotBlank @Size(max = 512) String query,
        String userId,
        Boolean allowExtraction) {

    public boolean extractionAllowed() {
        return allowExtraction == null || allowExtraction;
    }

    public String effectiveUserId() {
        return (userId == null || userId.isBlank()) ? "user_1" : userId;
    }
}
