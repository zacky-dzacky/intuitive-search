# Intuitive Search

Smart in-app feature search for a banking app: finds features from natural
language or abbreviations, and — when the query carries transactional intent —
extracts the parameters and **pre-fills a form for the user to confirm**.

It never executes anything.

---

## The pipeline

```
                          user query
                              │
                              ▼
        ┌───────────────────────────────────────────┐
        │ Stage 1 · Hybrid search      always, fast  │
        │  · Postgres FTS (prefix)   ─┐              │
        │  · pg_trgm fuzzy/abbrev    ─┼─ weighted    │
        │  · pgvector cosine (HNSW)  ─┤    merge     │
        │  · naming-term containment ─┘              │
        └──────────────────┬────────────────────────┘
                           ▼
        ┌───────────────────────────────────────────┐
        │ Signal detection   always, µs, NO LLM      │
        │  1. does the matched feature's schema even │
        │     accept params?  (has_params + slots)   │
        │  2. does the query show generic evidence:  │
        │     numbers · currency · dates · extra     │
        │     tokens beyond the feature's vocabulary │
        └──────┬────────────────────────────┬───────┘
     no params │                            │ schema allows params
     or signal │                            │ AND signal found
               ▼                            ▼
        action: "navigate"       ┌────────────────────────────┐
        (STOP — no LLM call)     │ Stage 2 · LLM extraction   │
                                 │  one generic prompt; the   │
                                 │  feature's slots injected  │
                                 │  as a JSON schema at       │
                                 │  runtime → strict JSON     │
                                 └─────────────┬──────────────┘
                                               ▼
                                 ┌────────────────────────────┐
                                 │ Stage 3 · Entity resolution│
                                 │  "mom" → payee acc_123 via │
                                 │  pg_trgm, scoped to user   │
                                 └─────────────┬──────────────┘
                                               ▼
                                   action: "prefill_form"
                                   → user reviews and confirms
```

**The strongest action this service can return is `prefill_form`.** There is no
execute path, no transaction endpoint, and no code that could be extended into
one by accident — the response type's `action` field has exactly four values:
`navigate`, `prefill_form`, `suggest`, `none`.

---

## Quick start

```bash
# 1. Database (local Postgres — see db/DATABASE.md for setup)
psql -U bank -d banksearch -f db/01_schema.sql
psql -U bank -d banksearch -f db/02_seed.sql

# 2. Deploy backend + embedding service to Kubernetes
cd backend && ./deploy-local.sh && cd ..
cd embedding-service && ./deploy-local.sh && cd ..

# 3. Backfill feature embeddings, then build the vector index
cd embedding-service
pip install -r requirements.txt
python precompute_embeddings.py
cd ..
psql -U bank -d banksearch -f db/03_indexes.sql

# 4. Demo UI — point it at the k8s service
open "frontend/index.html?api=http://k8s.orb.local:8080"
```

If you don't want to install Python locally, run the backfill inside the
embedding pod instead:

```bash
kubectl exec -it deploy/embedding-service -- \
  env DATABASE_URL=postgresql://bank:bank@host.docker.internal:5432/banksearch \
  python precompute_embeddings.py
```

### Verified behaviour

Measured against the running stack (67 seeded features, embeddings loaded,
`provider: heuristic`):

| Query | Feature | Conf. | Action | LLM | Time |
|---|---|---|---|---|---|
| `trf` | transfer | 0.97 | navigate | no | 13ms |
| `tran` | transfer | 0.53 | navigate | no | 13ms |
| `e-stmt` | download_e_statement | 0.97 | navigate | no | 10ms |
| `check balance` | check_balance | 0.97 | navigate | no | 6ms |
| `block my card` | block_card | 0.76 | navigate | no | 10ms |
| `where did my money go` | spending_insights | 0.67 | navigate | no | 15ms |
| `transfer to mom's 10000 usd` | transfer | 0.53 | **prefill_form** | yes | 18ms |
| `send 250 usd to landlord` | transfer | 0.49 | **prefill_form** | yes | 8ms |
| `download e-statement for march` | download_e_statement | 0.81 | **prefill_form** | yes | 12ms |
| `show me last month transactions over 500` | transaction_history | 0.57 | **prefill_form** | yes | 11ms |

