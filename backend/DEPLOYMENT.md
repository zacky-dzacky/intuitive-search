# Backend Deployment Guide

Covers running the backend as a Docker container and deploying it to a local Kubernetes cluster via OrbStack.

---

## Prerequisites

- [OrbStack](https://orbstack.dev/) (provides Docker and Kubernetes)
- `kubectl` (bundled with OrbStack)
- A running PostgreSQL instance (see [Database](#database) below)

---

## Docker

### Build the image

```bash
cd backend
docker build -t intuitive-search-service:0.0.1 .
```

The Dockerfile uses a two-stage build:
- **Stage 1** (`maven:3.9-eclipse-temurin-21`) — downloads dependencies and compiles the JAR.
- **Stage 2** (`eclipse-temurin:21-jre`) — copies only the JAR into a minimal runtime image.

Dependencies are cached in a separate layer from source code, so rebuilds after code-only changes are fast.

### Run locally with Docker

```bash
docker run --rm \
  -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/banksearch \
  -e DATABASE_USER=bank \
  -e DATABASE_PASSWORD=bank \
  -e LLM_PROVIDER=openai \
  -e OPENAI_BASE_URL=https://mbb-litellm-proxy-sb-sea.southeastasia.cloudapp.azure.com/v1 \
  -e OPENAI_CHAT_MODEL=gpt-5.4-mini \
  -e OPENAI_REASONING_EFFORT=low \
  -e OPENAI_API_KEY=sk-... \
  -e OPENAI_EMBEDDING_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai \
  -e OPENAI_EMBEDDING_API_KEY=<gemini-key> \
  intuitive-search-service:0.0.1
```

Health check: `http://localhost:8080/actuator/health`

---

## Kubernetes (OrbStack)

OrbStack Kubernetes uses **containerd** separately from Docker's image store — images built with `docker build` are not automatically visible to pods. The solution is a local Docker registry that both Docker and the cluster can reach.

### 1. Enable and start Kubernetes

In the OrbStack app: **Settings → Kubernetes → Enable**, then start the cluster:

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

> **Troubleshooting:** If `kubectl get nodes` returns connection refused, check
> `~/.orbstack/config/kubelet.conf`. It must contain valid YAML (e.g. `{}`), not
> shell commands. Corrupt content there causes the k8s service to exit on startup.

### 2. Start a local Docker registry (one-time)

```bash
docker run -d -p 5000:5000 --restart=always --name local-registry registry:2
```

OrbStack is already configured to treat `localhost:5000` and `host.docker.internal:5000` as trusted insecure registries via `~/.orbstack/config/docker.json`.

### 3. Configure secrets and environment

Edit `k8s/secret.yaml` with your credentials before the first deploy:

| Key | Description |
|-----|-------------|
| `DATABASE_URL` | JDBC URL for PostgreSQL |
| `DATABASE_USER` | Database username |
| `DATABASE_PASSWORD` | Database password |
| `OPENAI_API_KEY` | Azure AI Foundry key (via the LiteLLM proxy) for Stage-2 chat. Also serves embeddings unless `OPENAI_EMBEDDING_API_KEY` is set |
| `OPENAI_EMBEDDING_API_KEY` | Gemini key for embeddings — the Foundry key is chat-only for now |
| `ANTHROPIC_API_KEY` | Required only when `LLM_PROVIDER=anthropic` |

Edit `k8s/configmap.yaml` to switch provider or models. **Moving between providers is these values and the key — no image rebuild.** The configmap ships with Stage 2 on Foundry and embeddings on Gemini, because the Foundry sandbox key is scoped to chat models only (`gpt-5.4-mini`, `model-router`; `/embeddings` returns 403):

| Key | Azure AI Foundry (LiteLLM proxy) | Gemini |
|-----|----------------------------------|--------|
| `LLM_PROVIDER` | `openai` | `openai` |
| `OPENAI_BASE_URL` | `https://mbb-litellm-proxy-sb-sea.southeastasia.cloudapp.azure.com/v1` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `OPENAI_CHAT_MODEL` | `gpt-5.4-mini` (or `model-router`) | `gemini-3.6-flash` |
| `OPENAI_REASONING_EFFORT` | `low` | `low` |
| `OPENAI_EMBEDDING_BASE_URL` | — (no embedding model on the key yet) | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `OPENAI_EMBEDDING_MODEL` | — | `gemini-embedding-001` |
| `EMBEDDING_DIMENSIONS` | `768` | `768` |

Once the Foundry key covers an embedding deployment, set `OPENAI_EMBEDDING_MODEL` to that deployment name and drop `OPENAI_EMBEDDING_BASE_URL` / `OPENAI_EMBEDDING_API_KEY` so embeddings fall back to the chat endpoint and key. Without any embedding key, set `EMBEDDING_ENABLED: "false"` — search then runs lexical-only.

### 4. Deploy

Use the deploy script — it builds, pushes to the local registry, applies manifests, and waits for rollout:

```bash
chmod +x deploy-local.sh
./deploy-local.sh
```

Re-run this on every code change.

**What the script does internally:**

```bash
# Build
docker build -t intuitive-search-service:0.0.1 .

# Push to local registry (accessible by the cluster via host.docker.internal:5000)
docker tag intuitive-search-service:0.0.1 localhost:5000/intuitive-search-service:0.0.1
docker push localhost:5000/intuitive-search-service:0.0.1

# Apply manifests and roll out
kubectl apply -f k8s/
kubectl rollout restart deployment/intuitive-search
kubectl rollout status deployment/intuitive-search
```

### 5. Verify the deployment

```bash
# Watch pod status
kubectl get pods -w

# Stream logs
kubectl logs -f deploy/intuitive-search

# Check service and get external IP
kubectl get svc intuitive-search-svc
```

OrbStack assigns a real local IP to `LoadBalancer` services immediately (no `<pending>` state). Use the `EXTERNAL-IP` to reach the backend:

```bash
curl http://<EXTERNAL-IP>:8080/actuator/health
```

### 6. Tear down

```bash
kubectl delete -f k8s/
```

---

## Database

The backend requires PostgreSQL with the `pgvector` and `pg_trgm` extensions.

### Postgres pod in Kubernetes (default)

`k8s/postgres.yaml` ships a ready-to-use Postgres pod. It is applied automatically when you run `kubectl apply -f k8s/`.

What it provisions:

| Resource | Details |
|----------|---------|
| Image | `pgvector/pgvector:pg16` — Postgres 16 with pgvector pre-installed |
| Database | `banksearch` |
| User / Password | `bank` / `bank` |
| Extensions | `pg_trgm` and `vector` enabled via init script on first boot |
| Persistence | 1 Gi `PersistentVolumeClaim` — data survives pod restarts |
| Service | `postgres-svc:5432` (ClusterIP — internal to the cluster) |

The `DATABASE_URL` in `k8s/secret.yaml` is pre-configured to point at this service:

```
jdbc:postgresql://postgres-svc:5432/banksearch
```

Wait for Postgres to be ready before the backend starts:

```bash
kubectl rollout status deployment/postgres
kubectl logs deploy/postgres   # should end with "database system is ready to accept connections"
```

### Option — Host Postgres

If you prefer to use a Postgres instance running on your Mac, update `DATABASE_URL` in `k8s/secret.yaml`:

```
jdbc:postgresql://host.docker.internal:5432/banksearch
```

Ensure `pg_trgm` and `vector` extensions are enabled in that database, and that `pg_hba.conf` allows connections from the OrbStack network.

---

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/banksearch` | PostgreSQL JDBC URL |
| `DATABASE_USER` | `bank` | Database username |
| `DATABASE_PASSWORD` | `bank` | Database password |
| `SERVER_PORT` | `8080` | HTTP port |
| `LLM_PROVIDER` | `heuristic` | Slot extraction backend (`openai` / `heuristic` / `ollama` / `anthropic`) |
| `OPENAI_BASE_URL` | — | OpenAI-compatible endpoint (Azure AI Foundry via LiteLLM proxy, Gemini, OpenAI) |
| `OPENAI_API_KEY` | — | Its API key |
| `OPENAI_CHAT_MODEL` | `gpt-5.4-mini` | Chat model / deployment for slot extraction |
| `OPENAI_AUTH_HEADER` | `bearer` | `bearer` or `api-key` (legacy Azure gateways) |
| `OPENAI_REASONING_EFFORT` | — | `none`/`low`/`medium`/`high` for thinking models; omit for non-reasoning deployments |
| `LLM_TIMEOUT_MS` | `2500` | Stage-2 read timeout; raise for a hosted tier with a long tail (Foundry via proxy: ~1.3 s; Gemini free tier: 1–11 s) |
| `ANTHROPIC_API_KEY` | — | Required when `LLM_PROVIDER=anthropic` |
| `ANTHROPIC_MODEL` | `claude-haiku-4-5` | Claude model for slot extraction |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `qwen2.5:3b-instruct` | Ollama model for slot extraction |
| `EMBEDDING_ENABLED` | `true` | Enable vector search channel |
| `OPENAI_EMBEDDING_MODEL` | `gemini-embedding-001` | Embedding model / deployment |
| `OPENAI_EMBEDDING_BASE_URL` | `$OPENAI_BASE_URL` | Only if embeddings are served from a different endpoint (Gemini, while the Foundry key is chat-only) |
| `OPENAI_EMBEDDING_API_KEY` | `$OPENAI_API_KEY` | Only if that endpoint needs a different key |
| `EMBEDDING_DIMENSIONS` | `768` | Vector size (sent to the provider and enforced; Lucene caps at 1024) |
| `EMBEDDING_TIMEOUT_MS` | `800` | Per-query embedding deadline on the hot path |

---

## k8s Manifest Overview

```
k8s/
├── secret.yaml      # Sensitive env vars (DB credentials, API keys)
├── configmap.yaml   # Non-sensitive config (providers, endpoints, model names)
├── deployment.yaml  # Backend pod spec with liveness/readiness probes
├── service.yaml     # LoadBalancer — exposes port 8080 with a local IP
└── postgres.yaml    # Postgres 16 pod + ClusterIP service + PVC + init script
```
