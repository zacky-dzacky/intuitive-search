# Logbook — 2026-08-17 / 2026-08-18
## Istio Setup, Observability Stack, and Embedding Pipeline Fix

---

### Session Overview

This session covered the full lifecycle of getting Istio and its observability stack working, diagnosing why the Kiali service graph showed no traffic between `intuitive-search` and `embedding-service`, and fixing the underlying root causes that were silently breaking vector search.

---

## 1. Fixed 500 Error After Istio Setup

**Problem:** Every request to the Istio ingress gateway returned 500 immediately after following `ISTIO.md`.

**Root cause:** The `VirtualService` referenced `intuitive-search-gateway` in its `gateways:` field, but the `Gateway` resource was never created. The file `backend/k8s/istio-gateway.yaml` was missing entirely — it was documented in `ISTIO.md` but never written to disk or applied.

**Fix:**
- Created `backend/k8s/istio-gateway.yaml` with the Gateway resource
- Applied it: `kubectl apply -f backend/k8s/istio-gateway.yaml`

**Verified:** `curl http://192.168.139.2/actuator/health` → `{"status":"UP"}`

---

## 2. Updated Frontend API URL

**Problem:** The frontend (`frontend/index.html`) had `http://k8s.orb.local:8080` hardcoded as the API base URL. With Istio, the backend Service was changed from `LoadBalancer` to `ClusterIP`, so that URL no longer works — traffic must go through the Istio ingress gateway.

**Fix:** Updated the default API URL to `http://192.168.139.2` (the Istio ingress gateway external IP assigned by OrbStack).

**Note:** `192.168.139.2` is auto-assigned by OrbStack's load balancer controller when `istio-ingressgateway` is created with `type: LoadBalancer`. It can change on Istio reinstall. Use `?api=http://...` query param to override at runtime, or look it up with:
```bash
kubectl get svc istio-ingressgateway -n istio-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

---

## 3. Documented Two Routing Modes

**Added to `KUBERNETES.md`:** New "Routing Modes" section documenting both approaches:

| Mode | Service Type | Entry Point | Frontend API URL |
|------|-------------|-------------|-----------------|
| 1 — Direct LoadBalancer | `LoadBalancer` | `k8s.orb.local:8080` | `http://k8s.orb.local:8080` |
| 2 — Istio Ingress (current) | `ClusterIP` | `192.168.139.2:80` | `http://192.168.139.2` |

Includes traffic path diagrams, required Istio resources table, and step-by-step switch instructions.

---

## 4. Installed Observability Addons

The Istio `demo` profile ships addon manifests in `istio-1.22.0/samples/addons/` but does NOT install them automatically.

Installed manually:
```bash
kubectl apply -f istio-1.22.0/samples/addons/prometheus.yaml
kubectl apply -f istio-1.22.0/samples/addons/kiali.yaml
kubectl apply -f istio-1.22.0/samples/addons/jaeger.yaml
kubectl apply -f istio-1.22.0/samples/addons/grafana.yaml
```

All four pods came up in `istio-system`:

| Addon | Port | Purpose |
|-------|:----:|---------|
| Kiali | 20001 | Live mesh topology graph |
| Jaeger | 16686 | Distributed request tracing |
| Grafana | 3000 | Pre-built Istio metric dashboards |
| Prometheus | 9090 | Raw metric scraping |

**Created `MONITORING.md`** with full documentation: dashboard access commands, Kiali navigation steps, Jaeger trace reading guide, Grafana dashboard list, Prometheus PromQL queries, log-based tracing commands, and install/reinstall instructions.

---

## 5. Fixed Kiali "Could not fetch Grafana info" Warning

**Problem:** Kiali showed "grafana URL is not set in Kiali configuration" even though Grafana was installed.

**Root cause:** The Kiali `ConfigMap` `external_services` section had no `grafana.url` entry.

**Fix:** Patched the ConfigMap to add:
```yaml
external_services:
  grafana:
    url: "http://grafana.istio-system:3000"
```

Applied via `kubectl create configmap kiali --from-file=config.yaml=... --dry-run=client -o yaml | kubectl apply -f -` then restarted the Kiali deployment.

---

## 6. Kiali Graph Empty — No intuitive-search → embedding-service Edge

