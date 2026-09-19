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
  -e LLM_PROVIDER=heuristic \
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
| `ANTHROPIC_API_KEY` | Required only when `LLM_PROVIDER=anthropic` |

Edit `k8s/configmap.yaml` to switch the LLM provider or embedding service URL:

| Key | Default | Options |
|-----|---------|---------|
| `LLM_PROVIDER` | `heuristic` | `heuristic`, `ollama`, `anthropic` |
| `EMBEDDING_ENABLED` | `true` | `true`, `false` |
| `EMBEDDING_BASE_URL` | `http://embedding-svc:8000` | URL of your embedding service |

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
| `LLM_PROVIDER` | `heuristic` | Slot extraction backend (`heuristic` / `ollama` / `anthropic`) |
| `ANTHROPIC_API_KEY` | — | Required when `LLM_PROVIDER=anthropic` |
| `ANTHROPIC_MODEL` | `claude-haiku-4-5` | Claude model for slot extraction |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `qwen2.5:3b-instruct` | Ollama model for slot extraction |
| `EMBEDDING_ENABLED` | `true` | Enable vector search channel |
| `EMBEDDING_BASE_URL` | `http://localhost:8000` | Embedding service URL |

---

## k8s Manifest Overview

```
k8s/
├── secret.yaml      # Sensitive env vars (DB credentials, API keys)
├── configmap.yaml   # Non-sensitive config (LLM provider, embedding URL)
├── deployment.yaml  # Backend pod spec with liveness/readiness probes
├── service.yaml     # LoadBalancer — exposes port 8080 with a local IP
└── postgres.yaml    # Postgres 16 pod + ClusterIP service + PVC + init script
```
