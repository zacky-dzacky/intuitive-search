-- =====================================================================
-- Intuitive Search — admin dashboard migration
--
-- Additive and idempotent: run it against an existing banksearch database
-- any number of times. It changes nothing the search pipeline reads, so the
-- backend needs no restart and no rebuild.
--
--   psql -U bank -d banksearch -f admin-dashboard/db/04_admin.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- Change log for the dashboard's own writes.
--
-- The registry is configuration that takes effect within 60 seconds, with no
-- deploy and therefore no deploy log. This is the substitute: every create,
-- update and delete the dashboard performs, with the before/after values.
-- Never a transaction log — this dashboard, like the search API, executes
-- nothing.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_audit (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       TEXT        NOT NULL DEFAULT 'admin',
    action      TEXT        NOT NULL,          -- create | update | delete | reembed
    resource    TEXT        NOT NULL,          -- registry resource name
    record_id   TEXT,
    changes     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT admin_audit_action_check
        CHECK (action IN ('create', 'update', 'delete', 'reembed'))
);

CREATE INDEX IF NOT EXISTS admin_audit_at_idx ON admin_audit (at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_record_idx ON admin_audit (resource, record_id);

-- ---------------------------------------------------------------------
-- Analytics support. The audit table is written on every search; these make
-- the dashboard's aggregate queries cheap enough to run on page load.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS search_audit_feature_idx
    ON search_audit (matched_feature, created_at DESC);
CREATE INDEX IF NOT EXISTS search_audit_action_idx
    ON search_audit (action, created_at DESC);
