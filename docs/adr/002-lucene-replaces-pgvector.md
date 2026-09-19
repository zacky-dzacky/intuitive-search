# ADR-002 — Apache Lucene, in process, replaces the Postgres retrieval channels

**Status:** accepted · 2026-09-19
**Prompted by:** stakeholder review — *"leverage Apache Lucene; remove the vector DB, Lucene can store vectors in memory."*

## Context

Stage 1 ran four retrieval channels and merged them with weights:

| Channel | Where it ran | Postgres dependency |
|---|---|---|
| keyword | `tsvector` + `ts_rank_cd`, prefix `tsquery` | generated column, GIN index |
| trigram | `pg_trgm` `word_similarity` / `similarity` | extension, generated column, GIN index |
| vector | `pgvector` cosine over HNSW | extension, `VECTOR(384)` column, HNSW index |
| containment | in the JVM over the cached registry | none |

The backend is Java 21 / Spring Boot. The corpus is the feature catalogue: **67 rows**, one document each, edited rarely, read constantly, already cached whole in memory for the containment channel.

The question was whether the suggestion fits this scenario, or whether to explain why not. We researched it rather than assume.

## What we found

- **Fit.** Lucene is a Java library; it runs inside the service with no network hop, no extra process, and no database extension. It provides all three missing channels natively: BM25 with prefix terms (keyword), an n-gram field (trigram), and `KnnFloatVectorField` with HNSW (vector). The corpus builds in milliseconds and occupies kilobytes.
- **What is actually removed.** There was no separate vector database. `pgvector` was an extension on the same Postgres as the registry, next to `pg_trgm`. What goes is the extensions and the derived columns/indexes that served retrieval — and, in a bank, not needing `CREATE EXTENSION` on the corporate Postgres is a real governance win.
- **Dimension cap.** Lucene's default codec limits vectors to **1024 dimensions** (`Lucene99HnswVectorsFormat.getMaxDimensions() → 1024`, verified in source at 10.4.0; `KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS = 1024`). Elasticsearch and OpenSearch raise it by overriding the codec. Frontier embedding models default to 1536 (`text-embedding-3-small`) or 3072 (`text-embedding-3-large`, `gemini-embedding-001`).
- **"In memory".** `ByteBuffersDirectory` holds an index entirely in heap. Lucene's own guidance is that this is for small indexes and that `MMapDirectory` is the production choice for anything large. For 10²–10⁴ catalogue rows it is exactly right; it would not be for tens of thousands of help articles.
- **Not a store.** A Lucene index is a derived artefact. It has no place in the write path of the admin dashboard, no audit trail, no multi-writer story. Postgres has all three.

## Decision

1. **One in-process Lucene index serves the keyword, trigram and vector channels.** `FeatureIndex` (backend `index/`): one document per enabled feature, fields `text` (English-stemmed, BM25, prefix terms for typeahead), `ngram` (one term per pg_trgm-style padded trigram, `BooleanSimilarity` so a hit's score is its matched-trigram count), `embedding` (`KnnFloatVectorField`, cosine, HNSW).
2. **Scores keep their old scales so the merge weights keep their meaning.** BM25 → `s / (1 + s)` (the normalisation `ts_rank_cd` applied with flag 32); trigram → `max(word_similarity, similarity)` with pg_trgm's default thresholds (0.6 / 0.3) as inclusion floors; Lucene's `(1 + cos) / 2` → plain cosine. `HybridSearchService` is otherwise unchanged; the containment channel and every ranking test are untouched.
3. **Postgres remains the system of record.** The `features` table keeps only real columns. The `vector` extension, the `VECTOR` column, the generated `tsvector`/trigram columns, their indexes and the helper function are dropped (`db/05_drop_search_columns.sql` for existing databases). `pg_trgm` stays for Stage 3.
4. **The index is a cache rebuilt on change.** `FeatureIndexer` runs after every registry refresh (60 s). It fingerprints the registry (ids + document hashes), returns early when nothing changed, embeds only the rows whose text changed (one batch call), carries every other vector over from the previous generation, builds a new generation and swaps it in atomically; searches in flight finish on the old one. Cold start is one embedding call for the whole catalogue. Embedding failure still builds the index (lexical channels intact), records the error, and retries next refresh — the same degrade-to-lexical contract the request path already had.
5. **Embeddings are requested at 768 dimensions**, under Lucene's cap and at the size every target model documents for truncation. Enforced by the adapter (ADR-001).
6. **Operators see the index, not a column.** `GET /api/admin/index` (features, vectors, model, dims, built-at, last error) and `POST /api/admin/index/rebuild[?force=true]`. The admin dashboard's "Embeddings" page becomes "Search index" and proxies these; the feature form's post-save hook triggers a rebuild instead of a re-embed.

## Consequences

- **The hot path makes no network call for retrieval.** The query embedding is the only remaining hop, and repeated queries skip it.
- **No Postgres extensions for search; `pg_trgm` only for Stage 3.** Making Postgres fully extension-free (Stage 3 matches ~10 rows per customer and can be done in the JVM the same way) is a listed follow-up.
- **Each replica holds its own index.** An admin rebuild refreshes the replica that answered; the others converge within the refresh interval. Acceptable for a catalogue; if the lag ever matters, publish a "registry changed" signal instead of polling.
- **Scale path is decided now, so it is never re-litigated:** if the searchable corpus grows past what fits comfortably in heap, move the same index to `MMapDirectory` on local disk, or to OpenSearch — which is Lucene, with the same fields and queries. Not back to pgvector.
- The ivfflat-recall bug found in the PoC (Appendix C of the presentation) cannot recur: Lucene's vector index is HNSW by construction.
- `db/03_indexes.sql` no longer exists; `VectorLiteralTest` is deleted; `FeatureRepository` only reads the catalogue.

## Verdict for the stakeholders

The suggestion fits, and the reasons are theirs: fewer moving parts on the corporate database, retrieval inside the service, and a path to OpenSearch that is the same technology rather than a rewrite. The caveats — 1024-dim cap, index-is-a-cache, per-replica rebuild, Stage 3 still on `pg_trgm` — are recorded above so the next person does not have to redo the research.
