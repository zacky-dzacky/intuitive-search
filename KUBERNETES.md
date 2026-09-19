# Kubernetes Operations — Intuitive Search

OrbStack provides the local cluster. Postgres runs on the host machine (not in k8s).

---

## Services

| Service | Deployment name | Accessible at | Istio sidecar |
|---------|----------------|---------------|---------------|
| Backend (Spring) | `intuitive-search` | see [Routing Modes](#routing-modes) below | yes (2/2) |
| Admin dashboard (Next.js) | `admin-dashboard` | `k8s.orb.local:3001` | yes (2/2) |

---

## Routing Modes

The backend supports two external access patterns. Only one is active at a time — they are mutually exclusive because the Kubernetes Service type must differ between them.

---

### Mode 1 — Direct via LoadBalancer

OrbStack assigns the Service a real local IP, reachable from your Mac without any extra proxy layer.

```
Client (Mac)
  │
  ▼
intuitive-search-svc   (type: LoadBalancer, port 8080)
  │
  ▼
intuitive-search pod
```

**Service manifest** (`backend/k8s/service.yaml`):

```yaml
spec:
  type: LoadBalancer
  ports:
    - port: 8080
      targetPort: 8080
```

**Reachable at:** `http://k8s.orb.local:8080`

**Frontend API setting** (`frontend/index.html`):

```js
const API = new URLSearchParams(location.search).get('api')
    ?? (location.port === '8080' ? '' : 'http://k8s.orb.local:8080');
```

**When to use:** local development without Istio installed. Simple setup, no sidecar overhead.

---

### Mode 2 — Via Istio Ingress Gateway

All external traffic enters through the Istio ingress gateway. The backend Service is `ClusterIP` (not directly reachable from outside the cluster). Istio Gateway + VirtualService govern routing.

```
Client (Mac)
  │
  ▼
istio-ingressgateway   (LoadBalancer, port 80)   192.168.139.2
  │
  ▼  (Gateway + VirtualService: routes /api and /actuator/health)
intuitive-search-svc   (type: ClusterIP, port 8080)
  │
  ▼
intuitive-search pod   (2/2 — app container + Envoy sidecar)
```

**Service manifest** (`backend/k8s/service.yaml`):

```yaml
spec:
  type: ClusterIP
  ports:
    - port: 8080
      targetPort: 8080
```

**Istio resources required:**

| File | Kind | Purpose |
|------|------|---------|
| `backend/k8s/istio-gateway.yaml` | `Gateway` | Opens port 80 on the ingress pod |
| `backend/k8s/istio-virtualservice.yaml` | `VirtualService` | Routes `/api` and `/actuator/health` to `intuitive-search-svc` |

**Reachable at:** `http://192.168.139.2` (port 80)

**Frontend API setting** (`frontend/index.html`):

```js
const API = new URLSearchParams(location.search).get('api')
    ?? (location.port === '80' ? '' : 'http://192.168.139.2');
```

**When to use:** when Istio is installed (e.g. for canary releases, blue-green deployments, mTLS, or observability via Kiali/Jaeger).

---

### Switching between modes

**Switch to Mode 1 (LoadBalancer):**

```bash
# 1. Change service type
#    In backend/k8s/service.yaml set type: LoadBalancer
kubectl apply -f backend/k8s/service.yaml

# 2. Remove Istio routing resources (optional — they do nothing without a ClusterIP target)
kubectl delete -f backend/k8s/istio-virtualservice.yaml
kubectl delete -f backend/k8s/istio-gateway.yaml

# 3. Update frontend
#    In frontend/index.html change the API default to:
#    'http://k8s.orb.local:8080'
```

**Switch to Mode 2 (Istio Ingress):**

```bash
# 1. Change service type
#    In backend/k8s/service.yaml set type: ClusterIP
kubectl apply -f backend/k8s/service.yaml

# 2. Apply Istio routing resources
kubectl apply -f backend/k8s/istio-gateway.yaml
kubectl apply -f backend/k8s/istio-virtualservice.yaml

# 3. Update frontend
#    In frontend/index.html change the API default to:
#    'http://192.168.139.2'
```

---

## Service Mesh & mTLS

All three pods run with an Envoy sidecar injected by Istio (`2/2` containers). Internal traffic between services goes through the sidecar on both ends, which Kiali visualises as edges in the graph.

### Internal traffic

The backend's only in-cluster dependency is Postgres on the host; its model
calls (chat and embeddings) are egress to the hosted provider through the
sidecar. The admin dashboard calls the backend through `intuitive-search-svc`.

Services never talk to pod IPs directly — they resolve through the Kubernetes Service. The sidecars intercept that traffic and can apply mTLS, retries, and circuit-breaking transparently.

### mTLS mode

By default Istio runs in `PERMISSIVE` mode: sidecars accept both plain HTTP and mTLS, so the mesh works immediately without extra configuration. To check the current mode:

```bash
kubectl get peerauthentication -A
# No output → PERMISSIVE (namespace/mesh default)
```

To enforce strict mTLS for the `default` namespace (rejects plain-text connections):

```bash
kubectl apply -f - <<'EOF'
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: default
spec:
  mtls:
    mode: STRICT
EOF
```

Verify in Kiali: the edge between `admin-dashboard` and `intuitive-search-svc` should show a lock icon (mTLS) when strict mode is active.

---

## Observability (Kiali / Jaeger)

Kiali and Jaeger are installed alongside Istio. Access them via port-forward:

```bash
# Kiali
kubectl port-forward svc/kiali -n istio-system 20001:20001
# open http://localhost:20001

# Jaeger (distributed tracing)
kubectl port-forward svc/tracing -n istio-system 16686:80
# open http://localhost:16686
```

In Kiali's **Graph** view, select namespace `default` to see the live traffic topology. Clicking an edge shows protocol, request rate, error rate, and mTLS status for that connection.

---

## Bring up (first time)

### 1. Postgres (host machine, one-time)

```bash
PSQL=/opt/homebrew/opt/postgresql@16/bin/psql

$PSQL postgres -c "CREATE USER bank WITH PASSWORD 'bank';"
$PSQL postgres -c "CREATE DATABASE banksearch OWNER bank;"
$PSQL -d banksearch -f db/01_schema.sql
$PSQL -d banksearch -f db/02_seed.sql
```

### 2. Build and deploy all services

```bash
# Backend
cd backend && ./deploy-local.sh && cd ..

# Admin dashboard
cd admin-dashboard && ./deploy-local.sh && cd ..
```

The backend needs `OPENAI_API_KEY` in `backend/k8s/secret.yaml` (copy from
`secret.example.yaml`) and the provider/model values in `backend/k8s/configmap.yaml`
— Gemini by default, Azure AI Foundry by changing three values. On startup it
embeds the catalogue in one call and builds its search index in memory; there
is no backfill step and no vector index to create.

### 3. Upgrading a database from before the Lucene change (one-time)

```bash
psql -U bank -d banksearch -f db/05_drop_search_columns.sql
```

### 4. Admin dashboard migration (one-time)

```bash
psql -U bank -d banksearch -f admin-dashboard/db/04_admin.sql
```

---

## Redeploy (after code changes)

```bash
# Backend only
cd backend && ./deploy-local.sh

# Admin only
cd admin-dashboard && ./deploy-local.sh
```

---

## Check status

```bash
# Pod health
kubectl get pods

# Services and external IPs
kubectl get svc

# Logs (live)
kubectl logs -f deploy/intuitive-search
kubectl logs -f deploy/admin-dashboard
```

---

## Tear down

### Stop a single service (keeps manifests in cluster)

```bash
kubectl scale deployment intuitive-search --replicas=0
kubectl scale deployment admin-dashboard   --replicas=0
```

Bring it back:

```bash
kubectl scale deployment intuitive-search --replicas=1
kubectl scale deployment admin-dashboard   --replicas=1
```

### Remove a service completely

```bash
kubectl delete -f backend/k8s/
kubectl delete -f admin-dashboard/k8s/
```

A cluster deployed before the Python embedding service was removed from the
repo still has its objects; its manifests are gone, so delete them by name:

```bash
kubectl delete deployment,svc embedding-service embedding-service-svc
```

### Remove everything (full teardown)

```bash
kubectl delete -f backend/k8s/ \
               -f admin-dashboard/k8s/
```

Postgres is unaffected — it runs on the host, not in k8s.
To also wipe the database:

```bash
psql postgres -c "DROP DATABASE banksearch;"
psql postgres -c "DROP USER bank;"
```

---

## Secrets

Sensitive values live in k8s Secret manifests — update before applying to a shared environment:

| File | Variables to change |
|------|-------------------|
| `backend/k8s/secret.yaml` | `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` |
| `admin-dashboard/k8s/secret.yaml` | `DATABASE_URL`, `ADMIN_PASSWORD`, `SESSION_SECRET` |

Generate a strong session secret:

```bash
openssl rand -hex 32
```
