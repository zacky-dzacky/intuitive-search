package com.bank.intuitivesearch.index;

import java.time.Instant;

/**
 * What the current index generation looks like. Served on
 * {@code GET /api/admin/index} and used by the admin dashboard.
 *
 * @param features         documents in the index
 * @param withVector       documents that carry an embedding
 * @param dimensions       configured vector size
 * @param embeddingModel   provider + model the vectors came from
 * @param builtAt          when this generation was swapped in
 * @param buildMs          wall time of that build, embedding calls included
 * @param embeddedInBuild  documents (re-)embedded during that build
 * @param lastError        the most recent embedding failure, if the last
 *                         build could not vectorise everything
 */
public record IndexStats(
        int features,
        int withVector,
        int dimensions,
        String embeddingModel,
        Instant builtAt,
        long buildMs,
        int embeddedInBuild,
        String lastError) {

    static IndexStats empty(int dimensions, String embeddingModel) {
        return new IndexStats(0, 0, dimensions, embeddingModel, null, 0, 0, null);
    }
}
