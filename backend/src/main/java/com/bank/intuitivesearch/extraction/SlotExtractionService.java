package com.bank.intuitivesearch.extraction;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.Feature;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stage 2 entry point.
 *
 * <p>Exactly one {@link SlotExtractor} is active, chosen by
 * {@code search.llm.provider}. This class owns the invariant the rest of the
 * pipeline depends on: <b>extraction never throws and never blocks a
 * search</b>. Whatever goes wrong, the caller gets a result object and the
 * user gets their screen.
 */
@Service
public class SlotExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SlotExtractionService.class);

    private final SlotExtractor extractor;
    private final SearchProperties properties;

    public SlotExtractionService(List<SlotExtractor> extractors, SearchProperties properties) {
        this.properties = properties;
        if (extractors.isEmpty()) {
            log.warn("No SlotExtractor bean is active for search.llm.provider='{}'; "
                    + "Stage 2 is disabled and every query will navigate.",
                    properties.getLlm().getProvider());
            this.extractor = null;
        } else {
            if (extractors.size() > 1) {
                log.warn("{} SlotExtractor beans active; using '{}'",
                        extractors.size(), extractors.get(0).providerName());
            }
            this.extractor = extractors.get(0);
            log.info("Stage 2 slot extraction provider: {}", this.extractor.providerName());
        }
    }

    public boolean isAvailable() {
        return extractor != null;
    }

    public String providerName() {
        return extractor == null ? "none" : extractor.providerName();
    }

    /**
     * Callers must have already checked {@link Feature#acceptsParameters()} and
     * the signal gate; this method re-checks the schema guard anyway, because
     * "the LLM only ever sees features that declare slots" is a safety
     * property worth enforcing in more than one place.
     */
    public ExtractionResult extract(Feature feature, String query) {
        if (extractor == null) {
            return ExtractionResult.empty("none", "no extraction provider configured");
        }
        if (!feature.acceptsParameters()) {
            return ExtractionResult.empty(providerName(),
                    "feature declares no parameters; extraction skipped");
        }
        long started = System.nanoTime();
        try {
            ExtractionResult result = extractor.extract(feature, query);
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            if (tookMs > properties.getLlm().getTimeoutMs()) {
                log.warn("Slot extraction for '{}' took {}ms (budget {}ms)",
                        feature.featureId(), tookMs, properties.getLlm().getTimeoutMs());
            }
            return result == null
                    ? ExtractionResult.empty(providerName(), "extractor returned null")
                    : result;
        } catch (RuntimeException e) {
            // Belt and braces: an extractor implementation is contractually
            // forbidden from throwing, but a bug here must not lose the
            // navigation result the user is entitled to.
            log.error("Slot extractor '{}' threw; degrading to navigation",
                    providerName(), e);
            return new ExtractionResult(false, Map.of(), providerName(),
                    List.of("extraction failed: " + e.getClass().getSimpleName()));
        }
    }
}
