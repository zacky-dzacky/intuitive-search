package com.bank.intuitivesearch.api;

import com.bank.intuitivesearch.model.SearchRequest;
import com.bank.intuitivesearch.model.SearchResponse;
import com.bank.intuitivesearch.registry.FeatureRegistry;
import com.bank.intuitivesearch.search.SearchOrchestrator;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search API.
 *
 * <p>Two endpoints, matching how the UI actually calls them:
 * <ul>
 *   <li>{@code GET /api/search/suggest} — Stage 1 only, safe to hit on every
 *       keystroke. Cannot reach the LLM.</li>
 *   <li>{@code POST /api/search} — the full pipeline, called on debounce or
 *       Enter.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "${search.cors-origins:*}")
public class SearchController {

    private final SearchOrchestrator orchestrator;
    private final FeatureRegistry registry;
    private final SearchAuditWriter audit;

    public SearchController(SearchOrchestrator orchestrator, FeatureRegistry registry,
                            SearchAuditWriter audit) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.audit = audit;
    }

    /**
     * Full pipeline. The response's {@code action} is at most
     * {@code prefill_form} — this endpoint cannot move money.
     *
     * <p>Only this endpoint is audited: typeahead would log every prefix the
     * user typed on the way to a query, which is noise, not analytics.
     */
    @PostMapping
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        SearchResponse response = orchestrator.search(request);
        audit.record(request, response);
        return response;
    }

    /**
     * Typeahead. Forces {@code allowExtraction=false}, so no amount of
     * client-side misuse can trigger an LLM call per keystroke.
     */
    @GetMapping("/suggest")
    public SearchResponse suggest(@RequestParam("q") String query,
                                  @RequestParam(value = "userId", required = false) String userId) {
        return orchestrator.search(new SearchRequest(query, userId, false));
    }

    /** Registry introspection — useful when adding feature #51. */
    @GetMapping("/features")
    public ResponseEntity<Map<String, Object>> features() {
        List<Map<String, Object>> features = registry.all().stream()
                .map(f -> Map.<String, Object>of(
                        "feature_id", f.featureId(),
                        "display_name", f.displayName(),
                        "category", f.category(),
                        "route", f.route(),
                        "has_params", f.hasParams(),
                        "slots", f.slots().stream().map(s -> Map.of(
                                "name", s.name(),
                                "type", s.type(),
                                "required", s.required(),
                                "resolver", s.resolver())).toList()))
                .toList();
        return ResponseEntity.ok(Map.of("count", features.size(), "features", features));
    }
}
