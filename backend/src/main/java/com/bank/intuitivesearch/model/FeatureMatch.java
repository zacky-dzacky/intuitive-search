package com.bank.intuitivesearch.model;

/**
 * A Stage-1 candidate with its per-channel and merged scores. The breakdown is
 * kept so relevance regressions can be diagnosed from a response payload
 * instead of a debugger.
 *
 * @param keywordScore     normalised {@code ts_rank_cd} (0..1), 0 if no lexical hit
 * @param trigramScore     pg_trgm word/whole similarity (0..1)
 * @param vectorScore      cosine similarity from pgvector (0..1)
 * @param containmentScore whether the query literally contains one of the
 *                         feature's naming terms (0..1)
 * @param confidence       weighted merge of whichever channels were available
 */
public record FeatureMatch(
        Feature feature,
        double keywordScore,
        double trigramScore,
        double vectorScore,
        double containmentScore,
        double confidence,
        boolean exactMatch) {

    public String featureId() {
        return feature.featureId();
    }
}
