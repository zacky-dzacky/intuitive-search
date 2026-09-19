-- =====================================================================
-- Vector index. Run this AFTER embeddings have been backfilled:
--
--   docker compose exec -T postgres psql -U bank -d banksearch < db/03_indexes.sql
--
-- HNSW rather than ivfflat, deliberately:
--   * ivfflat partitions the vectors into `lists` centroids and, at the
--     default probes = 1, searches only ONE of them. On a registry this
--     small that silently drops the correct feature out of the candidate
--     set — measured here as a feature scoring 0.0 on the vector channel
--     while ranking top on every lexical one.
--   * HNSW needs no training data, has far better recall out of the box,
--     and at ~10^2 rows the build cost is irrelevant.
-- Revisit only if the registry grows by orders of magnitude.
-- =====================================================================

DROP INDEX IF EXISTS features_embedding_ivfflat_idx;

CREATE INDEX IF NOT EXISTS features_embedding_hnsw_idx
    ON features USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

ANALYZE features;

-- Raise recall further at query time if needed (default 40):
--   SET hnsw.ef_search = 100;