**Problem:** The Kiali service graph never showed any traffic between `intuitive-search` and `embedding-service`, even after many search requests.

**Investigation path:**

1. Checked that both pods had sidecars injected (`2/2` READY) ✓
2. Checked the embedding service logs — no `/embed` POST requests arriving from the backend
3. Checked Envoy sidecar logs on the backend pod — no outbound calls to port 8000
4. Tested connectivity from inside the backend pod via `curl` → worked (80ms round trip)
5. Forced the circuit breaker warning after 6 requests → error message revealed:

```
Embedding service failing (I/O error on POST request for "http://embedding-svc:8000/embed": embedding-svc: Name or service not known)
```

**Root cause 1:** Wrong hostname in `backend/k8s/configmap.yaml`:
```yaml
# WRONG (original)
EMBEDDING_BASE_URL: "http://embedding-svc:8000"

# CORRECT (fixed)
EMBEDDING_BASE_URL: "http://embedding-service-svc:8000"
```

The actual Kubernetes Service name is `embedding-service-svc`, not `embedding-svc`. DNS resolution was failing silently for 4 failures, then the circuit breaker opened on the 5th and logged the error.

**Why our earlier `kubectl patch` didn't stick:** `deploy-local.sh` runs `kubectl apply -f k8s/` which reapplied the configmap from the file on disk (still had wrong hostname), overwriting our patch. Always fix the source file, not just the live cluster object.

---

## 7. Vector Search Returning 0.0 Score

**Problem:** After fixing the hostname, the embedding service started receiving calls (200 OK), but vector score was still 0.0 in search responses.

**Root cause 2:** `features.embedding` column was NULL for all 67 features. The `precompute_embeddings.py` backfill script had never run successfully (it OOMKilled inside the pod and crashed locally due to PyTorch/OpenMP conflicts on macOS).

**Fix:** Wrote a minimal backfill script using only stdlib (`urllib.request`) + `psycopg2` that called the HTTP embedding service instead of loading the model locally:

```
67 features → HTTP service → 384-dim vectors → Postgres
```

Then rebuilt the HNSW vector index:
```bash
psql -U zackysyarief -d banksearch -f db/03_indexes.sql
```

**Result:** Vector score jumped to 0.836 for "transfer money".

---

## 8. Postgres Connection Exhaustion During Rolling Updates

**Problem:** Rolling update (old pod + new pod running simultaneously) exhausted Postgres `max_connections = 100`. The new pod failed with:
```
FATAL: remaining connection slots are reserved for roles with the SUPERUSER attribute
```

**Root cause:** `application.yml` had `maximum-pool-size: 20`. Two pods × 20 connections = 40, plus stale connections from previous restarts that didn't close cleanly = 97+ connections, hitting the 100 limit.

**Fixes applied:**
1. Reduced `maximum-pool-size: 20` → `5` in `application.yml` (2 pods × 5 = 10, well within any limit)
2. Increased Postgres `max_connections` to 200:
   ```sql
   ALTER SYSTEM SET max_connections = 200;
   ```
3. Restarted Postgres to apply: `brew services restart postgresql@16`
4. Cleared stale idle connections with `pg_terminate_backend()` during the incident

---

## 9. Files Changed

| File | Change |
|------|--------|
| `backend/k8s/istio-gateway.yaml` | **Created** — Istio Gateway resource |
| `backend/k8s/configmap.yaml` | Fixed `EMBEDDING_BASE_URL`: `embedding-svc` → `embedding-service-svc` |
| `backend/src/main/resources/application.yml` | Reduced `maximum-pool-size`: 20 → 5 |
| `frontend/index.html` | Updated API default URL to `http://192.168.139.2`; added comment documenting both routing modes |
| `KUBERNETES.md` | Added "Routing Modes" section with both routing patterns documented |
| `MONITORING.md` | **Created** — full observability documentation |

---

## 10. Current State (end of session)

- Istio ingress gateway routing traffic correctly on port 80
- All 4 observability addons running (Kiali, Jaeger, Grafana, Prometheus)
- Kiali graph shows `istio-ingressgateway → intuitive-search-svc → embedding-service-svc`
- Vector search working: 67/67 features have embeddings, HNSW index built
- Postgres: `max_connections = 200`, Hikari pool size = 5 per pod
- Frontend correctly calls `http://192.168.139.2` via Istio ingress
