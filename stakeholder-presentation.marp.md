---
marp: true
theme: default
paginate: true
size: 16:9
header: 'Intuitive Search'
footer: 'Stakeholder review · Draft'
style: |
  section {
    font-size: 26px;
    padding: 60px 70px;
  }
  section.lead {
    text-align: center;
    justify-content: center;
  }
  section.lead h1 {
    font-size: 72px;
    margin-bottom: 20px;
  }
  section.lead p {
    font-size: 32px;
    color: #555;
  }
  h1 {
    font-size: 40px;
    border-bottom: 2px solid #333;
    padding-bottom: 8px;
    margin-bottom: 20px;
  }
  h2 {
    font-size: 30px;
    color: #444;
  }
  table {
    font-size: 20px;
    margin: 0 auto;
  }
  th, td {
    padding: 6px 10px;
  }
  code {
    font-size: 0.85em;
  }
  pre {
    font-size: 16px;
    line-height: 1.35;
  }
  section.diagram pre {
    font-size: 14px;
    line-height: 1.3;
  }
  section.dense {
    font-size: 22px;
    padding: 50px 70px;
  }
  section.dense h1 {
    font-size: 36px;
    margin-bottom: 14px;
  }
  blockquote {
    border-left: 4px solid #0b6efd;
    padding: 10px 18px;
    background: #f4f8ff;
    color: #123;
    font-size: 24px;
  }
  strong { color: #0b3d91; }
---

<!-- _class: lead -->

# Intuitive Search

Finding features the way customers actually ask for them.

<!--
Speaker note: one-line framing — "a search bar that understands trf, download e-statement, and transfer to mom's 10000 usd — and knows the difference between the first two and the third."
-->

---

# The problem we're solving

Two things break in a 60+ feature banking app:

- **Discovery.** Users know *what* they want, not *where it lives* in our IA. Menus punish long tails: *e-stmt*, *block card*, *where did my money go*.
- **Friction.** Even when they find the screen, they still have to fill it in. A power-user request like *transfer to mom's 10000 usd* travels through 4 taps and 3 fields before it's a form they can confirm.

We treat search as the shortest path from **intent → confirmable form**, without ever executing on the customer's behalf.

<!--
Speaker note: this framing lands the safety point up front — nothing here can move money. That is the single most important thing for compliance-minded stakeholders.
-->

---

# What it does, in one screen

| Query | What happens | LLM fired? | Latency |
|---|---|:---:|---:|
| `trf` | Opens Transfer | no | 13 ms |
| `e-stmt` | Opens Download e-Statement | no | 10 ms |
| `where did my money go` | Opens Spending Insights | no | 15 ms |
| `block my card` | Opens Block Card | no | 10 ms |
| `transfer to mom's 10000 usd` | **Pre-fills Transfer** (recipient=Mom, 10000 USD) | **yes** | 18 ms |
| `send 250 usd to landlord` | **Pre-fills Transfer** | **yes** | 8 ms |
| `download e-statement for march` | **Pre-fills e-Statement** (period=March) | **yes** | 12 ms |

Measured against the running stack, 67 seeded features.

<!--
Speaker note: this slide IS the demo. The nuance is in the last column — LLM only where it has to. Everything else is deterministic, fast, and cheap.
-->

---

<!-- _class: diagram -->

# How it works, and why in that shape

```
   user query
      │
      ▼
  Stage 1 — hybrid search        (always, ~10 ms, no LLM, in-process Lucene)
      BM25 · trigram fuzzy · HNSW cosine · naming-term containment
      │
      ▼
  Signal detection               (always, µs, no LLM)
      does the feature accept params?
      does the query carry numbers / currency / dates / extra tokens?
      │
   ┌──┴─────────────┐
   │                │
   ▼                ▼
 no signal      signal + params allowed
   │                │
   ▼                ▼
 action:        Stage 2 — LLM slot extraction  (one generic prompt)
 navigate            │
 (STOP)              ▼
                Stage 3 — entity resolution   ("mom" → payee acc_123)
                     │
                     ▼
                action: prefill_form
```

**Three properties:** cheapest thing that works · LLM only when a query carries parameters · nothing the model returns is trusted.

<!--
Speaker note: the four-value action enum (navigate · prefill_form · suggest · none) is the compliance boundary, in code. Grep-provable. Degrading is a first-class outcome, not an error path — everything works or falls back to something that works. The strongest action this service can return is prefill_form. There is no execute path anywhere in the codebase.
-->

---

# The economics of the LLM gate

If we called the LLM on every keystroke, at typeahead volumes we'd spend most of the search bar's budget on requests that were never going to need it.

The gate is what stops that. On the seeded workload:

- `trf`, `tran`, `transfer`, `send money`, `i want to send money please` → **all score below threshold**. Navigation only.
- `transfer to mom's 10000 usd` → **scores 4**. Extraction runs.

Signal detection is regex + a token list. It costs microseconds. It is the difference between a search that scales with query volume and one that scales with **LLM spend**.

> `GET /suggest` (used by typeahead) **hard-codes `allow_extraction=false`.** A client-side bug cannot open the LLM gate.

<!--
Speaker note: this is the answer to "won't this get expensive at scale?" — no, because 80–90% of queries never leave Postgres, and the safety is in the code, not in an operational rule someone might forget.
-->

---

# Adding a feature is one INSERT

```sql
INSERT INTO features (feature_id, display_name, description, category, route,
                      keywords, aliases, has_params, slots)
VALUES ('request_cheque_book', 'Request Cheque Book', ...,
        ARRAY['cheque','check book','chequebook'],
        ARRAY['chq','cheque'],
        TRUE,
        '[{"name":"account","type":"string","resolver":"account"}, ...]'::jsonb);
```

Then embed it. Cache picks it up within 60 seconds. No deploy, no release, no code review of a feature module.

- **67 features** ship today.
- Original constraint was "feature #51 requires no code changes". We are past that.
- The **only** thing that ever needs new engineering time is a new *resolver kind* — 8 cover the shapes a banking form actually has.

<!--
Speaker note: this is the slide for the BA. "When product adds a new capability, how does it get into search?" — a row, not a ticket. The admin dashboard (later slide) is the UI for this.
-->

---

# Performance, measured not claimed

**Budgets:** <100 ms navigation · <400 ms command extraction. Load tested against the running stack (10,000 requests).

| | before hardening | after |
|---|---|---|
| `/api/search`, c=32 | 56 rps · p50 565 ms | **637 rps · p50 24 ms** |
| `/api/search`, c=128 | 56 rps · p50 2,243 ms | **798 rps · p50 133 ms** |
| `/api/search`, c=512 | 56 rps · p50 9,020 ms | **851 rps · p50 535 ms** |
| 10,000 simultaneous, one each | — | **10,000/10,000 OK, 1,001 rps** |

Two lessons that generalise:

- **A read timeout does not bound a queue.** Our client had a 400 ms read timeout that never fired while p50 sat at 9 seconds. Fixed with a deadline over the *whole* call, a bulkhead, and a circuit breaker.
- **One CPU-bound worker gets *slower* under concurrency.** Fixed with `OMP_NUM_THREADS=1` and 4 processes.

*Measured on the previous stack. The first lesson's guards now front the hosted provider unchanged; retrieval itself no longer leaves the process.*

<!--
Speaker note: we didn't guess; we measured; the fixes name failure modes, not vibes. Appendix C has the two ranking bugs we only found by running the pipeline — same lesson. Appendix E covers what changed when we took the feedback on models and Lucene.
-->

---

# Frontier models: one adapter, three variables

Feedback taken: **no open-source models.** Both models moved — embeddings (was `bge-small`, self-hosted) *and* extraction (was `qwen2.5` on Ollama).

The Foundry sandbox isn't provisioned yet, so today it runs on Gemini. Not a detour: **Azure AI Foundry's v1 API and Gemini's API speak the same OpenAI-compatible wire format with an API key.** One adapter, no provider SDK.

| | Gemini (today) | Azure AI Foundry (sandbox) |
|---|---|---|
| `OPENAI_BASE_URL` | `generativelanguage.googleapis.com/v1beta/openai` | `<resource>.openai.azure.com/openai/v1` |
| `OPENAI_API_KEY` | Gemini key | Foundry key |
| model names | `gemini-3.6-flash` · `gemini-embedding-001` | the deployment names |

Same prompt, same schema, same parser, same index. `heuristic` (CI) and `ollama` (air-gapped) remain behind the same interface.

<!--
Speaker note: day one on the sandbox is a config change, not a sprint. The acceptance criterion is in ADR-001: point the base URL at Foundry, change nothing else, run the smoke corpus.
-->

---

# The admin dashboard

The registry needs to be edited by humans who are not backend engineers. The dashboard is the surface for that:

- Full **CRUD** over the features table, with a structured slot editor
- **Search index view** — what the service's index holds (features, vectors, model, last build) and a rebuild button; an edited feature re-embeds itself within 60 s
- **Search playground** — run a query against the live API, read the whole diagnostics block (per-channel scores, signal score, whether the LLM fired, per-stage timings)
- **Analytics** — LLM invocation rate, latency percentiles by path, top queries, and the *unresolved* queries (the registry's real to-do list)
- **Change log** of every write

Adding a *new managed table* is one config object, not a feature branch — the same property the search service has.

<!--
Speaker note: this is where the BA and product team live day to day. "Which of our queries don't resolve?" is a business question with a UI answer, weekly.
-->

---

# What ships next, and the one decision we need

**Pre-launch checklist** (all named, none surprising):

- Principal from JWT, not the request body — Stage 3 queries are user-scoped, hard requirement
- CORS pinned, endpoints behind the existing gateway
- `search_audit` writes wired into the backend (one `@Async` component; response already carries every field)

**Roadmap after pilot** (in payback order):

1. Feed unresolved queries from the dashboard to product weekly as concrete "we should add this" signal
2. Move Stage 3's payee/account fuzzy match off `pg_trgm` too — Postgres with no extensions at all
3. If the corpus ever becomes help content in the tens of thousands: same Lucene index on disk, or OpenSearch — never back to a database extension

> **One decision to make in this room:** which Foundry deployments does the pilot use — chat model, embedding model, and the embedding size (we recommend 768)?

<!--
Speaker note: end with a single, answerable question. Do not leave the room without it.
-->

---

<!-- _class: lead -->

# Thank you

Questions.

Appendices follow: response shape · stack · two ranking bugs we found by running it · Lucene instead of pgvector.

---

# Appendix A — Response shape

```json
{
  "matched_feature": "transfer",
  "display_name": "Transfer Money",
  "route": "/payments/transfer",
  "confidence": 0.529,
  "params_extracted": true,
  "slots": {
    "recipient": { "raw": "mom",
                   "resolved_account_id": "acc_123",
                   "resolved_name": "Mom (Jane Doe)",
                   "resolved_detail": "Local Bank ****8899",
                   "match_score": 1.0 },
    "amount": 10000, "currency": "USD"
  },
  "action": "prefill_form",
  "diagnostics": {
    "timings_ms": { "stage1_hybrid_search": 11, "signal_detection": 0,
                    "stage2_llm_extraction": 180, "stage3_entity_resolution": 3 },
    "total_ms": 195, "llm_invoked": true, "llm_provider": "anthropic",
    "signal_score": 4,
    "signals": ["number:10000","currency:usd","extra_tokens:mom"],
    "score_breakdown": { "keyword": 0.17, "trigram": 0.42,
                         "vector": 0.59, "containment": 0.75 }
  }
}
```

---

# Appendix B — Stack

- **Java 21 + Spring Boot 3.4** with virtual threads — chained I/O in blocking style, no reactive plumbing
- **Apache Lucene 10, in process** — the Stage-1 index (BM25 · trigram · HNSW), rebuilt from Postgres when the registry changes
- **PostgreSQL 16** with `pg_trgm` only — registry, customer data, audit; no `pgvector`
- **Frontier models via one OpenAI-compatible adapter** — Gemini today, Azure AI Foundry on sandbox delivery
- **Kubernetes** (local `orb.local`, production TBD), **Istio** for mesh + observability
- **Admin dashboard** — Next.js 16 (App Router) + Tailwind, no ORM
- **75 unit tests** — signal gate, hostile-JSON parsing, prompt genericity, Stage-3 normalisation, containment ranking, the Lucene index, the incremental indexer, the adapter's wire contract

---

<!-- _class: dense -->

# Appendix C — Two ranking bugs we found by running it

Both would have passed a green unit-test suite. Kept in case the Tech Lead asks about verification.

**1. Similarity is diluted by the parameter payload.**
`transfer to mom's 10000 usd` shares one word with the registry; the rest is payload no feature has ever seen. Every similarity channel drops. The queries that most needed Stage 2 were being gated out of it.

*Fix* — a **containment channel** (does the query literally contain a feature's naming term? — payload can dilute similarity, it cannot dilute this) plus a **margin-based gate** ("high confidence" really means "clear gap to the runner-up", not "raw score above X").

**2. `ivfflat` silently lost the right answer.**
At `lists=8, probes=1`, a query searches one partition. `send 250 usd to landlord` came back with transfer scoring **0.0 on vector** while topping every lexical channel — not an error, just a quietly missing candidate.

*Fix* — HNSW then; Lucene's vector index is HNSW by construction, so the lesson carried over.

---

<!-- _class: dense -->

# Appendix D — Why embeddings didn't run on Ollama (historical)

*From the previous revision, when Stage 1 ran a self-hosted model. Kept for the one point that still holds: query and document vectors must come from one runtime — which is why the indexer re-embeds the whole catalogue through the hosted model.*

- **One runtime for query and document vectors.** The registry was embedded offline by sentence-transformers. Ollama (llama.cpp, quantised GGUF, own tokenizer) emits slightly different vectors — switching means re-embedding the registry first. A migration, not a config change.
- **400 ms deadline vs idle unload.** Ollama unloads an idle model after five minutes; the first query after a lull pays a multi-second reload and trips our circuit breaker. Our service loads before the port opens.
- **Scaling shape.** Slide 7's four single-threaded processes are the opposite of Ollama's one-process, N-slots model — and sharing it with Stage 2's 3B model on CPU queues the *always-on* stage behind the *conditional* one.
- **The knobs are thirty lines of Python.** BGE's query prefix, unit-normalised output, the 128-token cap, the typeahead cache — all would move into the Java client.

*When we'd switch:* a GPU node, or a mandate to consolidate serving. Then a separate Ollama instance, keep-alive pinned, prefix in the client, registry re-embedded before traffic moves.

---

<!-- _class: dense -->

# Appendix E — Lucene instead of pgvector: what actually changed

Feedback taken: **leverage Apache Lucene; drop the vector DB.** We checked it fits before agreeing. It does — with four things said out loud.

- **What was removed.** There was never a separate vector database: `pgvector` was an *extension* on the registry's Postgres, next to two more for full-text and trigrams. Gone: those extensions and a network hop per channel. Not needing `CREATE EXTENSION` on the corporate Postgres is the governance win — and it is your argument, not ours.
- **What Lucene does.** All three channels in one in-process index: BM25 (was `tsvector`), padded trigrams (was `pg_trgm`), HNSW vectors (was `pgvector`). Same scales, same weights, same ranking tests. 67 rows; builds in milliseconds.
- **"In memory" is right for this corpus, and we know the edge.** The index is a cache rebuilt from Postgres on change; Postgres stays the system of record. Right for 10²–10⁴ catalogue rows. If it ever becomes help content in the tens of thousands: same index on disk, or OpenSearch — which *is* Lucene. Never back to a database extension.
- **One constraint, found in the code.** Lucene's stock codec caps vectors at **1024 dims**; frontier models default to 1536/3072. So we ask for **768** — every target model is trained for it — and enforce it. It's a setting, but it is why the Foundry decision includes the embedding size.
- **Not changed, honestly:** Stage 3 still uses `pg_trgm` on the customer tables. Listed follow-up.

<!--
Speaker note: the answer to "does it make sense?" is yes. ADR-002 records the reasoning so nobody redoes the research.
-->
