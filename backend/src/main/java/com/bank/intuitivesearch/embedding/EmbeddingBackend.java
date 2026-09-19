package com.bank.intuitivesearch.embedding;

import java.util.List;

/**
 * The transport half of embedding: one implementation per provider wire
 * format. The resilience half — circuit breaker, bulkhead, deadline, query
 * cache — lives once in {@link EmbeddingClient} and wraps whichever backend
 * is configured, so switching providers cannot silently lose a guard.
 *
 * <p>Implementations are allowed to throw. The client turns every failure
 * into "no vector for this request", which the pipeline already treats as
 * "run lexical-only".
 */
public interface EmbeddingBackend {

    /** Embeds one search query. Never returns null; length equals the configured dimensions. */
    float[] embedQuery(String text);

    /**
     * Embeds catalogue documents in one request, in input order. Used by the
     * index builder, never on the request path.
     */
    List<float[]> embedDocuments(List<String> texts);

    /** Human-readable provider + model, for health and the admin dashboard. */
    String describe();

    /**
     * False when the backend cannot possibly work — no base URL or key yet.
     * The client then treats embeddings as disabled instead of failing every
     * call, so a fresh checkout without credentials still runs lexical-only.
     */
    default boolean isConfigured() {
        return true;
    }
}
