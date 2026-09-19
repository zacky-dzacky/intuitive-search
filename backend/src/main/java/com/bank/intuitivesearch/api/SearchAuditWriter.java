package com.bank.intuitivesearch.api;

import com.bank.intuitivesearch.model.SearchRequest;
import com.bank.intuitivesearch.model.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code search_audit} row per full search, off the request thread.
 *
 * <p>This is a record of what search <em>proposed</em>, never of anything
 * executed — the table feeds the admin dashboard's Analytics page (LLM
 * invocation rate, latency split, unresolved queries) and nothing else.
 *
 * <p>Fire-and-forget on purpose: the row is diagnostics, and a search that
 * worked must not fail because the log write did.
 */
@Component
public class SearchAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(SearchAuditWriter.class);

    private final JdbcClient jdbc;

    public SearchAuditWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void record(SearchRequest request, SearchResponse response) {
        SearchResponse.Diagnostics diagnostics = response.diagnostics();
        boolean llmInvoked = diagnostics != null && diagnostics.llmInvoked();
        Long latencyMs = diagnostics == null ? null : diagnostics.totalMs();
        try {
            jdbc.sql("""
                    INSERT INTO search_audit
                      (user_id, query, matched_feature, confidence, action, llm_invoked, latency_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)
                    .param(request.effectiveUserId())
                    .param(request.query())
                    .param(response.matchedFeature())
                    .param((float) response.confidence())
                    .param(response.action())
                    .param(llmInvoked)
                    .param(latencyMs)
                    .update();
        } catch (DataAccessException e) {
            log.warn("search_audit write failed for query='{}': {}", request.query(), e.getMessage());
        }
    }
}
