package com.bank.intuitivesearch.embedding;

import java.util.List;

/** Small vector arithmetic shared by the embedding backends and the index. */
public final class Vectors {

    private Vectors() {}

    /**
     * Brings a provider's vector to the configured size.
     *
     * <p>Hosted embedding models default to 1536 or 3072 dimensions, and
     * Lucene's stock codec stops at 1024. The models we target
     * (OpenAI {@code text-embedding-3-*}, Gemini {@code gemini-embedding-001})
     * are Matryoshka-trained: the leading dimensions carry the most
     * information by design, so a longer vector is truncated and
     * re-normalised — exactly what the provider does server-side when asked
     * for {@code dimensions}. A <em>shorter</em> vector is a misconfiguration
     * and is rejected rather than padded.
     */
    public static float[] fit(float[] vector, int dimensions) {
        if (vector.length < dimensions) {
            throw new IllegalStateException("embedding has " + vector.length
                    + " dimensions but " + dimensions + " are configured");
        }
        if (vector.length == dimensions) {
            return vector;
        }
        float[] out = new float[dimensions];
        System.arraycopy(vector, 0, out, 0, dimensions);
        return normalise(out);
    }

    /** Unit length, in place. A zero vector is left alone. */
    public static float[] normalise(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum == 0) {
            return vector;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inv;
        }
        return vector;
    }

    public static float[] toFloatArray(List<? extends Number> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }
}
