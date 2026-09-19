-- =====================================================================
-- Intuitive Search — schema
-- PostgreSQL 16 + pg_trgm
--
-- Postgres is the system of record for the feature catalogue, the demo
-- customer data and the audit log. It is NOT the search index: the backend
-- builds that in process (Apache Lucene, see backend/.../index/) from the
-- `features` rows on every refresh. So there is no vector column, no
-- tsvector, no pgvector — nothing here that cannot be rebuilt from the
-- plain columns in milliseconds.
--
-- pg_trgm remains for Stage 3 only: fuzzy matching a spoken payee or
-- account name against the customer's own rows.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

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
    has_params      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- [{"name","type","description","required","enum","resolver"}]
    slots           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT features_slots_is_array CHECK (jsonb_typeof(slots) = 'array'),
    CONSTRAINT features_params_have_slots
        CHECK (has_params = FALSE OR jsonb_array_length(slots) > 0)
);

-- The registry is read whole by the backend (one SELECT per refresh) and
-- edited row-by-row by the admin dashboard; the primary key is the only
-- index that earns its keep.

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
