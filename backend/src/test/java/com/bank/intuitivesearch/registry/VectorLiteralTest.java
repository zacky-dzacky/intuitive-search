package com.bank.intuitivesearch.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorLiteralTest {

    @Test
    @DisplayName("embeddings serialise to a pgvector literal")
    void buildsVectorLiteral() {
        assertThat(FeatureRepository.toVectorLiteral(new float[] {0.5f, -0.25f}))
                .isEqualTo("[0.5,-0.25]");
        assertThat(FeatureRepository.toVectorLiteral(new float[] {})).isEqualTo("[]");
    }
}
