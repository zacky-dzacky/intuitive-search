# Monitoring & Observability — Intuitive Search

All tooling runs inside the cluster via the Istio `demo` profile addons. Access is always through `istioctl dashboard` (port-forward) — none of these services have external IPs.

---

## Table of Contents

1. [Installed Addons](#1-installed-addons)
2. [Open Dashboards](#2-open-dashboards)
3. [Kiali — Traffic Graph](#3-kiali--traffic-graph)
4. [Jaeger — Distributed Tracing](#4-jaeger--distributed-tracing)
5. [Grafana — Metrics Dashboards](#5-grafana--metrics-dashboards)
6. [Prometheus — Raw Metrics](#6-prometheus--raw-metrics)
7. [Loki — Log Aggregation](#7-loki--log-aggregation)
8. [Log-based Tracing (no UI)](#8-log-based-tracing-no-ui)
9. [Install / Reinstall Addons](#9-install--reinstall-addons)

---

## 1. Installed Addons

| Addon | Purpose | Port (local) |
|-------|---------|:---:|
| Kiali | Service mesh topology, live traffic graph | 20001 |
| Jaeger | Distributed tracing, per-request span timeline | 16686 |
| Grafana | Pre-built Istio metric dashboards | 3000 |
| Prometheus | Raw metric scraping and querying | 9090 |
| Loki | Log aggregation, queried from Grafana Explore | via Grafana |
| Alloy | Log collector — ships pod logs into Loki | — |

All pods live in the `istio-system` namespace:

```bash
kubectl get pods -n istio-system
```

Expected output:

```
istiod-*                  1/1   Running
istio-ingressgateway-*    1/1   Running
istio-egressgateway-*     1/1   Running
kiali-*                   1/1   Running
jaeger-*                  1/1   Running
grafana-*                 1/1   Running
prometheus-*              2/2   Running
loki-0                    1/1   Running
alloy-*                   1/1   Running
```

---

## 2. Open Dashboards

Run each command in a separate terminal — they port-forward and open the browser automatically.

```bash
# Kiali — traffic graph
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard kiali

# Jaeger — distributed tracing
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard jaeger

# Grafana — metric dashboards
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard grafana

# Prometheus — raw metrics
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard prometheus
```

Or open URLs directly after port-forwarding starts:

| Tool | URL |
|------|-----|
| Kiali | http://localhost:20001/kiali |
| Jaeger | http://localhost:16686 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

---

## 3. Kiali — Traffic Graph

Kiali shows the live service mesh topology and traffic flow in real time.

### Open

```bash
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard kiali
```

### Navigate

1. Click **Graph** in the left sidebar
2. **Namespace** → `default`
3. Time range (top right) → **Last 1m**
4. **Display** dropdown → enable **Traffic Animation** to see animated packet flow

### What you see

```
istio-ingressgateway → intuitive-search-vs → intuitive-search-svc → intuitive-search (pod)
```

Each edge shows request rate (RPS), error rate (%), and response time. Red edges indicate errors.

### Generate traffic (if graph is empty)

```bash
for i in $(seq 1 30); do
  curl -s "http://192.168.139.2/api/search/suggest?q=transfer" > /dev/null
done
```

Then click the **refresh** button (circular arrow) in Kiali.

### Useful views

| View | How to access | What it shows |
|------|--------------|---------------|
| Traffic graph | Graph → Namespace: default | Full mesh topology with live flow |
| Service detail | Graph → click a node → Service tab | RPS, error %, p50/p99 latency |
| VirtualService config | Services → intuitive-search-svc → Istio Config | Gateway + VS + DR rules |
| Workload health | Workloads → intuitive-search | Pod status, sidecar injection, metrics |

---

## 4. Jaeger — Distributed Tracing

Jaeger captures the full request lifecycle as a timeline of spans — one span per hop (ingress gateway → sidecar → app).

### Open

```bash
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard jaeger
# http://localhost:16686
```

### Find traces

1. **Service** dropdown → `istio-ingressgateway`
2. **Operation** → `*` (all)
3. Click **Find Traces**

### Read a trace

Click any trace to expand it. Each row is a span:

```
▼ istio-ingressgateway: GET /api/search/suggest        22ms (total)
  └── intuitive-search.default: GET /api/search/suggest  20ms
```

- The gap between the gateway span start and the inner span start = network latency
- The inner span duration = time spent inside the app container

### Generate traces

```bash
for i in $(seq 1 30); do
  curl -s "http://192.168.139.2/api/search/suggest?q=transfer" > /dev/null
  curl -s "http://192.168.139.2/actuator/health" > /dev/null
done
```

### Note on trace propagation

Istio automatically captures ingress → sidecar spans. To see end-to-end traces across multiple services (e.g. the admin dashboard calling the backend), the app must forward these headers on outbound calls:

```
x-request-id
x-b3-traceid
x-b3-spanid
x-b3-parentspanid
x-b3-sampled
x-b3-flags
```

For this single-service setup, gateway + sidecar spans are captured automatically with no code changes.

---

## 5. Grafana — Metrics Dashboards

Grafana ships with pre-built Istio dashboards fed by Prometheus.

### Open

```bash
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard grafana
# http://localhost:3000
```

### Key dashboards

Navigate to **Dashboards → Browse → Istio**:

| Dashboard | What it shows |
|-----------|--------------|
| Istio Mesh Dashboard | Global RPS, error rate, p50/p99 latency across all services |
| Istio Service Dashboard | Per-service inbound/outbound traffic, success rate |
| Istio Workload Dashboard | Per-pod CPU, memory, request volume |

### Filter to this project

On any dashboard, set:
- **Namespace** → `default`
- **Service** → `intuitive-search-svc`
- **Workload** → `intuitive-search`

---

## 6. Prometheus — Raw Metrics

Prometheus scrapes Envoy sidecar metrics from every pod automatically.

### Open

```bash
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard prometheus
# http://localhost:9090
```

### Useful queries

Paste these into the Prometheus expression bar:

```promql
# Request rate to the backend (per second, last 5 min)
rate(istio_requests_total{destination_service="intuitive-search-svc.default.svc.cluster.local"}[5m])

# Error rate (5xx responses)
rate(istio_requests_total{destination_service="intuitive-search-svc.default.svc.cluster.local", response_code=~"5.*"}[5m])

# p99 response latency
histogram_quantile(0.99,
  rate(istio_request_duration_milliseconds_bucket{
    destination_service="intuitive-search-svc.default.svc.cluster.local"
  }[5m])
)

# Requests broken down by response code
sum by (response_code) (
  rate(istio_requests_total{destination_service="intuitive-search-svc.default.svc.cluster.local"}[5m])
)
```

---

## 7. Loki — Log Aggregation

Grafana's Istio dashboards cover metrics; Loki covers the log side, queried from the same Grafana instance. This is what makes application logs searchable across pod restarts and puts them on the same time axis as the Istio dashboards — `kubectl logs` (§8) does neither.

### How logs get there

```
backend container (stdout)
  -> Alloy DaemonSet      tails via the Kubernetes API, attaches labels
    -> Loki               istio-system, port 3100
      -> Grafana Explore  Loki datasource, pre-provisioned
```

The Grafana addon already provisions a Loki datasource at `http://loki:3100`, so nothing needs configuring on the Grafana side — that datasource simply has nothing to talk to until Loki and a collector are installed (§9).

Alloy's manifest lives at `k8s/observability/alloy.yaml`. It reads logs through the Kubernetes API rather than mounting node log directories, so it does not depend on the container runtime's on-disk layout, and it runs outside the mesh (`sidecar.istio.io/inject: "false"`) — it is infrastructure, not a mesh workload.

### Enable DEBUG on the backend first

`backend/src/main/resources/application.yml` sets `logging.level.com.bank.intuitivesearch: INFO`, which hides the most useful line in the whole pipeline: `SearchOrchestrator` logs the matched feature, confidence, and extracted slots for every query at DEBUG.

`backend/k8s/configmap.yaml` overrides that level:

```yaml
LOGGING_LEVEL_COM_BANK_INTUITIVESEARCH: "DEBUG"
```

```bash
kubectl apply -f backend/k8s/configmap.yaml
kubectl rollout restart deployment/intuitive-search
```

Set it back to `INFO` when you are done — the line fires on every request.

### Query in Grafana

```bash
~/intuitive_search/istio-1.22.0/bin/istioctl dashboard grafana
# http://localhost:3000 -> Explore -> pick the "Loki" datasource
```

Indexed labels: `namespace`, `pod`, `container`, `app`.

```logql
# All backend application logs
{namespace="default", container="backend"}

# The per-query decision line
{namespace="default", container="backend"} |~ `query='.*' -> feature=`

# Only the matches that came back under 0.5 confidence
{namespace="default", container="backend"} |~ `confidence=0\.[0-4]`

# App logs and sidecar access logs together, on one time axis
{app="intuitive-search"}

# Errors across every service in the namespace
{namespace="default"} |= "ERROR"
```

Every app pod runs an Istio sidecar, so each pod produces two streams: `container="backend"` selects the application, `container="istio-proxy"` the Envoy access log. Dropping the `container` selector interleaves them.

### Verify the pipeline

```bash
# Collector running, no push errors
kubectl logs -n istio-system -l app=alloy --tail=30

# Loki knows about the backend container
kubectl port-forward -n istio-system svc/loki 3100:3100 &
curl -s http://localhost:3100/loki/api/v1/label/container/values
```

In Grafana, **Connections → Data sources → Loki → Save & test** should report `Data source successfully connected`.

### Note on log format

Logs are ingested as plain text, so LogQL is limited to substring and regex filters (`|=`, `|~`). Spring Boot 3.4 can emit structured JSON via `logging.structured.format.console: ecs` in `application.yml`, which would unlock field-level queries such as `| json | confidence < 0.5`. That also changes local console output, so it has been left off.

---

## 8. Log-based Tracing (no UI)

When dashboards aren't available, trace a request through three log streams manually.

### Layer 1 — Istio Ingress Gateway

Logs every request that arrives at the mesh boundary:

```bash
kubectl logs -n istio-system deploy/istio-ingressgateway -f
```

Sample line:

```
[2026-08-17T10:00:00.000Z] "GET /api/search/suggest?q=trf HTTP/1.1" 200 - via_upstream - "-" 0 512 22 21 "-" "curl/8.1.2" "abc123" "192.168.139.2" "192.168.194.238:8080"
```

Fields: method, path, status, bytes-sent, duration-ms, upstream-host.

### Layer 2 — Envoy Sidecar (inside the pod)

Logs inbound requests received by the Envoy proxy injected into the backend pod:

```bash
kubectl logs -l app=intuitive-search -c istio-proxy -f
```

If you see the request here, it crossed the mesh and reached the correct pod.

### Layer 3 — Application Container

Logs processed by the Spring Boot app itself:

```bash
kubectl logs -l app=intuitive-search -c backend -f
```

If you see a log entry here, the request made it all the way through every layer.

### Watch all three in parallel

Open three terminals, or use `&`:

```bash
kubectl logs -n istio-system deploy/istio-ingressgateway -f &
kubectl logs -l app=intuitive-search -c istio-proxy -f &
kubectl logs -l app=intuitive-search -c backend -f
```

Then trigger a request:

```bash
curl "http://192.168.139.2/api/search/suggest?q=transfer"
```

A log entry should appear in all three streams in sequence.

### Inspect Envoy routing config

When a request is misrouted or dropped, check what Envoy has loaded:

```bash
POD=$(kubectl get pod -l app=intuitive-search -o name | head -1)

# Active route table
~/intuitive_search/istio-1.22.0/bin/istioctl proxy-config routes $POD

# Upstream clusters (where traffic can be sent)
~/intuitive_search/istio-1.22.0/bin/istioctl proxy-config clusters $POD

# Check if all sidecars are in sync with istiod
~/intuitive_search/istio-1.22.0/bin/istioctl proxy-status
```

### Summary — which tool answers which question

| Question | Tool |
|----------|------|
| Did the request arrive at Istio? | Ingress gateway log |
| Did the right pod receive it? | Sidecar log (`istio-proxy`) |
| Did the app process it? | Container log (`backend`) |
| Which path did traffic take across services? | Kiali graph |
| Where did a slow request spend its time? | Jaeger trace |
| What is the error rate over the last hour? | Grafana / Prometheus |
| What did the app log for a request an hour ago? | Grafana -> Explore -> Loki |
| Which queries matched with low confidence? | Loki, `SearchOrchestrator` DEBUG line |
| Why is Envoy routing requests a certain way? | `istioctl proxy-config` |

---

## 9. Install / Reinstall Addons

Addon manifests ship with istio at `istio-1.22.0/samples/addons/`.

```bash
# Install all at once
kubectl apply -f ~/intuitive_search/istio-1.22.0/samples/addons/prometheus.yaml
kubectl apply -f ~/intuitive_search/istio-1.22.0/samples/addons/kiali.yaml
kubectl apply -f ~/intuitive_search/istio-1.22.0/samples/addons/jaeger.yaml
kubectl apply -f ~/intuitive_search/istio-1.22.0/samples/addons/grafana.yaml
kubectl apply -f ~/intuitive_search/istio-1.22.0/samples/addons/loki.yaml

# The log collector is not an Istio addon; it lives in this repo.
kubectl apply -f ~/intuitive_search/k8s/observability/alloy.yaml

# Wait for all to be ready
kubectl rollout status deployment/prometheus -n istio-system
kubectl rollout status deployment/kiali      -n istio-system
kubectl rollout status deployment/jaeger     -n istio-system
kubectl rollout status deployment/grafana    -n istio-system
kubectl rollout status statefulset/loki      -n istio-system
kubectl rollout status daemonset/alloy       -n istio-system
```

`loki.yaml` ships storage and a query API but no agent, so nothing lands in it until `alloy.yaml` is applied too.

To remove all addons (does not affect Istio itself):

```bash
kubectl delete -f ~/intuitive_search/k8s/observability/alloy.yaml
kubectl delete -f ~/intuitive_search/istio-1.22.0/samples/addons/loki.yaml
kubectl delete -f ~/intuitive_search/istio-1.22.0/samples/addons/grafana.yaml
kubectl delete -f ~/intuitive_search/istio-1.22.0/samples/addons/jaeger.yaml
kubectl delete -f ~/intuitive_search/istio-1.22.0/samples/addons/kiali.yaml
kubectl delete -f ~/intuitive_search/istio-1.22.0/samples/addons/prometheus.yaml
```
