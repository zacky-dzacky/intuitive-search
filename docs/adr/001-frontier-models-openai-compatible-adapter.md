# ADR-001 — Frontier models through one OpenAI-compatible adapter

**Status:** accepted · 2026-09-19
**Prompted by:** stakeholder review — *"don't use open models; the office will commit to frontier models, most likely on Azure AI Foundry."*

## Context

The proof of concept ran two open-weight models:

| Stage | Model | How it was served |
|---|---|---|
| 1 — vector channel | `BAAI/bge-small-en-v1.5` (384-dim) | our own FastAPI + sentence-transformers service, on-cluster |
| 2 — slot extraction | `qwen2.5:3b-instruct` | Ollama |

The office direction is frontier models on Azure AI Foundry. The Foundry sandbox is not yet provisioned, so the work had to be done against another provider now in a way that makes the eventual switch trivial.

## What we checked

Azure AI Foundry's **v1 API** is used through the *standard* OpenAI client: `base_url = https://<resource>.openai.azure.com/openai/v1/` plus an API key — no dated `api-version`, no Azure-specific client, `/chat/completions` and `/embeddings` at the usual paths. Microsoft's own migration guidance is "swap `AzureOpenAI()` for `OpenAI()` and set `base_url`". Entra ID bearer tokens are also accepted on the same routes, for later.

Gemini exposes the identical wire format at `https://generativelanguage.googleapis.com/v1beta/openai/`, including `response_format` JSON schema for chat and `/embeddings` with `gemini-embedding-001`.

So the two providers we care about are the same adapter. Sources: [Azure OpenAI v1 API](https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle) · [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai).

## Decision

1. **One adapter, no provider SDK.** `OpenAiCompatibleSlotExtractor` (chat) and `OpenAiEmbeddingBackend` (embeddings) speak the OpenAI wire format over Spring's `RestClient`, the same way the Ollama extractor already did. An SDK would be the thing that makes the provider a code dependency; the wire format is four fields.
2. **Both models move, not just the visible one.** The embedding model is as much an "open model" as the LLM. The Python embedding service (`embedding-service/`, `bge-small`) is removed from the repository; there is no self-hosted embedding path.
3. **Switching providers is configuration only.** Gemini → Foundry is:

   | Env var | Gemini | Azure AI Foundry |
   |---|---|---|
   | `OPENAI_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` | `https://<resource>.openai.azure.com/openai/v1` |
   | `OPENAI_API_KEY` | Gemini key | Foundry key |
   | `OPENAI_CHAT_MODEL` | `gemini-3.6-flash` | chat deployment name |
   | `OPENAI_EMBEDDING_MODEL` | `gemini-embedding-001` | embedding deployment name |

   `OPENAI_AUTH_HEADER=api-key` exists for a Foundry gateway that still insists on the legacy header; default is `bearer`, which both providers accept. `OPENAI_REASONING_EFFORT` is sent only when set: Gemini 3.x models think before answering and took ~3.9 s per extraction by default versus ~1.6 s at `low` with identical output; a non-reasoning Foundry deployment must leave it unset or it rejects the request.
4. **Vector size is a setting, enforced.** `EMBEDDING_DIMENSIONS` (default 768) is sent as `dimensions` and enforced on the way back: a longer answer is truncated and re-normalised (the target models are Matryoshka-trained; that is what the provider does server-side when asked), a shorter one is rejected. See ADR-002 for why 768.
5. **The hot-path guards do not move.** Circuit breaker, bulkhead and whole-call deadline in `EmbeddingClient` wrap whichever backend is configured. A query-embedding cache is added in the JVM, because with a hosted model every miss is latency *and* money and typeahead repeats prefixes constantly.
6. **The catalogue is embedded by the backend**, not by an offline script (see ADR-002). One batch call on cold start; one call per changed row afterwards.

## Consequences

- Day one on the Foundry sandbox is three configuration values and a smoke run. **Acceptance criterion:** change only those values, run `smoke-search.sh`, every row passes.
- The per-query embedding deadline rises from 400 ms (on-cluster model) to 800 ms (hosted round trip). Typeahead repeats are served from cache and never wait on it; a miss that exceeds the deadline still degrades to lexical-only, as before.
- User-typed queries and feature texts leave the network to the provider. That was already true with `anthropic`; it is now the default. The provider choice is therefore a data-governance decision — Foundry inside the office tenancy is the intended home. `ollama` remains for an air-gapped deployment.
- `heuristic`, `ollama` and `anthropic` stay as `SlotExtractor` implementations; none is removed. `heuristic` keeps CI model-free.
- `embedding-service/` is deleted in full; the job its scripts did (build the documents, embed them, keep them current) moved into `FeatureIndexer`.
- **Dev/CI without a key is lexical-only.** `OpenAiEmbeddingBackend.isConfigured()` is false with no endpoint or key, `EmbeddingClient` treats embeddings as disabled, the vector weight is dropped and the rest renormalised. The unit suite never needs a model or the network.

## Verified against Gemini (2026-09-20, free tier)

- Cold start embedded all 68 features at 768 dims in one `/embeddings` call (3.1–3.7 s). Index reported `gemini-embedding-001`, 68/68 vectors.
- All ten smoke-corpus queries routed correctly; the four command queries reached `prefill_form` with correct slots through `gemini-3.5-flash` / `gemini-3.6-flash` (`reasoning_effort=low`), including normalisation Gemini did on its own ("march" → `2026-03`, "last month" → a date range).
- Two things the free tier taught us, both configuration rather than code:
  - **Model names rotate.** `gemini-2.5-flash` was already retired for new keys (404 pointing at `gemini-3.6-flash`). The 404 degraded to `navigate` as designed; the fix was one env var.
  - **Latency and quota are the tier's, not the adapter's.** Identical requests ranged 0.9 s → 11 s; chat is capped at 5 requests/min and 20/day *per model*, embeddings at 100 inputs/min. When quota ran out, the indexer built lexical-only, recorded the error, and re-embedded on its next refresh with no intervention. `LLM_TIMEOUT_MS` is therefore per-environment (10 s in the Gemini configmap); the <400 ms command budget from the spec is a paid-tier / Foundry conversation — a regional non-reasoning deployment is where that number lives.

## Not decided here

Which Foundry deployments the pilot uses (chat model, embedding model). The adapter does not care; the names are the only unknown.
