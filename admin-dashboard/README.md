# Intuitive Search — Admin Dashboard

A web console for the feature registry behind [Intuitive Search](../README.md):
add and edit the features search can find, keep their vectors current, run
queries against the live pipeline and read the diagnostics, and see what the
search API actually did.

It is built so that **managing something new costs one config object, not a
feature branch**. Every list page, form, validation rule and REST endpoint in
here is generated at runtime from a resource description — there is no
per-table page, component, controller or SQL anywhere in the project.

That is the same property the search service has: adding feature #68 is one
INSERT, not one code path. A dashboard that needed a code path per table would
be quietly contradicting the thing it manages.

---

## What it manages

| Section | What it is for |
|---|---|
| **Overview** | Registry shape at a glance, stale-vector count, recent changes, first-run migration check |
| **Features** | Full CRUD over the registry, with a structured editor for `slots` — the parameter contract that drives Stage 2 and Stage 3 |
| **Embeddings** | Which features have a current vector, which have drifted, and one button that fixes them |
| **Search playground** | Run a query against the live API and read the whole `diagnostics` block: per-channel scores, signal score, whether the LLM fired, per-stage timings |
| **Analytics** | LLM invocation rate, latency percentiles split by path, top queries, and the unresolved queries that are really the registry's to-do list |
| **Payees / Accounts** | The demo customer data Stage 3 resolves against |
| **Search log / Change log** | Read-only views of `search_audit` and of every write this dashboard made |

---

## Quick start

The dashboard is additive: it reads the same database the search backend
reads, and changes nothing the pipeline depends on.

```bash
# 1. The search stack must be up (deploy backend + embedding to k8s first)
#    See README.md quick start steps 1–2.

# 2. One migration, idempotent, no backend restart needed
psql -U bank -d banksearch -f admin-dashboard/db/04_admin.sql

# 3. Deploy to Kubernetes
cd admin-dashboard
./deploy-local.sh     # builds image, pushes, applies k8s/, waits for rollout
```

Or run locally for development:

```bash
cd admin-dashboard
cp .env.example .env.local        # then set ADMIN_PASSWORD and SESSION_SECRET
npm install
npm run dev                       # http://localhost:3001
```

If the migration has not been applied, the Overview page says so and prints the
exact command rather than failing on the first query that needs a new column.

### Environment

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | — | libpq URL for `banksearch` (note: not the JDBC form the backend uses) |
| `BACKEND_BASE_URL` | `http://localhost:8080` | The Spring API, for the playground |
| `EMBEDDING_BASE_URL` | `http://localhost:8000` | The FastAPI service, for re-embedding |
| `ADMIN_PASSWORD` | — | The single admin password |
| `SESSION_SECRET` | — | Signs the session cookie; `openssl rand -hex 32` |
| `SESSION_TTL_HOURS` | `12` | Session lifetime |

With `NODE_ENV=production`, the app **refuses to start** on the placeholder
values from `.env.example` or on a short secret. A published default password
is not something a log warning fixes.

---

## Adding a managed resource

This is the part worth reading. Suppose a `synonyms` table arrives:

```ts
// src/lib/resources/registry.ts
const synonyms: ResourceDef = {
  name: "synonyms",
  table: "synonyms",
  primaryKey: "id",
  label: "Synonym",
  labelPlural: "Synonyms",
  icon: "🔁",
  titleField: "term",
  defaultSort: { column: "term", direction: "asc" },
  searchColumns: ["term", "expands_to"],
  listColumns: ["term", "expands_to", "enabled"],
  fields: [
    { name: "id", label: "ID", type: "number", readOnly: true, immutable: true },
    { name: "term", label: "Term", type: "text", required: true },
    { name: "expands_to", label: "Expands to", type: "tags" },
    { name: "enabled", label: "Enabled", type: "boolean", defaultValue: true, filterable: true },
  ],
};

export const RESOURCES: ResourceDef[] = [features, payees, accounts, searchAudit, adminAudit, synonyms];
```

Adding that array entry produces, with no other edit:

- a sidebar entry under **Registry**
- `/resources/synonyms` — searchable, filterable, sortable, paged
- `/resources/synonyms/new` and `/resources/synonyms/:id` with typed inputs
- `GET|POST /api/admin/synonyms` and `GET|PATCH|DELETE /api/admin/synonyms/:id`
- validation and coercion per field type, applied identically in the browser
  and in the API
- an entry in the change log for every write

A read-only view is `capabilities: { create: false, update: false, delete: false }` —
that is all `search_audit` and `admin_audit` are.

### The only thing that needs code

A new **field type**. That costs exactly two additions:

1. a `case` in `src/lib/resources/validate.ts` (how to coerce and check it)
2. an entry in the `WIDGETS` map in `src/components/fields/index.tsx` (how to
   edit it)

The existing types — `text`, `textarea`, `slug`, `number`, `boolean`, `enum`,
`tags` (`text[]`), `slots`, `json`, `timestamp` — cover the registry as it
stands.

This mirrors the search service exactly: there, the only thing needing new code
is a new **resolver kind**.

---

## How it is put together

