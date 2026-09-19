# Database Documentation — Intuitive Search

## Overview

- **Engine**: PostgreSQL 16
- **Extensions**: `pg_trgm` (fuzzy matching of payee / account names in Stage 3). Nothing else — no `pgvector`.
- **Database name**: `banksearch`
- **Default credentials**: user `bank`, password `bank`

The database has two roles: the **search pipeline** reads `features` (whole, on a 60 s refresh), `accounts`, and `payees`; the **admin dashboard** also writes to `features` and reads `admin_audit` and `search_audit`.

**Postgres is the system of record, not the search index.** The backend builds its own index in process (Apache Lucene — full-text, trigram and vector channels) from the plain `features` columns every time the registry changes. Vectors are obtained from the configured embedding provider at build time and live only in that index. So there is no vector column, no `tsvector`, no HNSW index and no backfill step in this database; everything retrieval needs is derived from the columns below in milliseconds. See `docs/adr/002-lucene-replaces-pgvector.md`.

---

## Connection

### Local (host Postgres, as used in development)

| Parameter | Value |
|-----------|-------|
| Host | `localhost` |
| Port | `5432` |
| Database | `banksearch` |
| User | `bank` |
| Password | `bank` |

**psql**
```bash
/opt/homebrew/opt/postgresql@16/bin/psql -U bank -d banksearch
```

**JDBC (backend)**
```
jdbc:postgresql://localhost:5432/banksearch
```

**libpq / Node / Python**
```
postgresql://bank:bank@localhost:5432/banksearch
```

### From inside a Docker container

The containers use `host.docker.internal` to reach the host's Postgres:

```
jdbc:postgresql://host.docker.internal:5432/banksearch   # backend
postgresql://bank:bank@host.docker.internal:5432/banksearch  # admin / Node
```

In Kubernetes on OrbStack, `host.docker.internal` resolves to the host machine automatically — no extra configuration needed.

### DBeaver / GUI client

| Field | Value |
|-------|-------|
| Host | `localhost` |
| Port | `5432` |
| Database | `banksearch` |
| Authentication | Database Native |
| Username | `bank` |
| Password | `bank` |

---

## Schema versioning

Migrations are plain SQL files in `db/`, applied in name order. There is no migration framework — re-running a file is safe because every statement uses `IF NOT EXISTS` or `ON CONFLICT DO NOTHING`.

| File | When to run | What it does |
|------|-------------|--------------|
| `db/01_schema.sql` | Once, on first setup | Creates `pg_trgm` and all tables + indexes |
| `db/02_seed.sql` | Once, after schema | Inserts 67 features, 3 demo accounts, 6 demo payees |
| `admin-dashboard/db/04_admin.sql` | Once, before using the admin dashboard | Creates `admin_audit`, adds analytics indexes on `search_audit` |
| `db/05_drop_search_columns.sql` | Once, on a database created **before** search moved to Lucene | Drops the `embedding` / provenance / generated search columns, their indexes, the helper function and the `vector` extension |

(`03_indexes.sql` — the pgvector HNSW index — no longer exists.)

### Applying migrations

**First-time setup (local Postgres):**
```bash
PSQL=/opt/homebrew/opt/postgresql@16/bin/psql

# Create user and database (run as your macOS user, not as bank)
$PSQL postgres -c "CREATE USER bank WITH PASSWORD 'bank';"
$PSQL postgres -c "CREATE DATABASE banksearch OWNER bank;"

# Schema and seed must run as superuser because CREATE EXTENSION requires it
$PSQL -d banksearch -f db/01_schema.sql
$PSQL -d banksearch -f db/02_seed.sql
```

**Admin dashboard migration (run once):**
```bash
$PSQL -d banksearch -f admin-dashboard/db/04_admin.sql
```

**Upgrading an existing database (created before the Lucene change):**
```bash
$PSQL -d banksearch -f db/05_drop_search_columns.sql
```

**Adding a new migration file** — name it `06_whatever.sql`, use `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` throughout, and apply it manually. The ordering is `01 → 02 → 04 → 05 → 06 …`.

---

## Tables

### `features`

The feature registry. **This table is the configuration.** Adding a new banking feature is one INSERT — no code changes required.

| Column | Type | Notes |
|--------|------|-------|
| `feature_id` | `TEXT PK` | Slug, e.g. `transfer`, `check_balance` |
| `display_name` | `TEXT` | Human-readable name |
| `description` | `TEXT` | One-sentence description |
| `category` | `TEXT` | UI grouping: `payments`, `accounts`, `cards`, `investments`, `loans`, `insurance`, `security`, `settings`, `support`, `general` |
| `route` | `TEXT` | Frontend path the search result opens |
| `keywords` | `TEXT[]` | Natural-language terms for full-text matching |
| `aliases` | `TEXT[]` | Abbreviations users actually type (`trf`, `e-stmt`) |
| `has_params` | `BOOLEAN` | `TRUE` = feature accepts slot parameters and can trigger LLM extraction |
| `slots` | `JSONB` | Array of slot definitions; each has `name`, `type`, `required`, `resolver`, optional `enum` |
| `enabled` | `BOOLEAN` | Soft-disable without deleting |
| `updated_at` | `TIMESTAMPTZ` | Set on every write |

The backend derives everything else from these: the full-text field (`display_name + keywords + aliases + description`, stemmed), the trigram field (`display_name + keywords + aliases`, lower-cased) and the embedding text (`display_name. keywords. aliases. description`) are all built in `backend/.../index/FeatureDocument.java` at index time.