The LLM column is the point: it is `no` for every navigation query, including
partial words and abbreviations, and `yes` only where the query actually
carries parameters.

### Try it

```bash
# Navigation — abbreviation, no LLM call
curl -s localhost:8080/api/search -H 'content-type: application/json' \
  -d '{"query":"trf"}' | jq '{matched_feature, action, params_extracted}'

# Command — extraction + resolution
curl -s localhost:8080/api/search -H 'content-type: application/json' \
  -d '{"query":"transfer to mom'"'"'s 10000 usd","user_id":"user_1"}' | jq
```

```json
{
  "matched_feature": "transfer",
  "display_name": "Transfer Money",
  "route": "/payments/transfer",
  "confidence": 0.529,
  "params_extracted": true,
  "slots": {
    "recipient": {
      "raw": "mom",
      "resolved_account_id": "acc_123",
      "resolved_name": "Mom (Jane Doe)",
      "resolved_detail": "Local Bank ****8899",
      "match_score": 1.0
    },
    "amount": 10000,
    "currency": "USD"
  },
  "action": "prefill_form"
}
```

---

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/search` | Full pipeline. Body: `{query, user_id?, allow_extraction?}` |
| `GET /api/search/suggest?q=…` | Stage 1 only — safe on every keystroke, **cannot** reach the LLM |
| `GET /api/search/features` | Registry introspection |
| `GET /actuator/health` | Liveness |

Every response carries a `diagnostics` block — per-stage timings, whether the
LLM fired and why, the signal score, and the per-channel score breakdown. That
is what you tune the latency budgets against:

```json
"diagnostics": {
  "timings_ms": {"stage1_hybrid_search": 11, "signal_detection": 0,
                 "stage2_llm_extraction": 180, "stage3_entity_resolution": 3},
  "total_ms": 195, "llm_invoked": true, "llm_provider": "anthropic",
  "signal_score": 4, "signals": ["number:10000", "currency:usd", "extra_tokens:mom"],
  "stopped_at": "stage3_entity_resolution",
  "score_breakdown": {"keyword": 0.17, "trigram": 0.42,
                      "vector": 0.59, "containment": 0.75}
}
```

---

## Adding a feature

One INSERT. No deploy, no code. (The registry ships with 67, so this is
feature #68 — the constraint was "feature #51 requires no code changes".)

```sql
INSERT INTO features (feature_id, display_name, description, category, route,
                      keywords, aliases, has_params, slots)
VALUES ('request_cheque_book', 'Request Cheque Book',
        'Order a new cheque book for a current account.',
        'accounts', '/accounts/cheque-book',
        ARRAY['cheque','check book','chequebook','order cheques'],
        ARRAY['chq','cheque'],
        TRUE,
        '[{"name":"account","type":"string","resolver":"account",
           "description":"Which account the cheque book is for."},
          {"name":"quantity","type":"number","resolver":"amount",
           "description":"How many cheque books to order."}]'::jsonb);
