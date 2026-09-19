package com.bank.intuitivesearch.search;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.extraction.ExtractionResult;
import com.bank.intuitivesearch.extraction.SlotExtractionService;
import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.FeatureMatch;
import com.bank.intuitivesearch.model.SearchRequest;
import com.bank.intuitivesearch.model.SearchResponse;
import com.bank.intuitivesearch.model.SignalReport;
import com.bank.intuitivesearch.resolution.EntityResolutionService;
import com.bank.intuitivesearch.resolution.ResolvedSlot;
import com.bank.intuitivesearch.signal.SignalDetector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The 3-tier pipeline.
 *
 * <pre>
 *   query
 *     -> [1] hybrid search        (always, fast)
 *     -> [1b] signal detection    (always, microseconds, no LLM)
 *          |- no params / no signal -> navigate. STOP.
 *     -> [2] LLM slot extraction  (conditional)
 *     -> [3] entity resolution    (DB lookup)
 *     -> pre-filled form for the user to confirm
 * </pre>
 *
 * <p><b>Nothing here executes a transaction, and nothing downstream is
 * authorised to.</b> The strongest action this service can return is
 * {@code prefill_form}.
 */
@Service
public class SearchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SearchOrchestrator.class);

    private final HybridSearchService hybridSearch;
    private final SignalDetector signalDetector;
    private final SlotExtractionService extractionService;
    private final EntityResolutionService resolutionService;
    private final SearchProperties properties;

    public SearchOrchestrator(HybridSearchService hybridSearch,
                              SignalDetector signalDetector,
                              SlotExtractionService extractionService,
                              EntityResolutionService resolutionService,
                              SearchProperties properties) {
        this.hybridSearch = hybridSearch;
        this.signalDetector = signalDetector;
        this.extractionService = extractionService;
        this.resolutionService = resolutionService;
        this.properties = properties;
    }

    public SearchResponse search(SearchRequest request) {
        long startedNs = System.nanoTime();
        Map<String, Long> timings = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // ---- Stage 1: hybrid retrieval ---------------------------------
        long t0 = System.nanoTime();
        List<FeatureMatch> matches = hybridSearch.search(request.query());
        timings.put("stage1_hybrid_search", elapsedMs(t0));

        if (matches.isEmpty()) {
            return noMatch(timings, startedNs, warnings);
        }

        FeatureMatch best = matches.get(0);
        Feature feature = best.feature();

        // Low confidence: don't commit to a feature, offer choices instead.
        if (best.confidence() < properties.getNavigateThreshold()) {
            return suggestions(matches, timings, startedNs, warnings);
        }

        // ---- Signal detection (generic, always runs, cheap) ------------
        long t1 = System.nanoTime();
        SignalReport signals = signalDetector.detect(request.query(), feature);
        timings.put("signal_detection", elapsedMs(t1));

        // "Stage 1 confidence is high" means "we know which feature this is".
        // Absolute similarity alone is the wrong measure of that: every
        // similarity channel is diluted by the parameter text, so precisely
        // the queries that need Stage 2 score lowest on it. A clear margin
        // over the runner-up says the same thing without that bias, so either
        // qualifies.
        double margin = matches.size() > 1
                ? best.confidence() - matches.get(1).confidence()
                : best.confidence();
        boolean confidentFeature = best.confidence() >= properties.getExtractionThreshold()
                || (best.confidence() >= properties.getNavigateThreshold()
                        && margin >= properties.getExtractionMargin());
        boolean gateOpen = signals.shouldExtract() && confidentFeature;

        if (!request.extractionAllowed()) {
            // Search-as-you-type: Stage 1 only, by contract.
            return navigate(best, matches, timings, startedNs, signals,
                    "extraction disabled by client (typeahead)", warnings);
        }
        if (!gateOpen) {
            String stoppedAt = !signals.shouldExtract()
                    ? "signal_detection: " + signals.reason()
                    : "confidence %.2f < extraction threshold %.2f"
                            .formatted(best.confidence(), properties.getExtractionThreshold());
            return navigate(best, matches, timings, startedNs, signals, stoppedAt, warnings);
        }

        // ---- Stage 2: LLM slot extraction ------------------------------
        long t2 = System.nanoTime();
        ExtractionResult extraction = extractionService.extract(feature, request.query());
        timings.put("stage2_llm_extraction", elapsedMs(t2));
        warnings.addAll(extraction.warnings());

        if (!extraction.hasSlots()) {
            // The model found nothing usable — the user still gets their screen.
            return navigate(best, matches, timings, startedNs, signals,
                    "extraction produced no usable slots", warnings, true);
        }

        // ---- Stage 3: entity resolution --------------------------------
        long t3 = System.nanoTime();
        Map<String, ResolvedSlot> resolved = resolutionService.resolveAll(
                feature, extraction.slots(), request.effectiveUserId());
        timings.put("stage3_entity_resolution", elapsedMs(t3));

        Map<String, Object> slotPayload = new LinkedHashMap<>();
        resolved.forEach((name, slot) -> slotPayload.put(name, slot.toJsonValue()));

        log.debug("query='{}' -> feature={} confidence={} slots={}",
                request.query(), feature.featureId(), best.confidence(), slotPayload.keySet());

        return new SearchResponse(
                feature.featureId(),
                feature.displayName(),
                feature.route(),
                round(best.confidence()),
                true,
                slotPayload,
                // Pre-fill and wait. Never execute.
                SearchResponse.ACTION_PREFILL,
                toSuggestions(matches),
                new SearchResponse.Diagnostics(
                        timings, elapsedMs(startedNs), true, extractionService.providerName(),
                        signals.score(), signals.signals(), "stage3_entity_resolution",
                        List.copyOf(warnings), breakdown(best)));
    }

    // ------------------------------------------------------------------
    // Terminal outcomes
    // ------------------------------------------------------------------

    private SearchResponse navigate(FeatureMatch best,
                                    List<FeatureMatch> matches,
                                    Map<String, Long> timings,
                                    long startedNs,
                                    SignalReport signals,
                                    String stoppedAt,
                                    List<String> warnings) {
        return navigate(best, matches, timings, startedNs, signals, stoppedAt, warnings, false);
    }

    private SearchResponse navigate(FeatureMatch best,
                                    List<FeatureMatch> matches,
                                    Map<String, Long> timings,
                                    long startedNs,
                                    SignalReport signals,
                                    String stoppedAt,
                                    List<String> warnings,
                                    boolean llmInvoked) {
        return new SearchResponse(
                best.featureId(),
                best.feature().displayName(),
                best.feature().route(),
                round(best.confidence()),
                false,
                Map.of(),
                SearchResponse.ACTION_NAVIGATE,
                toSuggestions(matches),
                new SearchResponse.Diagnostics(
                        timings, elapsedMs(startedNs), llmInvoked,
                        llmInvoked ? extractionService.providerName() : "none",
                        signals.score(), signals.signals(), stoppedAt,
                        List.copyOf(warnings), breakdown(best)));
    }

    private SearchResponse suggestions(List<FeatureMatch> matches,
                                       Map<String, Long> timings,
                                       long startedNs,
                                       List<String> warnings) {
        FeatureMatch best = matches.get(0);
        return new SearchResponse(
                null, null, null, round(best.confidence()), false, Map.of(),
                SearchResponse.ACTION_SUGGEST,
                toSuggestions(matches),
                new SearchResponse.Diagnostics(
                        timings, elapsedMs(startedNs), false, "none", 0, List.of(),
                        "confidence below navigate threshold", List.copyOf(warnings),
                        breakdown(best)));
    }

    private SearchResponse noMatch(Map<String, Long> timings, long startedNs, List<String> warnings) {
        return new SearchResponse(
                null, null, null, 0, false, Map.of(),
                SearchResponse.ACTION_NONE, List.of(),
                new SearchResponse.Diagnostics(
                        timings, elapsedMs(startedNs), false, "none", 0, List.of(),
                        "no feature matched", List.copyOf(warnings), Map.of()));
    }

    private static List<SearchResponse.Suggestion> toSuggestions(List<FeatureMatch> matches) {
        return matches.stream()
                .limit(5)
                .map(m -> new SearchResponse.Suggestion(
                        m.featureId(),
                        m.feature().displayName(),
                        m.feature().route(),
                        m.feature().category(),
                        round(m.confidence())))
                .toList();
    }

    private static Map<String, Double> breakdown(FeatureMatch match) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("keyword", round(match.keywordScore()));
        out.put("trigram", round(match.trigramScore()));
        out.put("vector", round(match.vectorScore()));
        out.put("containment", round(match.containmentScore()));
        return out;
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
