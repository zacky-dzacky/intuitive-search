# Stakeholder feedback: frontier models via API adapter + Apache Lucene for Stage 1

> **Status: implemented 2026-09-19.** The decisions and their rationale are recorded in
> [`adr/001-frontier-models-openai-compatible-adapter.md`](adr/001-frontier-models-openai-compatible-adapter.md)
> and [`adr/002-lucene-replaces-pgvector.md`](adr/002-lucene-replaces-pgvector.md);
> this file is the plan as it was written before the work. Follow-up on 2026-09-20:
> the Python embedding service and the `local` provider were removed entirely.

## Context

The PoC was presented; two pieces of feedback came back:

1. **No open models.** The office will commit to frontier models, most likely via **Azure AI Foundry**. Sandbox isn't available yet, so build against another API‑key provider (Gemini) such that moving to Foundry is "just change the adapter".
2. **Leverage Apache Lucene** and drop the vector DB, since Lucene can hold vectors in memory.

Today the PoC uses two open models — `BAAI/bge-small-en-v1.5` (384‑dim, Python sentence‑transformers service) for the vector channel and `qwen2.5:3b` (Ollama) for slot extraction — and three Postgres retrieval channels (`tsvector`, `pg_trgm`, `pgvector`) over a 67‑row `features` catalogue.

### Research findings that drive the design

**Azure Foundry and Gemini speak the same wire format.** Foundry's v1 API is used with the *standard* OpenAI client: `base_url=https://<resource>.openai.azure.com/openai/v1/` + `api_key` (no `api-version`, no Azure‑specific client). Gemini exposes the identical format at `https://generativelanguage.googleapis.com/v1beta/openai/`, including `/chat/completions` (with `response_format` JSON schema and `tools`) and `/embeddings`. So a single **OpenAI‑compatible adapter** covers Gemini now and Foundry later — only `base-url`, `api-key` and model names change. No SDK needed; the existing `RestClient` pattern from `OllamaSlotExtractor` is enough.

**Lucene fits this scenario, with four honest caveats** (verified against Lucene `main` source, latest release 10.4.0, Java 21 — matches the backend):
- What is being removed is a Postgres *extension* and one network hop per channel, not a standalone vector DB. In a bank, not needing `CREATE EXTENSION vector` on the corporate Postgres is a real governance win.
- The default codec caps vectors at **1024 dimensions** (`Lucene99HnswVectorsFormat.getMaxDimensions() → 1024`; only Elasticsearch/OpenSearch override it). Frontier embeddings default to 1536/3072, so we request **768 dims** (`dimensions` param — supported by Azure `text-embedding-3-*`; Gemini `gemini-embedding-001` is Matryoshka‑trained so client‑side truncate+renormalise to 768 is valid) and enforce it in the adapter.
- "In memory" (`ByteBuffersDirectory`) is right *because* the corpus is tiny and rebuildable in milliseconds. The index is a derived cache; **Postgres remains the system of record** for the catalogue, customer data and audit. The scale path if the corpus ever becomes thousands of help articles is `MMapDirectory` on disk or OpenSearch (same Lucene) — never back to pgvector.
- Lucene replaces the three *feature‑search* channels only. Stage 3 (payee/account fuzzy match) still uses `pg_trgm` on per‑user tables; making Postgres fully extension‑free is a listed follow‑up, not part of this change.

Verdict: implement both. Write the narrative as ADRs so the stakeholders get the trade‑offs, not just the change.

---

## Part A — Frontier models through one OpenAI‑compatible adapter

Config contract (env → `application.yml` → `SearchProperties`). Switching Gemini → Foundry is *only* the values in the right column.

| Env var | Gemini (now) | Azure Foundry (later) |
|---|---|---|
| `LLM_PROVIDER` | `openai` | `openai` |
| `OPENAI_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` | `https://<resource>.openai.azure.com/openai/v1` |
| `OPENAI_API_KEY` | Gemini key | Foundry key |
| `OPENAI_CHAT_MODEL` | `gemini-3.6-flash` | deployment name, e.g. `gpt-4.1-mini` |
| `EMBEDDING_PROVIDER` | `openai` | `openai` |
| `OPENAI_EMBEDDING_MODEL` | `gemini-embedding-001` | deployment name, e.g. `text-embedding-3-small` |
| `EMBEDDING_DIMENSIONS` | `768` | `768` |

