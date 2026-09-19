package com.bank.intuitivesearch.search;

import com.bank.intuitivesearch.config.SearchProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
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
import org.springframework.web.client.RestClient;

/**
 * Client for the Python embedding microservice.
 *
 * <p>The embedding model is the slowest thing in the pipeline and the only
 * remote dependency on the hot path, so this class exists as much to contain
 * it as to call it. Three guards, in order:
 *
 * <ol>
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

    private final RestClient restClient;
    private final SearchProperties properties;
    private final ExecutorService executor;
    private final Semaphore inFlight;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilNanos = new AtomicLong();
    private volatile boolean degraded;

    public EmbeddingClient(RestClient embeddingRestClient,
                           SearchProperties properties,
                           ExecutorService searchExecutor) {
        this.restClient = embeddingRestClient;
        this.properties = properties;
        this.executor = searchExecutor;
        this.inFlight = new Semaphore(
                Math.max(1, properties.getEmbedding().getMaxConcurrent()), true);
    }

    /**
     * @return the query embedding, or empty when embeddings are disabled, the
     *         circuit is open, the bulkhead is full, or the deadline passed.
     *         Callers treat empty as "run lexical-only" — never as an error.
     */
    public Optional<float[]> embedQuery(String text) {
        SearchProperties.Embedding config = properties.getEmbedding();
        if (!config.isEnabled()) {
            return Optional.empty();
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

    private Optional<float[]> call(String text) {
        try {
            EmbedResponse response = restClient.post()
                    .uri("/embed")
                    .body(new EmbedRequest(text, true))
                    .retrieve()
                    .body(EmbedResponse.class);

            if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
                return Optional.empty();
            }
            int expected = properties.getEmbedding().getDimensions();
            if (response.embedding().size() != expected) {
                log.warn("Embedding service returned {} dims but features.embedding is VECTOR({}); "
                        + "skipping vector search", response.embedding().size(), expected);
                return Optional.empty();
            }
            return Optional.of(toFloatArray(response.embedding()));
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
            log.info("Embedding service recovered; vector search re-enabled");
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
                log.warn("Embedding service failing ({}); vector search disabled for {}ms, "
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

    private static float[] toFloatArray(List<Double> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }

    // Field names are pinned explicitly rather than relying on the global
    // naming strategy — this is a wire contract with a separate service.
    record EmbedRequest(
            @JsonProperty("text") String text,
            @JsonProperty("is_query") boolean isQuery) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(
            @JsonProperty("embedding") List<Double> embedding,
            @JsonProperty("model") String model,
            @JsonProperty("dimensions") Integer dimensions) {}
}
