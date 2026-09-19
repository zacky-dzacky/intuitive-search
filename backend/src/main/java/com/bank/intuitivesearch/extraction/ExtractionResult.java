package com.bank.intuitivesearch.extraction;

import java.util.List;
import java.util.Map;

/**
 * Raw output of Stage 2, before entity resolution.
 *
 * @param slots    slot name -&gt; extracted value ({@code String}, {@code Number}
 *                 or {@code Boolean}). Slots the model could not fill are
 *                 simply absent — never guessed.
 * @param provider which extractor produced this, for diagnostics
 * @param warnings recoverable problems (unknown slot dropped, value coerced,
 *                 malformed JSON repaired) — surfaced, never thrown
 */
public record ExtractionResult(
        boolean succeeded,
        Map<String, Object> slots,
        String provider,
        List<String> warnings) {

    public static ExtractionResult empty(String provider, String warning) {
        return new ExtractionResult(false, Map.of(), provider, List.of(warning));
    }

    public boolean hasSlots() {
        return succeeded && !slots.isEmpty();
    }
}
