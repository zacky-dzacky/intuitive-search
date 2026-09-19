# Implementation Request: Smart In-App Feature Search with Intent & Slot Extraction

## Context

I'm building a banking mobile/web app with ~50 features (transfer money, download e-statement, check balance, settings/profile, pay bills, etc.). I want a smart search bar that:

1. Lets users find app features by typing natural language or abbreviations ("trf", "transfer", "download e-statement")
2. When the query contains transactional intent with parameters (e.g., "transfer to mom's 10000 usd"), extracts structured parameters and pre-fills the relevant form for user confirmation — **never auto-executes** anything
3. Stays fast (target: <100ms for pure navigation queries, <400ms for command-style queries with LLM extraction)
4. Scales to 50+ features without per-feature code — feature definitions should be data/config, not hardcoded logic

## Architecture (3-Tier Pipeline)

```
User Query
    │
    ▼
[Stage 1: Hybrid Search] — always runs, fast
    → keyword/fuzzy match + vector similarity match
    → returns best-matching feature(s) with confidence score
    │
    ▼
[Signal Detection] — generic, feature-agnostic, always runs, cheap
    → checks matched feature's schema: does it even accept params?
    → if yes, checks query for generic signals (numbers, currency tokens, date words, extra tokens beyond feature name)
    │
    ├── No params expected OR no signal found → show feature/menu directly, STOP (no LLM call)
    │
    ▼ (only if schema allows params AND signal detected)
[Stage 2: LLM Slot Extraction] — triggered conditionally, heavier
    → generic prompt template + matched feature's slot schema injected dynamically
    → returns structured JSON (e.g., {recipient: "mom", amount: 10000, currency: "USD"})
    │
    ▼
[Stage 3: Entity Resolution] — DB lookup, cheap
    → resolve extracted strings against real data (e.g., "mom" → actual saved payee/account)
    │
    ▼
Pre-filled form shown to user for confirmation (never auto-submitted)
```

## Tech Stack

**Backend orchestration:** Java 21 (LTS, required by bank infra standards) + Spring Boot 3.2+ (matches existing team stack, primary API/BFF layer for the search endpoint). Enable virtual threads (`spring.threads.virtual.enabled=true`) — the pipeline chains several I/O-bound calls per request (embedding service, LLM extraction, Postgres lookups), and virtual threads let this scale under load with simple blocking-style code instead of reactive/WebFlux complexity.

**Hybrid search layer:**
- Keyword/fuzzy matching: PostgreSQL full-text search (`tsvector`/`tsquery`) + `pg_trgm` for fuzzy/typo tolerance
- Vector similarity: `pgvector` extension on the same PostgreSQL instance (avoid introducing a separate vector DB unless scale later demands it — e.g., Elasticsearch/OpenSearch or a dedicated vector store like Weaviate/Qdrant)
- Merge/rerank: weighted score combining keyword rank + cosine similarity, done in application code (Spring Boot service layer)

**Embedding model (for Stage 1 vector search):**
- Small, CPU-friendly embedding-only model — e.g., `bge-small-en-v1.5` or `all-MiniLM-L6-v2`
- Served via a lightweight Python microservice (FastAPI) using `sentence-transformers`, OR via ONNX runtime embedded directly if avoiding a Python service is preferred
- Feature embeddings precomputed offline and stored in `pgvector`; only the user's live query is embedded in real time

**Generic signal detection (before Stage 2):**
- Pure Java logic in Spring Boot — regex for numbers/currency, a maintained list of currency tokens and date/relation words, token-count comparison against matched feature name
- No LLM involved at this step

**LLM slot extraction (Stage 2, triggered conditionally):**
- Small instruct model with structured/JSON output support — e.g., `Qwen2.5-3B-Instruct` or `Phi-3-mini`, served locally via `vLLM` or `Ollama` for low latency, OR Claude Haiku via API if on-prem hosting isn't required and network latency to the API is acceptable for banking compliance
- Constrained JSON output (function-calling style or JSON schema mode) to keep generation short and fast
- One generic prompt template, parameterized with the matched feature's slot definitions — not one prompt per feature

**Entity resolution (Stage 3):**
- Standard Spring Boot service + repository layer querying existing payee/contact/account tables in PostgreSQL
- Fuzzy name matching (`pg_trgm` again) for resolving informal references like "mom" against saved payee nicknames

**Feature registry (config, not code):**
- A `features` table in PostgreSQL (or a versioned JSON config file loaded at startup) with columns/fields: `feature_id`, `display_name`, `keywords`, `embedding_vector`, `has_params`, `slots` (JSON array of `{name, type, description}`)
- Adding a new feature = inserting a row, no code deployment

**Frontend:** (adapt to whatever the app already uses — React/React Native, or native Android/iOS) — search-as-you-type calling Stage 1 only; Stage 2 triggered on debounce/enter, not every keystroke

## Data Model (starting point)

```sql
-- Feature registry
CREATE TABLE features (
    feature_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    keywords TEXT[],              -- for full-text search
    embedding VECTOR(384),        -- matches chosen embedding model dimension
    has_params BOOLEAN DEFAULT FALSE,
    slots JSONB DEFAULT '[]'      -- e.g., [{"name":"recipient","type":"string"},{"name":"amount","type":"number"},{"name":"currency","type":"string"}]
);

CREATE INDEX ON features USING gin (keywords);
CREATE INDEX ON features USING ivfflat (embedding vector_cosine_ops);
```

## API Contract (Spring Boot endpoint)

```
POST /api/search
Request:  { "query": "transfer to mom's 10000 usd" }

Response:
{
  "matched_feature": "transfer",
  "confidence": 0.92,
  "params_extracted": true,
  "slots": {
    "recipient": { "raw": "mom", "resolved_account_id": "acc_123", "resolved_name": "Mom (Jane Doe)" },
    "amount": 10000,
    "currency": "USD"
  },
  "action": "prefill_form"   // vs "navigate" when no params extracted
}
```

## Implementation Instructions

1. Set up PostgreSQL with `pgvector` and `pg_trgm` extensions
2. Build the `features` table and seed it with the ~50 features (I'll provide the list, or start with placeholder data for: transfer, download_e_statement, check_balance, settings_profile, pay_bill)
3. Build the embedding service (Python FastAPI + sentence-transformers) with two endpoints: `/embed` (single query) and a batch script to precompute + store feature embeddings
4. Build the Spring Boot `/api/search` endpoint implementing the 3-tier pipeline described above, calling the embedding service for Stage 1 vector search and merging with Postgres full-text results
5. Implement the generic signal detector as a Spring Boot component (no per-feature logic)
6. Build the Stage 2 LLM extraction call as a generic, templated prompt — inject `matched_feature.slots` at runtime, request strict JSON output, validate/parse the response defensively (handle malformed JSON gracefully)
7. Implement Stage 3 entity resolution against existing payee/account tables using fuzzy matching
8. Return the structured response per the API contract above; frontend pre-fills the relevant form and requires explicit user confirmation before any transaction executes

## Non-negotiable constraints

- No transaction of any kind may be executed directly from search results — always require explicit user confirmation on a pre-filled form
- Stage 2 (LLM) must only trigger when Stage 1 confidence is high AND generic signal detection finds extractable content — never on every keystroke
- All slot definitions and feature metadata must live in the `features` table/config, not in application code, so adding feature #51 requires no code changes
