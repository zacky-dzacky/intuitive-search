Got it — Q&A only, no implementation. To recap the answer:

Concurrency: yes. The endpoints are sync def (app.py:142, app.py:161), so Starlette runs them in a threadpool and inference never blocks the event loop. TORCH_NUM_THREADS=1 + 4 worker processes is the right shape for CPU-bound work, and the Dockerfile documents the measurement behind it.

High throughput: not as deployed. Five things stand in the way:

1. No admission control. Anyio's threadpool defaults to 40 threads per worker and there's no --limit-concurrency, so a pod admits ~160 concurrent inferences against a 2-core limit. Nothing sheds — requests just get slower. The only limiter in the system is the caller-side Semaphore(16) in EmbeddingClient.java:65, which doesn't cover other clients.
2. /health is sync (app.py:131), competing with inference for those same threads. Under saturation the liveness probe fails and k8s restarts a busy-but-healthy pod, costing a 60-90s cold start.
3. Memory vs. worker count. Uvicorn spawns rather than forks, so 4 workers load torch independently with no COW sharing — ~1.4-1.8GB against a 1500Mi limit.
4. /embed/batch has no per-item length cap (MAX_TEXT_CHARS only guards EmbedRequest.text), and shares a process pool with 400ms-budget queries.
5. @lru_cache is per-process, so 4 workers split the hit rate, and it has no single-flight for concurrent identical misses.

Plus replicas: 1, no HPA, no metrics.

The plan file at ~/.claude/plans/is-this-fastapi-endpoint-shimmering-trinket.md has the details if you ever want them, but I've made no changes to the project. Happy to go deeper on any of the five.