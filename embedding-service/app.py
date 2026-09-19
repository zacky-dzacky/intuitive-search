"""
Embedding microservice for Stage 1 vector search.

Small, CPU-friendly, embedding-only model. Only the *live user query* is
embedded here at request time; feature embeddings are precomputed offline by
precompute_embeddings.py and stored in pgvector.

    uvicorn app:app --host 0.0.0.0 --port 8000

Endpoints
    GET  /health   -> readiness + model metadata
    POST /embed    -> {"text": "..."}            -> {"embedding": [...]}
    POST /embed/batch -> {"texts": ["...", ...]} -> {"embeddings": [[...], ...]}
"""

from __future__ import annotations

import os
import threading
import time
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

MODEL_NAME = os.getenv("EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5")
# bge-* models were trained with an asymmetric retrieval prefix on the query
# side only. Leave empty for all-MiniLM-L6-v2.
QUERY_PREFIX = os.getenv(
    "QUERY_PREFIX", "Represent this sentence for searching relevant passages: "
)
MAX_BATCH = int(os.getenv("MAX_BATCH", "256"))
# Cost is linear in sequence length: 10 tokens ~19ms, 92 ~89ms, 361 ~572ms on
# one core. Feature documents top out at 60 tokens and search queries are
# shorter still, so capping here bounds the worst case without truncating
# anything real. Without it, one long request occupies a worker for half a
# second — and the API's own limit is 512 characters (~92 tokens).
MAX_SEQ_LENGTH = int(os.getenv("MAX_SEQ_LENGTH", "128"))
# Matches the API's @Size(max = 512) on the query field.
MAX_TEXT_CHARS = int(os.getenv("MAX_TEXT_CHARS", "512"))


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Load before the port accepts traffic. The model takes ~5.4s to load and
    # loading is per-worker, so lazy loading would make the first request to
    # each of N workers pay it — the largest tail latency in the whole system,
    # and it would recur after every deploy.
    get_model()
    yield


app = FastAPI(title="Intuitive Search — Embedding Service", version="1.0.0",
              lifespan=lifespan)

_model = None
_model_lock = threading.Lock()


def get_model():
    """Lazily load the sentence-transformers model (thread-safe, once)."""
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                import torch
                from sentence_transformers import SentenceTransformer

                # Scale out with worker processes, not intra-op threads.
                # Letting every concurrent request fan out across all cores
                # produces contention that reduces total throughput.
                torch.set_num_threads(int(os.getenv("TORCH_NUM_THREADS", "1")))
                model = SentenceTransformer(MODEL_NAME, device="cpu")
                model.max_seq_length = min(model.max_seq_length, MAX_SEQ_LENGTH)
                _model = model
    return _model


class EmbedRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TEXT_CHARS)
    # Queries get the bge retrieval prefix; documents (feature descriptions)
    # must not, or the two vector spaces drift apart.
    is_query: bool = True


class EmbedResponse(BaseModel):
    embedding: List[float]
    model: str
    dimensions: int
    took_ms: float


class BatchEmbedRequest(BaseModel):
    texts: List[str] = Field(..., min_length=1)
    is_query: bool = False


class BatchEmbedResponse(BaseModel):
    embeddings: List[List[float]]
    model: str
    dimensions: int
    took_ms: float


def _prepare(texts: List[str], is_query: bool) -> List[str]:
    if is_query and QUERY_PREFIX:
        return [QUERY_PREFIX + t for t in texts]
    return list(texts)


def _encode(texts: List[str], is_query: bool) -> List[List[float]]:
    model = get_model()
    vectors = model.encode(
        _prepare(texts, is_query),
        normalize_embeddings=True,  # cosine distance == dot product
        batch_size=32,
        show_progress_bar=False,
    )
    return [v.tolist() for v in vectors]


@lru_cache(maxsize=4096)
def _encode_query_cached(text: str) -> tuple:
    """Short-lived cache: search-as-you-type repeats the same prefixes a lot."""
    return tuple(_encode([text], is_query=True)[0])


@app.get("/health")
def health():
    try:
        model = get_model()
        dims = model.get_sentence_embedding_dimension()
        return {"status": "ok", "model": MODEL_NAME, "dimensions": dims,
                "max_seq_length": model.max_seq_length}
    except Exception as exc:  # pragma: no cover - startup diagnostics
        raise HTTPException(status_code=503, detail=f"model unavailable: {exc}")


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    started = time.perf_counter()
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must not be blank")
    vector = (
        list(_encode_query_cached(text))
        if req.is_query
        else _encode([text], is_query=False)[0]
    )
    return EmbedResponse(
        embedding=vector,
        model=MODEL_NAME,
        dimensions=len(vector),
        took_ms=round((time.perf_counter() - started) * 1000, 2),
    )


@app.post("/embed/batch", response_model=BatchEmbedResponse)
def embed_batch(req: BatchEmbedRequest):
    if len(req.texts) > MAX_BATCH:
        raise HTTPException(
            status_code=413, detail=f"batch too large (max {MAX_BATCH})"
        )
    started = time.perf_counter()
    vectors = _encode([t.strip() for t in req.texts], is_query=req.is_query)
    return BatchEmbedResponse(
        embeddings=vectors,
        model=MODEL_NAME,
        dimensions=len(vectors[0]) if vectors else 0,
        took_ms=round((time.perf_counter() - started) * 1000, 2),
    )
