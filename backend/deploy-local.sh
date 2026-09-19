#!/usr/bin/env bash
set -euo pipefail

IMAGE="intuitive-search-service:0.0.1"
REGISTRY="localhost:5000"
REMOTE_IMAGE="$REGISTRY/$IMAGE"

echo "==> Building Docker image..."
docker build -t "$IMAGE" .

echo "==> Pushing to local registry ($REGISTRY)..."
docker tag "$IMAGE" "$REMOTE_IMAGE"
docker push "$REMOTE_IMAGE"

echo "==> Applying manifests..."
kubectl apply -f k8s/

echo "==> Restarting deployment..."
kubectl rollout restart deployment/intuitive-search

echo "==> Waiting for rollout..."
kubectl rollout status deployment/intuitive-search

echo "==> Done. Service endpoint:"
kubectl get svc intuitive-search-svc
