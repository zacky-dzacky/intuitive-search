-- =====================================================================
-- Intuitive Search — schema
-- PostgreSQL 16 + pgvector + pg_trgm
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------
-- `array_to_string` is declared STABLE (it depends on type output
-- functions), so PostgreSQL refuses it inside a GENERATED column. The
-- text[] -> text case genuinely is immutable, so wrap it. This is the
-- standard workaround, not a correctness compromise.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION immutable_array_to_string(arr TEXT[], sep TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $$ SELECT array_to_string(arr, sep) $$;

-- ---------------------------------------------------------------------
-- Feature registry.
--
-- This table IS the configuration. Adding feature #51 = INSERT one row.
-- No application code changes are required, because:
--   * `slots`     drives the Stage-2 LLM extraction schema
--   * `has_params` + `slots` drive Stage-1 signal detection
--   * `slots[].resolver` drives Stage-3 entity resolution
--   * `route`     drives what the frontend opens / pre-fills
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS features (
    feature_id      TEXT PRIMARY KEY,
    display_name    TEXT        NOT NULL,
    description     TEXT        NOT NULL DEFAULT '',
    category        TEXT        NOT NULL DEFAULT 'general',
    route           TEXT        NOT NULL,
    -- natural-language keywords, used for full-text search
    keywords        TEXT[]      NOT NULL DEFAULT '{}',
    -- abbreviations / shorthand users actually type ("trf", "e-stmt", "cc")
    aliases         TEXT[]      NOT NULL DEFAULT '{}',
    -- 384 dims == bge-small-en-v1.5 / all-MiniLM-L6-v2
    embedding       VECTOR(384),
    has_params      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- [{"name","type","description","required","enum","resolver"}]
    slots           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Full-text document. GENERATED so it can never drift from the source
    -- columns and so inserting a feature needs no trigger/app support.
    search_document TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(display_name, '') || ' ' ||
            coalesce(immutable_array_to_string(keywords, ' '), '') || ' ' ||
            coalesce(immutable_array_to_string(aliases, ' '), '') || ' ' ||
            coalesce(description, '')
        )
    ) STORED,

    -- Lowercased blob for trigram / fuzzy + abbreviation matching.
    match_text TEXT GENERATED ALWAYS AS (
        lower(
            coalesce(display_name, '') || ' ' ||
            coalesce(immutable_array_to_string(keywords, ' '), '') || ' ' ||
            coalesce(immutable_array_to_string(aliases, ' '), '')
        )
    ) STORED,

    CONSTRAINT features_slots_is_array CHECK (jsonb_typeof(slots) = 'array'),
    CONSTRAINT features_params_have_slots
        CHECK (has_params = FALSE OR jsonb_array_length(slots) > 0)
);

CREATE INDEX IF NOT EXISTS features_fts_idx
    ON features USING gin (search_document);

CREATE INDEX IF NOT EXISTS features_trgm_idx
    ON features USING gin (match_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS features_keywords_idx
    ON features USING gin (keywords);

CREATE INDEX IF NOT EXISTS features_aliases_idx
    ON features USING gin (aliases);

-- ivfflat needs data to build meaningful lists; it is created in
-- 03_indexes.sql, after the seed + embedding backfill has run.

-- ---------------------------------------------------------------------
-- Minimal customer-data tables used by Stage 3 (entity resolution).
-- In a real deployment these already exist; the resolver only reads them.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounts (
    account_id     TEXT PRIMARY KEY,
    user_id        TEXT NOT NULL,
    label          TEXT NOT NULL,
    account_number TEXT NOT NULL,
    account_type   TEXT NOT NULL DEFAULT 'savings',
    currency       CHAR(3) NOT NULL DEFAULT 'USD',
    balance_minor  BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS accounts_user_idx ON accounts (user_id);
CREATE INDEX IF NOT EXISTS accounts_label_trgm_idx
    ON accounts USING gin (lower(label) gin_trgm_ops);

CREATE TABLE IF NOT EXISTS payees (
    payee_id       TEXT PRIMARY KEY,
    user_id        TEXT NOT NULL,
    nickname       TEXT,
    full_name      TEXT NOT NULL,
    account_number TEXT NOT NULL,
    bank_code      TEXT NOT NULL DEFAULT 'LOCAL',
    bank_name      TEXT NOT NULL DEFAULT 'Local Bank',
    currency       CHAR(3) NOT NULL DEFAULT 'USD',
    last_used_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS payees_user_idx ON payees (user_id);
CREATE INDEX IF NOT EXISTS payees_nickname_trgm_idx
    ON payees USING gin (lower(coalesce(nickname, '')) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS payees_fullname_trgm_idx
    ON payees USING gin (lower(full_name) gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Optional: audit trail of what search proposed. NEVER an execution log —
-- nothing in this system executes a transaction.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS search_audit (
    id             BIGSERIAL PRIMARY KEY,
    user_id        TEXT,
    query          TEXT        NOT NULL,
    matched_feature TEXT,
    confidence     REAL,
    action         TEXT        NOT NULL,
    llm_invoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    latency_ms     INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS search_audit_created_idx ON search_audit (created_at DESC);

-- ---------------------------------------------------------------------
-- Grant the application user access. The schema is created by a
-- superuser; the app connects as 'bank' which needs explicit permission.
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE
    ON features, accounts, payees, search_audit TO bank;
GRANT USAGE, SELECT ON SEQUENCE search_audit_id_seq TO bank;
GRANT EXECUTE ON FUNCTION immutable_array_to_string(TEXT[], TEXT) TO bank;