**Constraints:**
- `slots` must be a JSON array
- If `has_params = TRUE` then `slots` must be non-empty

**Indexes:** the primary key only. The table is read whole once a minute and edited a row at a time; retrieval indexes live in the backend's Lucene index.

---

### `accounts`

Demo customer accounts used by Stage 3 entity resolution. In production these already exist in the bank's core system; the resolver only reads them.

| Column | Type | Notes |
|--------|------|-------|
| `account_id` | `TEXT PK` | |
| `user_id` | `TEXT` | Scopes all resolver queries |
| `label` | `TEXT` | Display name, trigram-indexed for fuzzy match |
| `account_number` | `TEXT` | Masked in responses |
| `account_type` | `TEXT` | `savings`, `current`, `multicurrency` |
| `currency` | `CHAR(3)` | ISO-4217 |
| `balance_minor` | `BIGINT` | Balance in minor units (cents) |

**Indexes:** `accounts_user_idx` (equality), `accounts_label_trgm_idx` (fuzzy label match)

---

### `payees`

Demo saved beneficiaries. Same scope rules as `accounts`.

| Column | Type | Notes |
|--------|------|-------|
| `payee_id` | `TEXT PK` | |
| `user_id` | `TEXT` | Scopes all resolver queries |
| `nickname` | `TEXT` | Short name the user chose (`Mom`, `Landlord`) |
| `full_name` | `TEXT` | Legal name |
| `account_number` | `TEXT` | |
| `bank_code` | `TEXT` | |
| `bank_name` | `TEXT` | |
| `currency` | `CHAR(3)` | ISO-4217 |
| `last_used_at` | `TIMESTAMPTZ` | |

**Indexes:** `payees_user_idx`, `payees_nickname_trgm_idx`, `payees_fullname_trgm_idx`

---

### `search_audit`

Optional append-only log of what the search pipeline proposed. **Never an execution log** — nothing in this system executes a transaction.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGSERIAL PK` | |
| `user_id` | `TEXT` | Nullable (unauthenticated queries) |
| `query` | `TEXT` | Raw user input |
| `matched_feature` | `TEXT` | FK to `features.feature_id` |
| `confidence` | `REAL` | Hybrid score 0–1 |
| `action` | `TEXT` | `navigate`, `prefill_form`, `suggest`, `none` |
| `llm_invoked` | `BOOLEAN` | Whether Stage 2 ran |
| `latency_ms` | `INTEGER` | End-to-end pipeline latency |
| `created_at` | `TIMESTAMPTZ` | |

**Indexes:** `search_audit_created_idx` (DESC), plus `search_audit_feature_idx` and `search_audit_action_idx` added by `04_admin.sql` for the analytics dashboard.

---

### `admin_audit`

Change log written by the admin dashboard for every create/update/delete/reembed operation on the feature registry. The substitute for a deploy log — registry changes take effect within 60 s with no deploy.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGSERIAL PK` | |
| `at` | `TIMESTAMPTZ` | |
| `actor` | `TEXT` | Always `admin` in the current single-admin setup |
| `action` | `TEXT` | One of `create`, `update`, `delete`, `reembed` |
| `resource` | `TEXT` | Registry resource name |
| `record_id` | `TEXT` | `feature_id` of the affected row |
| `changes` | `JSONB` | Before/after values |

---

## Adding a feature

One INSERT, no code change, no deploy:

```sql
INSERT INTO features (
    feature_id, display_name, description, category, route,
    keywords, aliases, has_params, slots
) VALUES (
    'request_cheque_book',
    'Request Cheque Book',
    'Order a new cheque book for a current account.',
    'accounts', '/accounts/cheque-book',
    ARRAY['cheque','check book','chequebook','order cheques'],
    ARRAY['chq','cheque'],
    TRUE,
    '[
      {"name":"account","type":"string","required":false,"resolver":"account",
       "description":"Which account the cheque book is for."},
      {"name":"quantity","type":"number","required":false,"resolver":"amount",
       "description":"How many cheque books to order."}
    ]'::jsonb
);
```

Nothing else. The backend's registry refresh (60 seconds, `search.registry-refresh-ms`) notices the new row, embeds it — one call, just that row — and rebuilds the search index. No restart, no backfill script. The admin dashboard's *Rebuild index* button does the same without the wait.

**Slot `resolver` values:**

| Resolver | What Stage 3 does |
|----------|-------------------|
| `payee` | Fuzzy-matches the raw string against `payees.nickname` / `payees.full_name` scoped to `user_id` |
| `account` | Fuzzy-matches against `accounts.label` scoped to `user_id` |
| `currency` | Normalises to ISO-4217 (`"dollars"` → `"USD"`) |
| `amount` | Strips symbols and separators, returns a number |
| `date` | Parses relative expressions (`"yesterday"`, `"March"`) to ISO-8601 |
| `period` | Parses month/quarter/year references to a range |
| `phone` | Strips non-digit characters |
| `none` | Passes the raw LLM output through unchanged |

---

## Maintenance

**Check what the search index holds** — that is a backend question now, not a database one:
```bash
curl -s localhost:8080/api/admin/index | jq
```

**Start/stop the local Postgres service:**
```bash
brew services start postgresql@16
brew services stop postgresql@16
```

**Postgres data directory:** `/opt/homebrew/var/postgresql@16`
