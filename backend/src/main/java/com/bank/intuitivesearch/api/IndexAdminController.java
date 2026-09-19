package com.bank.intuitivesearch.api;

import com.bank.intuitivesearch.embedding.EmbeddingClient;
import com.bank.intuitivesearch.index.FeatureIndex;
import com.bank.intuitivesearch.index.FeatureIndexer;
import com.bank.intuitivesearch.index.IndexStats;
import com.bank.intuitivesearch.registry.FeatureRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator view of the search index — what the admin dashboard's
 * "Search index" page reads and its "Rebuild" button calls.
 *
 * <p>The index keeps itself current (every registry refresh rebuilds it if
 * anything changed), so the rebuild endpoint exists for two cases: an
 * operator who does not want to wait out the refresh interval after an edit,
 * and a forced re-embed after the embedding model or its dimensions change.
 *
 * <p>With several backend replicas this refreshes the one that answered; the
 * others converge on their next scheduled refresh.
 */
@RestController
@RequestMapping("/api/admin/index")
@CrossOrigin(origins = "${search.cors-origins:*}")
public class IndexAdminController {

    private final FeatureIndex index;
    private final FeatureRegistry registry;
    private final EmbeddingClient embeddingClient;

    public IndexAdminController(FeatureIndex index, FeatureRegistry registry,
                                EmbeddingClient embeddingClient) {
        this.index = index;
        this.registry = registry;
        this.embeddingClient = embeddingClient;
    }

    @GetMapping
    public Map<String, Object> status() {
        IndexStats stats = index.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", stats != null);
        out.put("features", stats == null ? 0 : stats.features());
        out.put("with_vector", stats == null ? 0 : stats.withVector());
        out.put("dimensions", stats == null ? null : stats.dimensions());
        out.put("embedding_model", stats == null ? null : stats.embeddingModel());
        out.put("embedding_enabled", embeddingClient.isEnabled());
        out.put("embedding_degraded", embeddingClient.isDegraded());
        out.put("built_at", stats == null ? null : stats.builtAt());
        out.put("build_ms", stats == null ? null : stats.buildMs());
        out.put("embedded_in_build", stats == null ? null : stats.embeddedInBuild());
        out.put("last_error", stats == null ? null : stats.lastError());
        return out;
    }

    /** @param force re-embed every feature, not just the ones whose text changed */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@RequestParam(value = "force", defaultValue = "false") boolean force) {
        FeatureIndexer.SyncReport report = registry.refresh(force);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rebuilt", report.rebuilt());
        out.put("features", report.features());
        out.put("embedded", report.embedded());
        out.put("with_vector", report.withVector());
        out.put("took_ms", report.tookMs());
        out.put("error", report.error());
        return out;
    }
}
