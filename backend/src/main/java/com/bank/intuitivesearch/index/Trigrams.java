package com.bank.intuitivesearch.index;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * pg_trgm's trigram extraction, in Java.
 *
 * <p>Each alphanumeric word is padded with two spaces in front and one behind
 * before being cut into 3-character windows, so word boundaries carry
 * signal: {@code "trf"} yields {@code "  t", " tr", "trf", "rf "}. This is
 * what makes an abbreviation typed on its own line up with the alias in the
 * registry — the padded trigrams of the query are a subset of the padded
 * trigrams of the alias, however much other text surrounds it.
 */
final class Trigrams {

    private Trigrams() {}

    static Set<String> of(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                out.add(padded.substring(i, i + 3));
            }
        }
        return out;
    }
}
