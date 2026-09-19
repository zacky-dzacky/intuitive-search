# Database Documentation — Intuitive Search

## Overview

- **Engine**: PostgreSQL 16
- **Extensions**: `pgvector` (vector similarity search), `pg_trgm` (fuzzy/trigram search)
- **Database name**: `banksearch`
- **Default credentials**: user `bank`, password `bank`

The database has two roles: the **search pipeline** reads `features`, `accounts`, and `payees`; the **admin dashboard** also writes to `features` and reads `admin_audit` and `search_audit`.

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
| `db/01_schema.sql` | Once, on first setup | Creates extensions, the `immutable_array_to_string` helper, and all tables + indexes (except the vector index) |
| `db/02_seed.sql` | Once, after schema | Inserts 67 features, 3 demo accounts, 6 demo payees |
| `db/03_indexes.sql` | After embeddings are backfilled | Drops the legacy `ivfflat` index (if any), builds the `HNSW` vector index, runs `ANALYZE` |
| `admin-dashboard/db/04_admin.sql` | Once, before using the admin dashboard | Adds `embedding_source_hash` / `embedded_at` columns to `features`, creates `admin_audit`, adds analytics indexes on `search_audit` |

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

**Vector index (run after embedding backfill):**
```bash
$PSQL -d banksearch -f db/03_indexes.sql
```

**Admin dashboard migration (run once):**
```bash
$PSQL -d banksearch -f admin-dashboard/db/04_admin.sql
```

**Adding a new migration file** — name it `05_whatever.sql`, use `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` throughout, and apply it manually. The ordering is `01 → 02 → 03 → 04 → 05 …`.

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
| `embedding` | `VECTOR(384)` | 384-dimensional bge-small-en-v1.5 vector; NULL until backfilled |
| `has_params` | `BOOLEAN` | `TRUE` = feature accepts slot parameters and can trigger LLM extraction |
| `slots` | `JSONB` | Array of slot definitions; each has `name`, `type`, `required`, `resolver`, optional `enum` |
| `enabled` | `BOOLEAN` | Soft-disable without deleting |
| `updated_at` | `TIMESTAMPTZ` | Set on every write |
| `search_document` | `TSVECTOR GENERATED` | Auto-built from `display_name + keywords + aliases + description`; used by full-text search |
| `match_text` | `TEXT GENERATED` | Lowercased `display_name + keywords + aliases`; used by trigram matching |
| `embedding_source_hash` | `TEXT` | MD5 of the document that produced the vector (added by `04_admin.sql`); used to detect stale embeddings |
| `embedded_at` | `TIMESTAMPTZ` | When the vector was last written (added by `04_admin.sql`) |

**Constraints:**
- `slots` must be a JSON array
- If `has_params = TRUE` then `slots` must be non-empty

**Indexes:**

| Index | Type | Purpose |
|-------|------|---------|
| `features_fts_idx` | GIN on `search_document` | Full-text search (prefix queries) |
| `features_trgm_idx` | GIN on `match_text` (trgm) | Trigram / fuzzy / abbreviation matching |
| `features_keywords_idx` | GIN on `keywords` | Array containment lookups |
| `features_aliases_idx` | GIN on `aliases` | Array containment lookups |
| `features_embedding_hnsw_idx` | HNSW on `embedding` (cosine) | Vector similarity search; built by `03_indexes.sql` after seed |

**Why HNSW instead of ivfflat** — `ivfflat` with `lists = 8` and `probes = 1` silently dropped the correct feature from the candidate set on the 67-row registry (measured: cosine 0.0 while topping every lexical channel). HNSW requires no training data and has far better default recall; build cost is irrelevant at this size.

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

## Custom objects

### `immutable_array_to_string(arr TEXT[], sep TEXT) → TEXT`

A wrapper around `array_to_string` declared `IMMUTABLE` so it can be used in `GENERATED` columns. PostgreSQL refuses `array_to_string` there because it is only `STABLE`; the `text[] → text` case is genuinely immutable, making this the standard safe workaround.

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

Then backfill the embedding so the vector channel can find it:

```bash
cd embedding-service && python precompute_embeddings.py
```

The backend's in-memory registry cache refreshes within 60 seconds (`search.registry-refresh-ms`). No restart required.

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

**Check which features are missing embeddings:**
```sql
SELECT feature_id, display_name
FROM features
WHERE embedding IS NULL AND enabled = TRUE;
```

**Check for stale embeddings (requires `04_admin.sql` to have been applied):**
```sql
SELECT feature_id, display_name, embedded_at
FROM features
WHERE embedding_source_hash IS DISTINCT FROM
      md5(btrim(concat_ws('. ',
          nullif(display_name, ''),
          nullif(array_to_string(keywords, ', '), ''),
          nullif(array_to_string(aliases, ', '), ''),
          nullif(description, ''))))
  AND embedding IS NOT NULL;
```

**Rebuild the vector index after a large batch of new embeddings:**
```bash
/opt/homebrew/opt/postgresql@16/bin/psql -d banksearch -f db/03_indexes.sql
```

**Start/stop the local Postgres service:**
```bash
brew services start postgresql@16
brew services stop postgresql@16
```

**Postgres data directory:** `/opt/homebrew/var/postgresql@16`