(Embedding may point at a different base URL/key than chat via optional `OPENAI_EMBEDDING_BASE_URL` / `OPENAI_EMBEDDING_API_KEY`, defaulting to the chat values.)

### A1. `SearchProperties` (`backend/src/main/java/com/bank/intuitivesearch/config/SearchProperties.java`)
- `Llm`: add nested `OpenAi { baseUrl, apiKey, model, authHeader = "bearer" }` (`authHeader` accepts `bearer` | `api-key` — cheap insurance in case a Foundry gateway wants the legacy `api-key` header).
- `Embedding`: add `provider = "local"` (`local` | `openai`), nested `OpenAi { baseUrl, apiKey, model, authHeader }`, `queryPrefix`/`documentPrefix` (empty by default; the bge prefix moves here for the `local` provider), `documentTimeoutMs = 30000`, `queryCacheSize = 4096`. `dimensions` stays the single source of truth for vector size (default becomes 768 when provider is `openai`).

### A2. Stage 2 — `OpenAiCompatibleSlotExtractor` (new, `extraction/`)
- `@ConditionalOnProperty(name="search.llm.provider", havingValue="openai")`, implements `SlotExtractor`; mirror `OllamaSlotExtractor` structure (never throws; degrades to `ExtractionResult.empty`).
- `POST {base}/chat/completions` with `model`, `messages` from `PromptBuilder.systemPrompt()/userPrompt()`, `temperature: 0`, `max_tokens`, and `response_format: {type: "json_schema", json_schema: {name: "extract_slots", schema: PromptBuilder.jsonSchema(feature), strict: false}}`. Content goes through the existing `SlotJsonParser.parse` (handles prose/fences if a provider ignores `response_format`).
- New `RestClient openAiChatRestClient` bean in `HttpClientConfig` with the auth header applied via `defaultHeader`; keep the connect‑500ms / read‑`llm.timeout-ms` pattern.

### A3. Embedding — split resilience from transport
`EmbeddingClient` keeps its circuit breaker / bulkhead / deadline unchanged; the HTTP call is delegated to a new strategy interface:

```java
interface EmbeddingBackend {          // search/EmbeddingBackend.java
    float[] embedQuery(String text);
    List<float[]> embedDocuments(List<String> texts);   // batch, one request
    String describe();                                  // provider/model for health + admin
}
```
- `LocalEmbeddingBackend` — existing `/embed` + `/embed/batch` wire (records moved out of `EmbeddingClient`); kept for dev/CI only.
- `OpenAiEmbeddingBackend` — `POST {base}/embeddings` with `{model, input: [...], dimensions}`; parses `data[].embedding` (sorted by `index`). **Dimension policy:** if the response is longer than `dimensions`, truncate and L2‑renormalise (Matryoshka); if shorter, reject. Applies `queryPrefix`/`documentPrefix` if configured.
- Selection by `search.embedding.provider` (`@ConditionalOnProperty`, `local` as `matchIfMissing`).
- `EmbeddingClient` gains `embedDocuments(List<String>)` (long timeout, bypasses the bulkhead; used by the indexer) and a small in‑JVM LRU for `embedQuery` — the Python service's `lru_cache(4096)` moved to where the money is now spent (typeahead repeats prefixes constantly).
- Raise the default `search.embedding.timeout-ms` for the `openai` provider (hosted round trip is ~150–400ms; 400ms would trip the breaker) — set `800` in `application.yml` with the comment explaining the trade‑off; keep `400` documented for `local`.

### A4. Config/deploy surface
- `application.yml`: new keys + comments; `EMBEDDING_PROVIDER`, `OPENAI_*` placeholders.
- `backend/k8s/configmap.yaml` (`LLM_PROVIDER: openai`, `EMBEDDING_PROVIDER: openai`, base URLs, model names) and `secret.example.yaml` (`OPENAI_API_KEY`). Remove `EMBEDDING_BASE_URL`'s reference to the Python service in the default path.
- `README.md` "Stage 2 providers": add the `openai` row and a **"Switching to Azure Foundry"** subsection = the table above.

---

