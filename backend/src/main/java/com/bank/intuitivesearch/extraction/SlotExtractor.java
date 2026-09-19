package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.model.Feature;

/**
 * Stage 2 strategy. Implementations differ only in <em>where</em> the model
 * runs — the prompt, the schema and the validation are shared.
 *
 * <p>Implementations must never throw: a failed extraction degrades to
 * navigation, it does not fail the request.
 */
public interface SlotExtractor {

    /**
     * @param feature the Stage-1 match, whose {@code slots} define the schema
     * @param query   the user's original text
     */
    ExtractionResult extract(Feature feature, String query);

    /** Identifier used in diagnostics. */
    String providerName();
}
