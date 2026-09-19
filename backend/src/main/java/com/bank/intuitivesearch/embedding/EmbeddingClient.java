package com.bank.intuitivesearch.embedding;

import com.bank.intuitivesearch.config.SearchProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The embedding call, wrapped in everything that keeps it from hurting.
 *
 * <p>The embedding model is the slowest thing in the pipeline and the only
 * remote dependency on the hot path, so this class exists as much to contain
 * it as to call it. The transport is an {@link EmbeddingBackend} chosen by
 * configuration; the guards below apply to every backend equally:
 *
 * <ol>
 *   <li><b>Query cache</b> — typeahead sends "tra", "tran", "trans"… and the
 *       same handful of full queries over and over. With a hosted model every
 *       miss is latency <em>and</em> money, so recent queries are kept.</li>
 *   <li><b>Circuit breaker</b> — after repeated failures, stop calling
 *       entirely for a cooldown. Without this, a sick service costs every
 *       single request a full timeout.</li>
 *   <li><b>Bulkhead</b> — cap in-flight calls. Beyond the cap we skip the
 *       vector channel immediately rather than queue for it.</li>
 *   <li><b>Hard deadline</b> — the wait is bounded end to end, including time
 *       spent queued for an HTTP connection.</li>
 * </ol>
 *
 * <p>That last point is the one that matters and the one a plain read timeout
 * does not give you. A read timeout starts when the request is written to the
 * wire; time spent waiting for a connection from the client's pool is invisible
 * to it. Under load that is where nearly all the time goes — measured here as
 * p50 of 10.7s against a service with a 400ms "timeout", with the timeout
 * never once firing. Bounding the whole operation is what makes the fallback
 * to lexical-only search actually happen.
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final EmbeddingBackend backend;
    private final SearchProperties properties;
    private final ExecutorService executor;
    private final Semaphore inFlight;
    private final Map<String, float[]> queryCache;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilNanos = new AtomicLong();
    private volatile boolean degraded;

    public EmbeddingClient(EmbeddingBackend backend,
                           SearchProperties properties,
                           ExecutorService searchExecutor) {
        this.backend = backend;
        this.properties = properties;
        this.executor = searchExecutor;
        this.inFlight = new Semaphore(
                Math.max(1, properties.getEmbedding().getMaxConcurrent()), true);
        int cacheSize = Math.max(0, properties.getEmbedding().getQueryCacheSize());
        this.queryCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > cacheSize;
            }
        });
    }

    /**
     * @return the query embedding, or empty when embeddings are disabled, the
     *         circuit is open, the bulkhead is full, or the deadline passed.
     *         Callers treat empty as "run lexical-only" — never as an error.
     */
    public Optional<float[]> embedQuery(String text) {
        SearchProperties.Embedding config = properties.getEmbedding();
        if (!isEnabled()) {
            return Optional.empty();
        }
        float[] cached = queryCache.get(text);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (circuitOpen()) {
            return Optional.empty();
        }
        // Shed rather than queue: a request that waits for a permit has
        // already blown the budget it was waiting to spend.
        if (!inFlight.tryAcquire()) {
            return Optional.empty();
        }

        Future<Optional<float[]>> pending = executor.submit(() -> {
            try {
                return call(text);
            } finally {
                // Released by the task, not the caller — an abandoned call is
                // still occupying the service, and the bulkhead must reflect
                // reality rather than our patience.
                inFlight.release();
            }
        });

        try {
            Optional<float[]> result = pending.get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result.isPresent()) {
                onSuccess();
                if (config.getQueryCacheSize() > 0) {
                    queryCache.put(text, result.get());
                }
            }
            return result;
        } catch (TimeoutException e) {
            pending.cancel(true);
            onFailure("deadline of " + config.getTimeoutMs() + "ms exceeded");
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            onFailure(e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Embeds catalogue documents for the index builder. Not on the request
     * path, so none of the hot-path guards apply: it may take seconds, and a
     * failure is the caller's to report — the builder keeps whatever vectors
     * it already has and retries on the next refresh.
     *
     * @throws IllegalStateException when embeddings are disabled
     */
    public List<float[]> embedDocuments(List<String> texts) {
        if (!isEnabled()) {
            throw new IllegalStateException("embeddings are disabled or the provider is not configured");
        }
        return backend.embedDocuments(texts);
    }

    private Optional<float[]> call(String text) {
        try {
            return Optional.of(backend.embedQuery(text));
        } catch (Exception e) {
            onFailure(e.getMessage());
            return Optional.empty();
        }
    }

    private boolean circuitOpen() {
        long openUntil = circuitOpenUntilNanos.get();
        return openUntil != 0 && System.nanoTime() < openUntil;
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        circuitOpenUntilNanos.set(0);
        if (degraded) {
            degraded = false;
            log.info("Embedding provider recovered; vector search re-enabled");
        }
    }

    private void onFailure(String reason) {
        SearchProperties.Embedding config = properties.getEmbedding();
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= config.getCircuitBreakerFailures()) {
            circuitOpenUntilNanos.set(
                    System.nanoTime() + config.getCircuitOpenMs() * 1_000_000L);
            if (!degraded) {
                degraded = true;
                log.warn("Embedding provider failing ({}); vector search disabled for {}ms, "
                        + "falling back to lexical-only retrieval", reason, config.getCircuitOpenMs());
            }
        }
    }

    /** True while the vector channel is being skipped. Surfaced on /actuator/health. */
    public boolean isDegraded() {
        return degraded || circuitOpen();
    }

    public int availableCapacity() {
        return inFlight.availablePermits();
    }

    /** Embeddings are on and the provider has what it needs to be called. */
    public boolean isEnabled() {
        return properties.getEmbedding().isEnabled() && backend.isConfigured();
    }

    public String describeBackend() {
        return backend.describe();
    }
}