## Part B — Lucene replaces the three Postgres feature‑search channels

### B1. Dependencies
`backend/pom.xml`: `org.apache.lucene:lucene-core:10.4.0`, `lucene-analysis-common:10.4.0`. Remove nothing yet (Anthropic SDK stays for the `anthropic` provider).

### B2. `FeatureIndex` (new, `registry/FeatureIndex.java`)
In‑memory Lucene index over the enabled features, rebuilt and atomically swapped (`AtomicReference<IndexHolder{Directory, DirectoryReader, IndexSearcher}>`, old generation closed after swap). One document per feature:

| Field | Type | Replaces |
|---|---|---|
| `feature_id` | `StringField` + `StoredField` | — |
| `text` | `TextField`, `EnglishAnalyzer` (stemming ≈ Postgres `'english'`), content = `display_name + keywords + aliases + description` | `search_document` tsvector |
| `ngram` | `TextField`, custom analyzer: lowercase → `NGramTokenizer(3,3)`, content = `lower(display_name + keywords + aliases)`; `NumericDocValuesField ngram_count` = distinct trigrams | `match_text` + `pg_trgm` |
| `embedding` | `KnnFloatVectorField(…, COSINE)` (only when a vector is available) | `embedding VECTOR(384)` + HNSW |

Search API mirrors `FeatureRepository` so `HybridSearchService` changes minimally:
- `List<ChannelHit> lexicalSearch(String normalisedQuery, int limit)` — one `IndexSearcher.search` for keyword (BooleanQuery of SHOULD `TermQuery` on stemmed tokens + `PrefixQuery` for tokens ≥ 3 chars with `SCORING_BOOLEAN_REWRITE`; score normalised as `s / (1 + s)`, the same mapping `ts_rank_cd(..., 32)` uses) and one for trigram (BooleanQuery of the query's trigrams on `ngram`, `BooleanSimilarity` via `PerFieldSimilarityWrapper` so the raw score = number of matched query trigrams; `coverage = matched/|Q|` ≈ `word_similarity`, `jaccard = matched/(|Q|+|D|−matched)` = `similarity`; channel score = `max(coverage, jaccard)`, exactly today's `GREATEST(...)`). Return merged `ChannelHit(featureId, keywordScore, trigramScore, 0)`.
- `List<ChannelHit> vectorSearch(float[] query, int limit)` — `KnnFloatVectorQuery("embedding", vec, limit)`; Lucene's COSINE score is `(1+cos)/2`, convert back to cosine (`2·score − 1`) so the vector channel keeps its current scale.
- `IndexStats stats()` — features indexed, with vectors, embedding provider/model/dims, built‑at, build duration, last error (for health + admin).

`ChannelHit` record moves from `FeatureRepository` to `FeatureIndex` (or `model/`); `FeatureRepository` loses `lexicalSearch`, `vectorSearch`, `anyEmbeddingsPresent`, `toVectorLiteral` (+ `VectorLiteralTest`).

### B3. Index lifecycle (`FeatureRegistry.refresh()` is the hook)
- `FeatureRegistry.refresh()` already runs at startup and every 60s. After it loads features it calls `featureIndex.rebuild(features)`.
- `rebuild` is idempotent and cheap: it computes the document hash per feature (same document form as `precompute_embeddings.feature_document` / `featureDocument.ts`: `display_name. keywords, …. aliases, …. description`, empty parts dropped) and **only embeds features whose hash is new or changed**, reusing vectors from the previous generation (`Map<featureId, (hash, float[])>`). Cold start = one batch `/embeddings` call for 67 docs. Unchanged registry = no API call, and the Lucene rebuild is skipped entirely when nothing changed (fingerprint of ids+hashes).
- If embedding fails during a rebuild, the index is still built with whatever vectors exist and the failure is recorded in `stats()`; the next refresh retries. This is the same "degrade to lexical" contract the pipeline already has.
- `POST /api/admin/index/rebuild` (new `api/IndexAdminController`, `force` flag re‑embeds everything) and `GET /api/admin/index` for status. Multi‑replica note: the endpoint refreshes one pod; the 60s poll converges the rest (documented).

