# Embedding Service Deployment Guide

Covers running the embedding service as a Docker container and deploying it to a local Kubernetes cluster via OrbStack.

---

## Prerequisites

- [OrbStack](https://orbstack.dev/) (provides Docker and Kubernetes)
- `kubectl` (bundled with OrbStack)
- A local Docker registry running on port 5000 (see the backend DEPLOYMENT.md for setup)

---

## Docker

### Build the image

```bash
cd embedding-service
docker build -t embedding-service:0.0.1 .
```

The Dockerfile:
- Installs Python dependencies (`fastapi`, `uvicorn`, `sentence-transformers`, `torch`).
- **Bakes the model into the image** at build time (`BAAI/bge-small-en-v1.5`, ~130 MB) so pods never cold-download on first request.

### Run locally with Docker

```bash
docker run --rm \
  -p 8000:8000 \
  embedding-service:0.0.1
```

Health check: `http://localhost:8000/health`

Override the defaults with environment variables if needed:

```bash
docker run --rm \
  -p 8000:8000 \
  -e WORKERS=2 \
  -e EMBEDDING_MODEL=BAAI/bge-small-en-v1.5 \
  embedding-service:0.0.1
```

---

## Kubernetes (OrbStack)

OrbStack Kubernetes uses **containerd** separately from Docker's image store — images built with `docker build` are not automatically visible to pods. Push to the local registry so the cluster can pull them.

### 1. Enable and start Kubernetes

In the OrbStack app: **Settings → Kubernetes → Enable**, then:

```bash
orbctl start k8s
kubectl config use-context orbstack
```

Verify the node is ready:

```bash
kubectl get nodes
# NAME       STATUS   ROLES           VERSION
# orbstack   Ready    control-plane   v1.35.x
```

### 2. Start a local Docker registry (one-time)

```bash
docker run -d -p 5000:5000 --restart=always --name local-registry registry:2
```

OrbStack treats `localhost:5000` and `host.docker.internal:5000` as trusted insecure registries.

### 3. Build and push the image

```bash
docker build -t embedding-service:0.0.1 .
docker tag embedding-service:0.0.1 localhost:5000/embedding-service:0.0.1
docker push localhost:5000/embedding-service:0.0.1
```

### 4. Deploy

```bash
kubectl apply -f k8s/
kubectl rollout status deployment/embedding-service
```

Re-run the build, push, and rollout restart on every code change:

```bash
docker build -t embedding-service:0.0.1 . \
  && docker tag embedding-service:0.0.1 localhost:5000/embedding-service:0.0.1 \
  && docker push localhost:5000/embedding-service:0.0.1 \
  && kubectl rollout restart deployment/embedding-service \
  && kubectl rollout status deployment/embedding-service
```

### 5. Verify the deployment

```bash
# Watch pod status
kubectl get pods -w

# Stream logs
kubectl logs -f deploy/embedding-service

# Check the service
kubectl get svc embedding-service-svc
```

The service is `ClusterIP` (internal only). To test it from outside the cluster, use port-forward:

```bash
kubectl port-forward svc/embedding-service-svc 8000:8000
curl http://localhost:8000/health
curl -X POST http://localhost:8000/embed \
  -H "Content-Type: application/json" \
  -d '{"text": "transfer money"}'
```

### 6. Tear down

```bash
kubectl delete -f k8s/
```

---

## Precomputing Feature Embeddings

`precompute_embeddings.py` is an offline batch job that embeds every feature in the database and writes the vector into `features.embedding`. Run it once after seeding the database, and again whenever a feature row is added or its description changes.

### Run against a local Postgres

```bash
cd embedding-service
pip install -r requirements.txt

# Only rows with NULL embedding (default)
python precompute_embeddings.py

# Re-embed everything
python precompute_embeddings.py --all
```

### Run against the in-cluster Postgres

Port-forward the Postgres service from another terminal, then run the script:

```bash
kubectl port-forward svc/postgres-svc 5432:5432
```

```bash
DATABASE_URL=postgresql://bank:bank@localhost:5432/banksearch \
  python precompute_embeddings.py
```

After writing new embeddings, rebuild the ivfflat index:

```bash
psql postgresql://bank:bank@localhost:5432/banksearch -f db/03_indexes.sql
```

---

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `EMBEDDING_MODEL` | `BAAI/bge-small-en-v1.5` | Sentence-transformers model name |
| `QUERY_PREFIX` | `Represent this sentence for searching relevant passages: ` | Prepended to queries (bge asymmetric retrieval) |
| `MAX_BATCH` | `256` | Maximum texts per `/embed/batch` request |
| `MAX_SEQ_LENGTH` | `128` | Token cap per input (bounds worst-case latency) |
| `MAX_TEXT_CHARS` | `512` | Character cap per input text |
| `WORKERS` | `4` | Number of uvicorn worker processes |
| `OMP_NUM_THREADS` | `1` | OpenMP threads per worker |
| `MKL_NUM_THREADS` | `1` | MKL threads per worker |
| `TORCH_NUM_THREADS` | `1` | PyTorch intra-op threads per worker |
| `DATABASE_URL` | `postgresql://bank:bank@localhost:5432/banksearch` | Used only by `precompute_embeddings.py` |

> **Worker tuning:** Each worker loads its own copy of the model (~130 MB). Scale `WORKERS` up to the number of physical CPU cores available. Increasing intra-op thread counts (`TORCH_NUM_THREADS`) beyond 1 hurts throughput under concurrency due to thread contention.

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Readiness check — returns model name and embedding dimensions |
| `POST` | `/embed` | Embed a single text string |
| `POST` | `/embed/batch` | Embed a list of texts (max 256) |

### `/embed` request/response

```json
// Request
{ "text": "transfer money", "is_query": true }

// Response
{ "embedding": [...], "model": "BAAI/bge-small-en-v1.5", "dimensions": 384, "took_ms": 19.4 }
```

Set `is_query: false` when embedding feature documents — the bge retrieval prefix must only be applied to queries, not documents.

---

## k8s Manifest Overview

```
k8s/
├── deployment.yaml  # Pod spec: 4 workers, CPU/memory limits, liveness/readiness probes
└── service.yaml     # ClusterIP — internal only, reachable at embedding-service-svc:8000
```