```

Then embed it: `python embedding-service/precompute_embeddings.py`.
The registry cache picks the row up within 60s (`search.registry-refresh-ms`).

That row alone drives all four stages:

| Column | Drives |
|---|---|
| `keywords`, `aliases` | full-text + trigram matching (`search_document` and `match_text` are `GENERATED` columns, so they can't drift) |
| `embedding` | vector recall for phrasings nobody listed |
| `has_params` + `slots` | whether signal detection can ever open the LLM gate |
| `slots[].description` | the text injected into the one shared prompt |
| `slots[].type` / `enum` | the JSON schema used for constrained decoding, and type coercion |
| `slots[].resolver` | which Stage-3 strategy runs |
| `route` | what the frontend opens |

The only thing that ever needs new code is a new **resolver kind**. The built-in
set — `payee`, `account`, `currency`, `amount`, `date`, `period`, `phone`,
`none` — covers the shapes a banking form actually has.

---

## Stage 2 providers

Set `search.llm.provider` (env `LLM_PROVIDER`):

| Value | Use for | Notes |
|---|---|---|
| `heuristic` *(default)* | local dev, CI | No model, no network. Regex + slot metadata. Keeps the pipeline runnable and testable offline. |
| `ollama` | on-prem / air-gapped | `qwen2.5:3b-instruct` or `phi-3-mini`. Slot schema passed as Ollama's `format`, which constrains decoding to conforming JSON. Also fits any vLLM front-end exposing the same API. |
| `anthropic` | hosted | Claude Haiku (`claude-haiku-4-5`). Extraction is expressed as a **tool call** whose input schema is the feature's slots, so output is structured, not prose. Credentials resolve from `ANTHROPIC_API_KEY` or an `ant auth login` profile — never hardcoded. |

All three go through the same prompt template, the same JSON schema, and the
same defensive parser. Swapping providers is a config change.

**Nothing the model returns is trusted.** `SlotJsonParser` strips code fences,
digs the object out of prose, repairs trailing commas and smart quotes, drops
any key the feature doesn't declare, enforces enums, and coerces types. A
malformed response yields an empty result and a navigation outcome — never an
exception, and never a wrong pre-fill. Stage 3 then re-normalises every value
deterministically, so a wrong model output becomes a blank field rather than a
plausible-looking wrong one.

---

## How the constraints are enforced

> *No transaction of any kind may be executed directly from search results.*

`SearchResponse.action` has four possible values and none of them is execute.
No service in this codebase writes to an account, and the only write anywhere
is an optional `search_audit` row. The demo UI's Continue button hands values
to a screen; it does not post them.

> *Stage 2 must only trigger when Stage 1 confidence is high AND generic signal
> detection finds extractable content — never on every keystroke.*

Four independent gates, all of which must open:

1. `feature.acceptsParameters()` — `has_params` **and** a non-empty `slots`
   array. Re-checked inside `SlotExtractionService` as well as at the call
   site, because it's a safety property worth asserting twice.
2. `SignalReport.shouldExtract()` — weighted score ≥ threshold.
3. **Feature certainty** — either `confidence >= extraction-threshold` (0.62)
   *or* a clear margin over the runner-up (`extraction-margin`, 0.15). See
   below for why the margin clause is necessary rather than a loophole.
4. `allow_extraction` on the request — and `GET /suggest` **hard-codes it to
   false**, so no client-side mistake can put an LLM call on the keystroke path.

The signal detector is where the cost control lives, so its behaviour is
pinned by tests: `"transfer"`, `"trf"`, `"tran"`, `"send money"` and
`"i want to send money please"` all score below threshold;
`"transfer to mom's 10000 usd"` scores 4.

### Two ranking problems worth knowing about

Both were found by running the pipeline, not by reading it — and both are the
kind that a green test suite would happily hide.

**1. Similarity is diluted by the parameter payload.** `transfer to mom's
10000 usd` shares only one word with anything in the registry; the rest is
payload no feature has ever seen. Every similarity channel drops accordingly —
the query scores 0.53, below the extraction threshold — so the queries that
*most* need Stage 2 were the ones being gated out of it. Worse, plain
`ts_rank_cd` initially ranked **Transfer Receipt** above **Transfer Money**,
because it rewards a short document that repeats "transfer".

Two fixes, both in `HybridSearchService`:

- A **containment channel**: does the query literally contain one of the
  feature's naming terms? Payload can dilute a similarity score; it cannot
  dilute this. Terms score by length and IDF, so the long phrase
  "where my money" (Spending Insights) beats the bare keyword "money"
  (Transfer) on *where did my money go*, and tokens need not be adjacent, so
  "block my card" still matches the term "block card".
- A **margin-based gate**: "Stage 1 confidence is high" really means "we know
  which feature this is". A clear gap to the runner-up measures that directly,
  without the length bias.

**2. `ivfflat` silently lost the right answer.** At `lists = 8` with the
default `probes = 1`, a query searches one partition — and `send 250 usd to
landlord` came back with the transfer feature scoring **0.0** on the vector
channel while topping every lexical one. Not an error, just a quietly missing
candidate. `db/03_indexes.sql` uses **HNSW** instead: no training data, far
better default recall, and irrelevant build cost at this size.

> *All slot definitions and feature metadata must live in the features
> table/config, not in application code.*

There is no feature id in any `switch`, `if`, or prompt file. Grep for
`"transfer"` outside `db/` and the tests: the only hits are documentation.

---

## Load testing

`loadtest.py` is a dependency-free async load generator (keep-alive, percentile
reporting). `{i}` in a body is replaced per request, which matters: the
embedding service caches by query text, so a fixed query measures the cache
rather than the model.

```bash
python3 loadtest.py --url http://localhost:8080/api/search --method POST \
  --body '{"query":"check balance {i}"}' --total 10000 --concurrency 512
