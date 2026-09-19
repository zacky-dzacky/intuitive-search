FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models \
    EMBEDDING_MODEL=BAAI/bge-small-en-v1.5

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py precompute_embeddings.py ./

# Bake the model into the image so the first request isn't a cold download.
RUN python -c "from sentence_transformers import SentenceTransformer; \
    import os; SentenceTransformer(os.environ['EMBEDDING_MODEL'], device='cpu')"

EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8000/health || exit 1

# Embedding is CPU-bound. One worker letting torch use every core does *worse*
# under concurrency than several single-threaded workers: measured throughput
# fell from 59 to 44 req/s going from 8 to 32 concurrent clients, which is
# thread contention, not saturation. Pin each worker to one thread and scale
# out with processes instead. WORKERS should be <= physical cores; each one
# loads its own copy of the model (~130MB for bge-small).
ENV OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    TORCH_NUM_THREADS=1 \
    WORKERS=4

CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port 8000 --workers ${WORKERS}"]
