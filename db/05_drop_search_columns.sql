-- =====================================================================
-- Intuitive Search — migration: search moves out of Postgres
--
-- Run once against a database created by the previous 01_schema.sql /
-- 03_indexes.sql / 04_admin.sql. Idempotent.
--
--   psql -U bank -d banksearch -f db/05_drop_search_columns.sql
--
-- The backend now builds its own Lucene index from the plain `features`
-- columns, so everything that existed only to serve retrieval goes:
--   * the pgvector column, its HNSW index and the extension itself
--   * the embedding provenance columns the admin dashboard tracked
--   * the GENERATED tsvector / trigram columns and their GIN indexes
-- pg_trgm stays — Stage 3 still uses it on `payees` and `accounts`.
--
-- Nothing here is data anyone entered: every dropped column was derived
-- from the ones that remain.
-- =====================================================================

DROP INDEX IF EXISTS features_embedding_hnsw_idx;
DROP INDEX IF EXISTS features_embedding_ivfflat_idx;
DROP INDEX IF EXISTS features_fts_idx;
DROP INDEX IF EXISTS features_trgm_idx;
DROP INDEX IF EXISTS features_keywords_idx;
DROP INDEX IF EXISTS features_aliases_idx;

ALTER TABLE features
    DROP COLUMN IF EXISTS embedding,
    DROP COLUMN IF EXISTS embedding_source_hash,
    DROP COLUMN IF EXISTS embedded_at,
    DROP COLUMN IF EXISTS search_document,
    DROP COLUMN IF EXISTS match_text;

DROP FUNCTION IF EXISTS immutable_array_to_string(TEXT[], TEXT);

-- Requires superuser (the same privilege CREATE EXTENSION needed). If this
-- role cannot drop it, the extension is merely unused; drop it later.
DROP EXTENSION IF EXISTS vector;
