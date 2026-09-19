# Istio Setup: Ingress Gateway, VirtualService, Envoy Sidecar, Canary Release & Blue-Green Deployment

This guide is written for the **intuitive-search** project. All manifests reference the exact resource names (`intuitive-search`, `intuitive-search-svc`, namespace `default`) already in use.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Install Istio](#2-install-istio)
3. [Enable Envoy Sidecar Injection](#3-enable-envoy-sidecar-injection)
4. [Adapt the Existing Service](#4-adapt-the-existing-service)
5. [Deploy the Istio Ingress Gateway](#5-deploy-the-istio-ingress-gateway)
6. [Create the Gateway Resource](#6-create-the-gateway-resource)
7. [Create the VirtualService (stable traffic)](#7-create-the-virtualservice-stable-traffic)
8. [Canary Release](#8-canary-release)
9. [Blue-Green Deployment](#9-blue-green-deployment)
10. [Verify the Setup](#10-verify-the-setup)
11. [Observability (Kiali, Prometheus, Jaeger)](#11-observability-kiali-prometheus-jaeger)
12. [Cleanup](#12-cleanup)

---

## 1. Prerequisites

| Tool | Minimum version | Check |
|------|----------------|-------|
| `kubectl` | 1.26+ | `kubectl version --client` |
| `istioctl` | 1.22+ | `istioctl version` |
| Kubernetes cluster | 1.26+ | `kubectl cluster-info` |
| Cluster nodes | ≥2 vCPU / 4 GiB RAM each | `kubectl top nodes` |

> **OrbStack / local cluster note:** OrbStack's built-in Kubernetes works fine. Ensure `kubectl` points to the right context: `kubectl config current-context`.

---

## 2. Install Istio

### 2.1 Download istioctl

```bash
curl -L https://istio.io/downloadIstio | ISTIO_VERSION=1.22.0 sh -
cd istio-1.22.0
export PATH="$PWD/bin:$PATH"
```

Add the export to `~/.zshrc` to persist it:

```bash
echo 'export PATH="$HOME/istio-1.22.0/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### 2.2 Pre-flight check

```bash
istioctl x precheck
```

All checks must pass before continuing.

### 2.3 Install with the `demo` profile

The `demo` profile enables the Ingress Gateway, Egress Gateway, and all telemetry add-ons. For production use the `default` profile instead.

```bash
istioctl install --set profile=demo -y
```

### 2.4 Verify the control plane

```bash
kubectl get pods -n istio-system
```

Expected pods (all `Running`):

```
istiod-*                    1/1   Running
istio-ingressgateway-*      1/1   Running
istio-egressgateway-*       1/1   Running
```

---

## 3. Enable Envoy Sidecar Injection

Istio injects an Envoy sidecar proxy into every pod in namespaces that carry the `istio-injection=enabled` label. This gives each pod mTLS, observability, and traffic management for free.

### 3.1 Label the namespace

```bash
kubectl label namespace default istio-injection=enabled
```

Verify:

```bash
kubectl get namespace default --show-labels
# NAME      STATUS   AGE   LABELS
# default   Active   ...   istio-injection=enabled,...
```

### 3.2 Restart existing deployments

Pods that were running before the label was added do not have a sidecar yet. Roll them to pick it up:

```bash
kubectl rollout restart deployment/intuitive-search
kubectl rollout restart deployment/postgres
```

### 3.3 Confirm the sidecar is present

```bash
kubectl get pods -l app=intuitive-search
```

The `READY` column must show `2/2` — one for the backend container and one for the Envoy sidecar:

```
NAME                                READY   STATUS    RESTARTS
intuitive-search-7d9f8c5b6-xxxxx    2/2     Running   0
```

---

## 4. Adapt the Existing Service

Istio manages ingress via its own Gateway + VirtualService, so the Kubernetes Service no longer needs `type: LoadBalancer`. Change it to `ClusterIP` so external traffic is routed only through Istio.

**Current `backend/k8s/service.yaml`:**

```yaml
spec:
  type: LoadBalancer   # <-- change this
```

**Updated:**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: intuitive-search-svc
  namespace: default
spec:
  selector:
    app: intuitive-search
  type: ClusterIP        # Istio Ingress Gateway handles external access
  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
```

Apply:

```bash
kubectl apply -f backend/k8s/service.yaml
```

---

## 5. Deploy the Istio Ingress Gateway

The Ingress Gateway pod was already created by `istioctl install`. You just need to find its external IP/hostname:

```bash
kubectl get svc istio-ingressgateway -n istio-system
```

Example output on OrbStack (gets a real local IP):

```
NAME                   TYPE           CLUSTER-IP     EXTERNAL-IP    PORT(S)
istio-ingressgateway   LoadBalancer   10.96.100.5    198.19.249.2   80:31380/TCP,443:31390/TCP,...
```

Save the external IP for later testing:

```bash
export INGRESS_HOST=$(kubectl get svc istio-ingressgateway -n istio-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
export INGRESS_PORT=80
echo "Gateway: http://$INGRESS_HOST:$INGRESS_PORT"
```

---

## 6. Create the Gateway Resource

A `Gateway` resource configures the Istio Ingress Gateway to accept traffic on a specific host and port.

Create `backend/k8s/istio-gateway.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: intuitive-search-gateway
  namespace: default
spec:
  selector:
    istio: ingressgateway   # matches the default istio-ingressgateway pod label
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "*"   # accept any hostname; replace with your real domain in production
```

Apply:

```bash
kubectl apply -f backend/k8s/istio-gateway.yaml
```

---

## 7. Create the VirtualService (stable traffic)

A `VirtualService` binds routes to the Gateway and defines where traffic goes.

Create `backend/k8s/istio-virtualservice.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: intuitive-search-vs
  namespace: default
spec:
  hosts:
    - "*"
  gateways:
    - intuitive-search-gateway
  http:
    - match:
        - uri:
            prefix: /api
        - uri:
            prefix: /actuator/health
      route:
        - destination:
            host: intuitive-search-svc
            port:
              number: 8080
```

Apply:

```bash
kubectl apply -f backend/k8s/istio-virtualservice.yaml
```

Test the health endpoint through the Gateway:

```bash
curl http://$INGRESS_HOST:$INGRESS_PORT/actuator/health
# {"status":"UP"}
```

---

## 8. Canary Release

Canary release works by running two versions of the backend simultaneously and splitting traffic between them using Istio's weighted routing. Istio uses `DestinationRule` subsets to distinguish the versions by pod label.

### 8.1 Concept

```
Client
  │
  ▼
Istio Ingress Gateway
  │
  ▼
VirtualService  ─── 90% ──▶ stable (v1)
                └── 10% ──▶ canary (v2)
```

### 8.2 Add a `version` label to the stable Deployment

Update `backend/k8s/deployment.yaml` — add `version: v1` to both `metadata.labels` and `spec.template.metadata.labels`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: intuitive-search-v1          # rename to avoid collision with canary
  namespace: default
  labels:
    app: intuitive-search
    version: v1
spec:
  replicas: 1
  selector:
    matchLabels:
      app: intuitive-search
      version: v1
  template:
    metadata:
      labels:
        app: intuitive-search
        version: v1
    spec:
      containers:
        - name: backend
          image: host.docker.internal:5000/intuitive-search-service:0.0.1
          # ... rest unchanged
```

### 8.3 Create the canary Deployment

Create `backend/k8s/deployment-canary.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: intuitive-search-v2
  namespace: default
  labels:
    app: intuitive-search
    version: v2
spec:
  replicas: 1
  selector:
    matchLabels:
      app: intuitive-search
      version: v2
  template:
    metadata:
      labels:
        app: intuitive-search
        version: v2
    spec:
      containers:
        - name: backend
          image: host.docker.internal:5000/intuitive-search-service:0.0.2   # new version
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: intuitive-search-config
            - secretRef:
                name: intuitive-search-secret
          resources:
            requests:
              cpu: "250m"
              memory: "256Mi"
            limits:
              cpu: "1"
              memory: "512Mi"
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 45
            periodSeconds: 15
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
```

### 8.4 Create the DestinationRule

A `DestinationRule` defines subsets (stable / canary) by matching pod labels.

Create `backend/k8s/istio-destinationrule.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: intuitive-search-dr
  namespace: default
spec:
  host: intuitive-search-svc
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        h2UpgradePolicy: UPGRADE
    outlierDetection:           # automatically eject unhealthy pods
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 30s
  subsets:
    - name: stable
      labels:
        version: v1
    - name: canary
      labels:
        version: v2
```

### 8.5 Update the VirtualService for weighted routing

Replace `backend/k8s/istio-virtualservice.yaml` with:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: intuitive-search-vs
  namespace: default
spec:
  hosts:
    - "*"
  gateways:
    - intuitive-search-gateway
  http:
    - match:
        - uri:
            prefix: /api
        - uri:
            prefix: /actuator/health
      route:
        - destination:
            host: intuitive-search-svc
            subset: stable
            port:
              number: 8080
          weight: 90
        - destination:
            host: intuitive-search-svc
            subset: canary
            port:
              number: 8080
          weight: 10
```

### 8.6 Apply all canary resources

```bash
kubectl apply -f backend/k8s/deployment.yaml            # stable (v1)
kubectl apply -f backend/k8s/deployment-canary.yaml     # canary (v2)
kubectl apply -f backend/k8s/istio-destinationrule.yaml
kubectl apply -f backend/k8s/istio-virtualservice.yaml
```

### 8.7 Shift traffic gradually

Edit the `weight` fields and re-apply as confidence grows:

| Phase | stable weight | canary weight |
|-------|:---:|:---:|
| Initial canary | 90 | 10 |
| Expanded | 70 | 30 |
| Near full | 50 | 50 |
| Full rollout | 0 | 100 |

After full rollout, rename canary to v1, delete the old v1 Deployment, and reset weights.

### 8.8 Header-based canary routing (optional)

Route only requests with `x-canary: true` header to v2 — useful for internal testers:

```yaml
http:
  - match:
      - headers:
          x-canary:
            exact: "true"
    route:
      - destination:
          host: intuitive-search-svc
          subset: canary
          port:
            number: 8080
  - route:                         # default: everyone else goes stable
      - destination:
          host: intuitive-search-svc
          subset: stable
          port:
            number: 8080
```

Test:

```bash
curl -H "x-canary: true" http://$INGRESS_HOST:$INGRESS_PORT/api/search?q=test
```

---

## 9. Blue-Green Deployment

Blue-green deployment eliminates downtime by maintaining two identical production environments — **blue** (current live) and **green** (new version). Traffic is switched instantly via the VirtualService. If the green environment has issues, you flip the VirtualService back to blue in seconds with zero pod restarts.

### 9.1 Concept

```
Client
  │
  ▼
Istio Ingress Gateway
  │
  ▼
VirtualService ──── 100% ──▶ blue  (current live)
                             green (idle, fully deployed, ready)

                   switch →

VirtualService ──── 100% ──▶ green (new live)
                             blue  (idle, kept as rollback target)
```

Key difference from canary: **no traffic split** — you go 100% to one environment at a time. The switch is instantaneous.

### 9.2 Naming convention

| Environment | Deployment name | version label | Image tag |
|-------------|----------------|:---:|-----------|
| Blue (current) | `intuitive-search-blue` | `blue` | `0.0.1` |
| Green (new) | `intuitive-search-green` | `green` | `0.0.2` |

### 9.3 Blue Deployment

Create `backend/k8s/deployment-blue.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: intuitive-search-blue
  namespace: default
  labels:
    app: intuitive-search
    version: blue
spec:
  replicas: 2                        # blue is live — run full replica count
  selector:
    matchLabels:
      app: intuitive-search
      version: blue
  template:
    metadata:
      labels:
        app: intuitive-search
        version: blue
    spec:
      containers:
        - name: backend
          image: host.docker.internal:5000/intuitive-search-service:0.0.1
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: intuitive-search-config
            - secretRef:
                name: intuitive-search-secret
          resources:
            requests:
              cpu: "250m"
              memory: "256Mi"
            limits:
              cpu: "1"
              memory: "512Mi"
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 45
            periodSeconds: 15
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
```

### 9.4 Green Deployment

Create `backend/k8s/deployment-green.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: intuitive-search-green
  namespace: default
  labels:
    app: intuitive-search
    version: green
spec:
  replicas: 2                        # match blue replica count before the switch
  selector:
    matchLabels:
      app: intuitive-search
      version: green
  template:
    metadata:
      labels:
        app: intuitive-search
        version: green
    spec:
      containers:
        - name: backend
          image: host.docker.internal:5000/intuitive-search-service:0.0.2   # new version
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: intuitive-search-config
            - secretRef:
                name: intuitive-search-secret
          resources:
            requests:
              cpu: "250m"
              memory: "256Mi"
            limits:
              cpu: "1"
              memory: "512Mi"
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 45
            periodSeconds: 15
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
```

### 9.5 DestinationRule with blue/green subsets

Create `backend/k8s/istio-destinationrule-bluegreen.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: intuitive-search-dr
  namespace: default
spec:
  host: intuitive-search-svc
  trafficPolicy:
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 30s
  subsets:
    - name: blue
      labels:
        version: blue
    - name: green
      labels:
        version: green
```

### 9.6 VirtualService pointing 100% to blue (initial state)

Create `backend/k8s/istio-virtualservice-bluegreen.yaml`:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: intuitive-search-vs
  namespace: default
spec:
  hosts:
    - "*"
  gateways:
    - intuitive-search-gateway
  http:
    - match:
        - uri:
            prefix: /api
        - uri:
            prefix: /actuator/health
      route:
        - destination:
            host: intuitive-search-svc
            subset: blue               # ← live environment
            port:
              number: 8080
          weight: 100
```

### 9.7 Deploy both environments

```bash
# Deploy blue (becomes live immediately)
kubectl apply -f backend/k8s/deployment-blue.yaml

# Deploy green in the background — no traffic yet
kubectl apply -f backend/k8s/deployment-green.yaml

# Apply routing rules (all traffic → blue)
kubectl apply -f backend/k8s/istio-destinationrule-bluegreen.yaml
kubectl apply -f backend/k8s/istio-virtualservice-bluegreen.yaml
```

Wait for green to be fully ready before switching:

```bash
kubectl rollout status deployment/intuitive-search-green
# Waiting for deployment "intuitive-search-green" rollout to finish: 0 of 2 updated replicas are available...
# deployment "intuitive-search-green" successfully rolled out
```

### 9.8 Smoke-test green before switching

Use a header to route your own requests to green while production traffic still hits blue:

Add a header-based match above the default route in the VirtualService:

```yaml
http:
  - match:
      - headers:
          x-env:
            exact: "green"
    route:
      - destination:
          host: intuitive-search-svc
          subset: green
          port:
            number: 8080
  - route:                           # everyone else still hits blue
      - destination:
          host: intuitive-search-svc
          subset: blue
          port:
            number: 8080
        weight: 100
```

Test green directly:

```bash
curl -H "x-env: green" http://$INGRESS_HOST:$INGRESS_PORT/actuator/health
# {"status":"UP"}

curl -H "x-env: green" http://$INGRESS_HOST:$INGRESS_PORT/api/search?q=bank
```

### 9.9 Switch traffic to green (the cutover)

Edit `istio-virtualservice-bluegreen.yaml` — change `subset: blue` → `subset: green`:

```yaml
      route:
        - destination:
            host: intuitive-search-svc
            subset: green              # ← now live
            port:
              number: 8080
          weight: 100
```

Apply:

```bash
kubectl apply -f backend/k8s/istio-virtualservice-bluegreen.yaml
```

The switch is instantaneous — Istio propagates the updated route to all Envoy proxies in seconds. No pod restarts, no downtime.

Verify:

```bash
curl http://$INGRESS_HOST:$INGRESS_PORT/actuator/health
# Requests now routed to green pods
kubectl logs -l app=intuitive-search,version=green -c backend --tail=20 -f
```

### 9.10 Rollback to blue

If green has issues, flip back in one command:

```bash
kubectl patch virtualservice intuitive-search-vs \
  --type=json \
  -p='[{"op":"replace","path":"/spec/http/0/route/0/destination/subset","value":"blue"}]'
```

Or re-apply the file with `subset: blue` and `kubectl apply`.

### 9.11 Finalize — retire the old environment

Once green is confirmed stable (after 24–48 h in production), scale down blue to save resources:

```bash
kubectl scale deployment intuitive-search-blue --replicas=0
```

For the next release cycle, blue becomes the new version and green becomes the current live — swap the roles.

### 9.12 Blue-green vs. Canary — when to use which

| | Blue-Green | Canary |
|---|---|---|
| Switch style | Instant (all-or-nothing) | Gradual (% traffic) |
| Rollback speed | Seconds | Seconds |
| Resource cost | 2× pods running simultaneously | 2× pods during rollout only |
| Best for | Schema changes, breaking API changes, compliance deployments | Feature experiments, risky changes, performance validation |
| Risk | All users hit new version at once | Only a subset is exposed initially |

---

## 10. Verify the Setup

### 10.1 Check all Istio resources

```bash
kubectl get gateway,virtualservice,destinationrule -n default
```

### 10.2 Validate with istioctl

```bash
istioctl analyze -n default
```

No errors should be reported. Warnings are usually safe to read and ignore.

### 10.3 Confirm sidecar injection

```bash
kubectl describe pod -l app=intuitive-search | grep -A5 "istio-proxy"
```

### 10.4 Load test with traffic split

```bash
# Send 100 requests; roughly 10 should reach v2
for i in $(seq 1 100); do
  curl -s http://$INGRESS_HOST:$INGRESS_PORT/actuator/health
done | sort | uniq -c
```

Watch which pods are receiving requests:

```bash
kubectl logs -l app=intuitive-search,version=v1 -c backend --tail=20 -f &
kubectl logs -l app=intuitive-search,version=v2 -c backend --tail=20 -f
```

### 10.5 Inspect Envoy config

```bash
# List all proxies
istioctl proxy-status

# Dump full Envoy config for a specific pod
kubectl get pod -l app=intuitive-search,version=v1 -o name | head -1 | xargs -I{} istioctl proxy-config all {}
```

### 10.6 Check mTLS between services

```bash
istioctl authn tls-check $(kubectl get pod -l app=intuitive-search,version=v1 -o jsonpath='{.items[0].metadata.name}') intuitive-search-svc.default.svc.cluster.local
```

---

## 11. Observability (Kiali, Prometheus, Jaeger)

The `demo` profile ships with all three. Access them via port-forward:

```bash
# Kiali — service mesh topology & traffic graph
istioctl dashboard kiali

# Prometheus — raw metrics
istioctl dashboard prometheus

# Jaeger — distributed tracing
istioctl dashboard jaeger

# Grafana — Istio dashboards
istioctl dashboard grafana
```

Each command opens the dashboard in your default browser.

In **Kiali**, navigate to **Graph → Namespace: default** to see the live traffic split between `intuitive-search-v1` and `intuitive-search-v2` in real time.

---

## 12. Cleanup

Remove Istio resources without touching application workloads:

```bash
kubectl delete -f backend/k8s/istio-virtualservice.yaml
kubectl delete -f backend/k8s/istio-destinationrule.yaml
kubectl delete -f backend/k8s/istio-gateway.yaml
kubectl delete -f backend/k8s/deployment-canary.yaml
kubectl label namespace default istio-injection-
kubectl rollout restart deployment/intuitive-search-v1
```

Uninstall Istio entirely:

```bash
istioctl uninstall --purge -y
kubectl delete namespace istio-system
```

---

## Summary of Files to Create

### Shared (required for both strategies)

| File | Purpose |
|------|---------|
| `backend/k8s/service.yaml` | Change `type: LoadBalancer` → `ClusterIP` (edit existing) |
| `backend/k8s/istio-gateway.yaml` | Istio Gateway resource |

### Canary release

| File | Purpose |
|------|---------|
| `backend/k8s/deployment.yaml` | Add `version: v1` label (edit existing) |
| `backend/k8s/deployment-canary.yaml` | Canary Deployment (v2) |
| `backend/k8s/istio-virtualservice.yaml` | VirtualService with 90/10 weighted routing |
| `backend/k8s/istio-destinationrule.yaml` | DestinationRule with stable/canary subsets |

### Blue-green deployment

| File | Purpose |
|------|---------|
| `backend/k8s/deployment-blue.yaml` | Blue Deployment (current live, v1) |
| `backend/k8s/deployment-green.yaml` | Green Deployment (new version, v2) |
| `backend/k8s/istio-virtualservice-bluegreen.yaml` | VirtualService pointing 100% to one subset |
| `backend/k8s/istio-destinationrule-bluegreen.yaml` | DestinationRule with blue/green subsets |
