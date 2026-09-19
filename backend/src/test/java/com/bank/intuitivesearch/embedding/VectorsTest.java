package com.bank.intuitivesearch.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorsTest {

    @Test
    @DisplayName("a longer vector is truncated to the configured size and re-normalised")
    void truncatesAndRenormalises() {
        float[] fitted = Vectors.fit(new float[] {3, 4, 100, 100}, 2);
        assertThat(fitted).hasSize(2);
        assertThat(fitted[0]).isCloseTo(0.6f, Offset.offset(1e-6f));
        assertThat(fitted[1]).isCloseTo(0.8f, Offset.offset(1e-6f));
    }

    @Test
    @DisplayName("an exact-size vector is passed through untouched")
    void passesThrough() {
        float[] in = {3, 4};
        assertThat(Vectors.fit(in, 2)).isSameAs(in);
    }

    @Test
    @DisplayName("a shorter vector is a misconfiguration, not something to pad")
    void rejectsShort() {
        assertThatThrownBy(() -> Vectors.fit(new float[] {1, 2}, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 dimensions")
                .hasMessageContaining("3 are configured");
    }

    @Test
    void zeroVectorStaysZero() {
        assertThat(Vectors.normalise(new float[] {0, 0})).containsExactly(0, 0);
    }
}