```

### What 10,000 requests found

The embedding model is the only remote call on the hot path, and it was
**capping the entire search API at ~56 rps** — at every concurrency level,
because the API simply inherited the model's ceiling.

| | before | after |
|---|---|---|
| `/embed`, unique queries, c=8 | 59 rps · p50 132ms | **170 rps · p50 45ms** |
| `/embed`, unique queries, c=32 | 44 rps · p50 722ms | **158 rps · p50 196ms** |
| `/api/search`, c=32 | 56 rps · p50 565ms | **637 rps · p50 24ms** |
| `/api/search`, c=128 | 56 rps · p50 2243ms | **798 rps · p50 133ms** |
| `/api/search`, c=512 | 56 rps · p50 9020ms | **851 rps · p50 535ms** |

10,000 *simultaneous* connections, one request each: **10,000/10,000 OK, no
errors, 1001 rps, p50 2.0s** — Tomcat on virtual threads accepts the lot
without refusing connections.

Two causes, both worth knowing:

**A read timeout does not bound a queue.** The embedding client had a 400ms
read timeout, and it never once fired while p50 sat at 9 seconds. A read
timeout starts when the request reaches the wire; time spent waiting for a
connection from the client's pool is invisible to it. Measured directly
against a deliberately-slow server: 11.4 rps at concurrency 128 with a 400ms
budget — about 4.6 calls actually in flight, the rest queued inside the client.
`EmbeddingClient` now bounds the **whole operation** (`Future.get(deadline)`),
adds a **bulkhead** so excess requests skip the vector channel instead of
queueing for it, and a **circuit breaker** so a sick service costs one timeout
rather than one per request. That is what makes "degrade to lexical-only"
actually happen — it fired twice during the c=512 run and recovered on its own.

**One CPU-bound worker gets slower with concurrency.** Throughput *fell* from
59 to 44 rps going from 8 to 32 clients: torch intra-op threads contending, not
saturation. The service now pins one thread per worker and scales out with
processes (`OMP_NUM_THREADS=1`, `WORKERS=4`), which is where the ~3× came from.

Cache hits serve **1538 rps at p50 63ms** versus ~45–170 rps on a miss, so the
per-query LRU is load-bearing for typeahead, where prefixes repeat heavily.

### Where embedding time actually goes

bge-small is a 12-layer, 33.4M-parameter BERT. Per request, on one core:

| step | p50 | share |
|---|---|---|
| tokenize | 0.09ms | 0.3% |
| **transformer forward pass** | **24.5ms** | **99.7%** |
| pool + L2 normalise | ~1ms | — |

There is nothing to optimise around it — the forward pass *is* the request.
The levers are batching, more processes, or a smaller/quantised model.

Cost is linear in sequence length, which is where the tail came from:

| input | tokens | p50 |
|---|---|---|
| `trf` | 4 | 16ms |
| `transfer to mom's 10000 usd` | 10 | 19ms |
| 512 chars (the API's max query) | 92 | 89ms |
| 2000 chars (the service's old max) | 361 | **572ms** |

Two tail-latency fixes followed:

- **The model is preloaded at startup.** Loading takes **5.4s** and happens per
  worker, so lazy loading meant the first request to each of 4 workers paid it
  after every deploy — by far the largest latency in the system. The port now
  opens only once the model is resident: first request after a fresh deploy is
  95ms, not 5.4s.
- **Sequence length is capped at 128 tokens and text at 512 chars**, matching
  the API's own limit. Feature documents top out at 60 tokens and queries are
  shorter, so nothing real is truncated — verified by
  `check_embeddings.py` still reporting cosine 1.00000 against the stored
  vectors. A 2000-char request is now rejected in 2.5ms instead of occupying a
  worker for half a second.

Note `precompute_embeddings.py` loads its own model and is unaffected by the
service's cap; the two stay consistent because no document reaches 128 tokens.

**Still on the table** (not done here): micro-batching would roughly double
throughput per core — batch-of-32 costs 13.0ms/query versus 26.9ms one at a
time — and ONNX or int8 quantisation would cut the forward pass further.

### Embedding correctness

`embedding-service/check_embeddings.py` — 8 checks, all passing. Beyond shape
and normalisation it verifies the part that fails *silently*: bge is asymmetric,
so queries get the retrieval prefix and documents must not. It confirms the
vectors `precompute_embeddings.py` stored match the live service's document
form (cosine 1.00000) and not its query form, and that the prefix genuinely
improves retrieval on this registry (4/5 probes vs 2/5 without).

```bash
python3 embedding-service/check_embeddings.py
```

## Performance notes

The budgets are &lt;100ms for navigation and &lt;400ms for command queries.

- **Virtual threads** (`spring.threads.virtual.enabled=true`) — the request
  path chains four blocking I/O calls; virtual threads scale that on ordinary
  blocking code instead of reactive plumbing.
- **Stage 1 fans out.** The lexical SQL and the query-embedding round trip run
  concurrently on a virtual-thread executor; only the pgvector query waits on
  the embedding.
- **The registry is cached in memory.** Retrieval SQL returns ids and scores;
  full rows are hydrated locally, and the containment channel scans them
  directly. 67 rows costs nothing to hold or scan.
- **Fail-soft vector search.** If the embedding service is slow, saturated, or
  down, its weight is dropped and the remaining weights are *renormalised*, so
  confidence stays on the same 0–1 scale and thresholds keep meaning what they
  meant. A degraded search beats a 500. Enforced by a deadline that covers
  queue time, a bulkhead, and a circuit breaker — see Load testing above for
  why a read timeout alone was not enough.
- **Constrained decoding** keeps Stage 2 generation to a few dozen tokens,
  which is what makes 400ms reachable across a network hop.

---

## Layout

```
db/                   01_schema.sql · 02_seed.sql (67 features) · 03_indexes.sql
embedding-service/    FastAPI /embed + offline precompute_embeddings.py
backend/
  config/             SearchProperties (every threshold and weight) · HTTP clients
  registry/           FeatureRepository (the 3 retrieval channels) · FeatureRegistry cache
  search/             EmbeddingClient · HybridSearchService · SearchOrchestrator
  signal/             SignalDetector          ← the LLM gate
  extraction/         PromptBuilder · SlotJsonParser · {Heuristic,Ollama,Anthropic}SlotExtractor
  resolution/         PayeeResolver · AccountResolver · ScalarSlotResolvers
  api/                SearchController · ApiExceptionHandler
frontend/index.html   Typeahead + pre-filled form demo
```

## Tests

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

38 unit tests, no database or model required. They cover the signal gate, the
defensive JSON parsing of hostile model output, tsquery sanitisation, prompt
genericity, Stage-3 normalisation, and the containment-ranking behaviour that
fixed the two problems above.

---

## Production notes

Things this repo demonstrates but a real deployment must change:

- **`user_id` comes from the request body.** That is demo-only. Take it from
  the authenticated principal — Stage 3 queries are scoped by it, and a search
  feature must never be able to enumerate another customer's payees.
- **CORS is `*`** (`search.cors-origins`). Pin it.
- **No authn/authz on the endpoints.** Put them behind the existing gateway.
- **HNSW is sized for ~10^2 features.** Revisit the index choice (and
  `hnsw.ef_search`) only if the registry grows by orders of magnitude.
- **Prompt/PII.** Queries are sent to the Stage-2 provider. With
  `provider: anthropic` that is a network egress of user-typed text — which is
  exactly why `ollama` exists as a first-class option here.