### B4. `HybridSearchService`
Swap `repository.lexicalSearch(tsQuery, normalised, limit)` → `featureIndex.lexicalSearch(normalised, limit)` and `repository.vectorSearch` → `featureIndex.vectorSearch`. Remove `toTsQuery` (and its test cases in `HybridSearchQueryTest`; the containment tests stay untouched). Merge logic, weights, exact‑match bump, containment channel: unchanged.

### B5. Database
- `db/01_schema.sql`: drop `CREATE EXTENSION vector` and `embedding VECTOR(384)`; keep `pg_trgm` (Stage 3). Update the header comment.
- `db/03_indexes.sql`: drop the HNSW index; keep the GIN indexes only if Stage 3 needs them (it doesn't — they were for `features`; remove).
- New `db/05_drop_pgvector.sql`: `ALTER TABLE features DROP COLUMN IF EXISTS embedding, DROP COLUMN IF EXISTS embedding_source_hash, DROP COLUMN IF EXISTS embedded_at; DROP INDEX …; DROP EXTENSION IF EXISTS vector;` for existing databases.
- `admin-dashboard/db/04_admin.sql`: remove the provenance columns + backfill block.
- `db/DATABASE.md`: replace the pgvector/HNSW section with "the search index lives in the backend (Lucene); Postgres holds the catalogue".

### B6. Admin dashboard → "Search index" page
- `src/lib/embeddings.ts`: replace the SQL + Python‑service calls with two thin calls to the backend: `indexStatus()` → `GET {BACKEND_BASE_URL}/api/admin/index`, `rebuildIndex({force})` → `POST …/rebuild`. Keep the module/route names so `ResourceForm.tsx` (post‑save re‑embed) keeps working unchanged apart from the body (`{}` instead of `featureIds`).
- `src/app/api/embeddings/route.ts`: proxy to the above; keep `recordAdminAction("reembed")`.
- `src/app/(app)/embeddings/page.tsx` + `ReembedControls.tsx`: tiles become *Features indexed / With vector / Embedding model / Last built*; button text "Rebuild index". Drop the stale‑features table.
- `src/lib/resources/registry.ts`: remove the `embedding_stale` list column; `src/lib/setup.ts`: remove the provenance‑column check; `src/lib/health.ts`: drop the "Embedding service" probe (backend `/actuator/health` already reports embedding degradation); `env.ts`: remove `embeddingBaseUrl`/`EMBEDDING_DIMENSIONS`; `.env.example` accordingly.

### B7. Retire the Python embedding service from the default path
- `embedding-service/` stays in the repo as the `local` provider for offline dev, but is removed from Quick start, `deploy-local.sh` sequence, and k8s defaults. `precompute_embeddings.py` and `check_embeddings.py` are deleted (their job moved into the backend indexer). README/DEPLOYMENT notes updated; `concurrency.md` gets a one‑line "superseded" header.

---

## Part C — Narrative for stakeholders

`docs/adr/001-frontier-models-openai-compatible-adapter.md` and `docs/adr/002-lucene-replaces-pgvector.md` (Context / Decision / Consequences / What we did *not* change), plus updates to `stakeholder-presentation.md`:
- Slide 8 "Provider flexibility": replace the ollama/anthropic framing with *"one adapter, two providers: Gemini today, Azure Foundry when the sandbox lands — three environment variables"*.
- Slide 10: the open decision is now "Foundry model choice + dims", not ollama‑vs‑anthropic.
- New Appendix E — *"Lucene instead of pgvector: what actually changed"*: the four caveats above, the 1024‑dim fact, why 768 dims, and the scale path (OpenSearch, not pgvector).
- Appendix D (why embeddings don't run on Ollama) becomes historical; add a one‑line note.

---

## Files touched (summary)

**New:** `extraction/OpenAiCompatibleSlotExtractor.java`, `search/EmbeddingBackend.java`, `search/LocalEmbeddingBackend.java`, `search/OpenAiEmbeddingBackend.java`, `registry/FeatureIndex.java`, `api/IndexAdminController.java`, `db/05_drop_pgvector.sql`, `docs/adr/001-*.md`, `docs/adr/002-*.md`, tests below.

**Modified:** `config/SearchProperties.java`, `config/HttpClientConfig.java`, `search/EmbeddingClient.java`, `search/HybridSearchService.java`, `registry/FeatureRegistry.java`, `registry/FeatureRepository.java`, `backend/pom.xml`, `application.yml`, `backend/k8s/configmap.yaml`, `backend/k8s/secret.example.yaml`, `db/01_schema.sql`, `db/03_indexes.sql`, `db/DATABASE.md`, `admin-dashboard/db/04_admin.sql`, `admin-dashboard/src/lib/{embeddings,health,env,setup}.ts`, `admin-dashboard/src/lib/resources/registry.ts`, `admin-dashboard/src/app/api/embeddings/route.ts`, `admin-dashboard/src/app/(app)/embeddings/page.tsx`, `admin-dashboard/src/components/{ReembedControls,ResourceForm}.tsx`, `admin-dashboard/.env.example`, `README.md`, `stakeholder-presentation.md` (+ `.marp.md`), `smoke-search.sh` (no change expected; used for verification).

**Deleted:** `registry/VectorLiteralTest.java`, `embedding-service/precompute_embeddings.py`, `embedding-service/check_embeddings.py`.

---

## Verification

Unit (no DB, no network — same as today's suite; `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test`):
- `registry/FeatureIndexTest`: build from a handful of in‑memory `Feature`s (reuse fixtures from `HybridSearchQueryTest`); assert `tran` → Transfer Money via prefix, `trf`/`estmt` score on the trigram channel, stemmed match (`transferring` → transfer), disabled/unknown ids never returned, kNN with hand‑made unit vectors returns nearest first with cosine ≈ expected, rebuild with an unchanged feature set doesn't re‑embed (fake `EmbeddingBackend` counts calls), changed description re‑embeds only that feature, generation swap closes the old reader.
- `extraction/OpenAiCompatibleSlotExtractorTest` and `search/OpenAiEmbeddingBackendTest`: `MockRestServiceServer` bound to the `RestClient.Builder` (already available via `spring-boot-starter-test`): correct path/auth header/body shape, `json_schema` response parsed, prose fallback, 3072‑dim response truncated+renormalised to 768, 1536→768 same, shorter‑than‑configured rejected, `api-key` header mode.
- Existing `HybridSearchQueryTest` ranking cases must still pass (weights may need a small retune if BM25 normalisation shifts scores — decide from the smoke corpus, not by hand).

Integration (real Gemini key, local Postgres, `mvn spring-boot:run`):
1. Run `db/05_drop_pgvector.sql`; start backend with `LLM_PROVIDER=openai EMBEDDING_PROVIDER=openai OPENAI_BASE_URL=… OPENAI_API_KEY=… OPENAI_CHAT_MODEL=gemini-3.6-flash OPENAI_EMBEDDING_MODEL=gemini-embedding-001 EMBEDDING_DIMENSIONS=768`.
2. Startup log shows one batch embedding call for 67 docs; `GET /api/admin/index` reports 67 indexed / 67 with vector / model + 768 dims.
3. `API=http://localhost:8080 ./smoke-search.sh` — the README query corpus (`trf`, `e-stmt`, `where my money`, `transfer to mom's 10000 usd` → `prefill_form` with resolved payee) passes; `diagnostics.llm_provider == "openai"`.
4. Edit a feature via the admin dashboard → within 60s (or via "Rebuild index") the `GET /api/admin/index` built‑at advances and only 1 doc was re‑embedded (log line).
5. Kill network to the embedding API → search degrades to lexical‑only (`vector_score` absent), `/actuator/health` shows degraded; restore → recovers.
6. `cd admin-dashboard && npm run typecheck && npm run lint`.
7. Foundry dry‑run: point `OPENAI_BASE_URL` at an unreachable Azure‑shaped URL and confirm the only diff needed is env (no code path differs) — documented in the ADR as the acceptance criterion for the sandbox hand‑over.

## Follow‑ups (explicitly out of scope)
- Move Stage 3 payee/account fuzzy matching off `pg_trgm` (per‑user lists are ~10 rows; Lucene `FuzzyQuery` or in‑JVM trigram scoring) so Postgres needs no extensions at all.
- Delete `embedding-service/` entirely once nobody runs the `local` provider.
- If the catalogue grows past ~10k documents: `MMapDirectory` on local disk or OpenSearch — decision recorded in ADR‑002.
