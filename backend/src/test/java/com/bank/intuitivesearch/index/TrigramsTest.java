package com.bank.intuitivesearch.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrigramsTest {

    @Test
    @DisplayName("words are padded like pg_trgm so boundaries carry signal")
    void padsWords() {
        assertThat(Trigrams.of("trf")).containsExactly("  t", " tr", "trf", "rf ");
    }

    @Test
    @DisplayName("punctuation splits words and is not part of any trigram")
    void ignoresPunctuation() {
        assertThat(Trigrams.of("E-Statement")).containsExactly(
                "  e", " e ",
                "  s", " st", "sta", "tat", "ate", "tem", "eme", "men", "ent", "nt ");
    }

    @Test
    @DisplayName("an abbreviation's trigrams sit inside the alias list that contains it")
    void abbreviationIsSubsetOfMatchText() {
        assertThat(Trigrams.of("transfer money, send, trf")).containsAll(Trigrams.of("trf"));
    }

    @Test
    void emptyAndNull() {
        assertThat(Trigrams.of("")).isEmpty();
        assertThat(Trigrams.of("  ")).isEmpty();
        assertThat(Trigrams.of(null)).isEmpty();
    }
}