```
src/
  lib/resources/
    types.ts        the description language (FieldDef, ResourceDef, ComputedColumn)
    registry.ts     THE CONFIG — every managed table, as data
    validate.ts     coercion + validation per field type; shared by API and forms
    repository.ts   the one query builder: list / get / insert / update / delete
  app/api/admin/[resource]/…   the only CRUD endpoints; both are generic
  app/(app)/resources/[resource]/…  the only list/create/edit pages
  components/fields/            one widget per field type
```

Some deliberate choices:

**Identifiers are verified, not trusted.** `ident()` refuses anything that is
not a plain SQL name, and every value is a bound parameter. The registry is
developer-authored, but it is also edited often, which is exactly when a typo
turns into a query.

**Invariants live next to the constraint they mirror.** `has_params` with an
empty `slots` array is rejected with a sentence about Stage 2 rather than by
`features_params_have_slots` returning a Postgres error — but the constraint is
still there, so the rule holds even if a write bypasses the dashboard.

**PATCH is a real partial update.** Only submitted keys are written, so a form
showing six of ten columns can never blank the other four.

**Auth is checked twice.** The middleware blocks unauthenticated requests, and
every route handler re-checks. Authentication that lives only in a `matcher`
pattern is one typo away from being absent.

---

## Embeddings

Editing a feature's text **does not** drop its vector. The old vector still
retrieves — slightly out of date — whereas nulling it silently removes the
channel that catches phrasings nobody listed. So staleness is made visible
instead: `db/04_admin.sql` adds `embedding_source_hash`, which stores the md5 of
the exact document that was embedded. If the live text no longer hashes to it,
the row is listed on the Embeddings page, and one button fixes it.

The re-embed path is a faithful port of
`embedding-service/precompute_embeddings.py`:

- the same document (`display_name. keywords. aliases. description`, empty
  parts dropped) — `src/lib/featureDocument.ts` holds the TypeScript and SQL
  forms side by side
- sent with `is_query: false`, because bge is **asymmetric** — a document
  embedded with the query prefix retrieves measurably worse and fails nothing
  loudly
- written with the same 6-decimal pgvector literal

Verified against the running stack: a feature embedded through the dashboard
scores cosine `1.000000` against the embedding service's document form of the
same row. Either tool can be used; they produce the same vectors.

The hash written is the hash of the document that was actually embedded, not a
fresh one computed at write time — so an edit made *during* a long backfill
leaves the row correctly marked stale rather than falsely marked current.

---

## Where the analytics data comes from

`search_audit` exists in the schema but **the Spring backend does not write to
it** — grep for it under `backend/src` and there are no hits. So today the only
rows come from the playground, which writes one per query unless you untick
“record this search in the log”. The Analytics page says as much when the table
is empty rather than showing a convincing zero.

To make it reflect real traffic, have the backend write the row. The response
already carries everything needed, so it is one component and one call:

```java
// backend/src/main/java/com/bank/intuitivesearch/api/SearchAuditWriter.java
@Component
public class SearchAuditWriter {
    private static final Logger log = LoggerFactory.getLogger(SearchAuditWriter.class);
    private final JdbcTemplate jdbc;

    public SearchAuditWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Fire-and-forget: the audit row is diagnostics, and a search that worked
     * must not fail because the log write did.
     */
    @Async
    public void record(SearchRequest request, SearchResponse response) {
        try {
            jdbc.update("""
                INSERT INTO search_audit
                  (user_id, query, matched_feature, confidence, action, llm_invoked, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                request.userId(), request.query(), response.matchedFeature(),
                response.confidence(), response.action(),
                response.diagnostics().llmInvoked(), response.diagnostics().totalMs());
        } catch (DataAccessException e) {
            log.warn("search_audit write failed", e);
        }
    }
}
```

Then call it from `SearchController.search(...)` after the orchestrator
returns. Everything on the Analytics page works off that one row shape.

---

## Security notes

Written down because this dashboard writes to the registry that decides what
search finds, which makes it a more interesting target than the search API:

- **One password, one signed cookie.** HMAC-SHA-256 over an expiry, verified
  with a constant-time compare. No user table to drift, and swapping in SSO
  means replacing `createSessionCookie` / `verifySessionCookie` and the login
  route.
- **Login attempts are throttled** per client, in-process — enough for a
  single-instance dashboard, and honest about being no more than that.
- **The `next=` parameter is validated** to a same-site path. An open redirect
  on a login page is how a session ends up somewhere else.
- **The backend is never called from the browser.** The playground proxies
  through the server, so the API's address stays inside the network and the
  dashboard session is the only way to reach it.
- **Placeholder credentials are refused in production**, not warned about.
- Put this behind the same gateway as everything else if it is exposed beyond
  a laptop. It has no CSRF token: same-site cookies cover the browser case, and
  a real deployment should not be relying on this file for its perimeter.

---

## Development

```bash
npm run dev        # http://localhost:3001
npm run build      # production build
npm run typecheck  # tsc --noEmit
```

Stack: Next.js 15 (App Router, React 19), Tailwind v4, `pg`. No ORM — the
generic query builder is ~200 lines and needs the schema to stay describable,
which an ORM's model classes would fight. No component library — the field
widgets are the component library, and there are ten of them.

Chart colours are a validated categorical palette checked against this
dashboard's own light and dark surfaces; on the light surface two of the four
slots fall below 3:1, which is why every mark in the app is directly labelled.
