package com.bank.intuitivesearch.resolution;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.bank.intuitivesearch.resolution.CustomerDataRepository.NamedEntity;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Resolves an informal reference ("mom", "mom's", "jane") against the
 * customer's saved payees using pg_trgm similarity.
 *
 * <p>When the top two candidates are close, the slot is marked ambiguous and
 * both are returned. The form then asks the user which one — it does not pick.
 */
@Component
public class PayeeResolver implements SlotResolver {

    private final CustomerDataRepository repository;
    private final SearchProperties properties;

    public PayeeResolver(CustomerDataRepository repository, SearchProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "payee";
    }

    @Override
    public ResolvedSlot resolve(SlotDefinition slot, Object rawValue, String userId) {
        String needle = normalise(rawValue);
        if (needle.isEmpty()) {
            return ResolvedSlot.unresolved(slot.name(), rawValue);
        }

        SearchProperties.Resolution cfg = properties.getResolution();
        List<NamedEntity> matches = repository.findPayees(
                userId, needle, cfg.getMinSimilarity(), cfg.getMaxCandidates());

        if (matches.isEmpty()) {
            // Unknown payee is a perfectly normal outcome — the user may be
            // paying someone new. The form opens with the name pre-filled and
            // the account fields blank.
            return ResolvedSlot.unresolved(slot.name(), rawValue);
        }

        NamedEntity best = matches.get(0);
        boolean ambiguous = matches.size() > 1
                && (best.score() - matches.get(1).score()) < cfg.getAmbiguityMargin();

        List<ResolvedSlot.Candidate> candidates = ambiguous
                ? matches.stream()
                        .map(m -> new ResolvedSlot.Candidate(m.id(), m.name(), m.detail(), m.score()))
                        .toList()
                : List.of();

        return new ResolvedSlot(
                slot.name(),
                rawValue,
                ambiguous ? null : best.id(),
                best.name(),
                best.detail(),
                best.score(),
                ambiguous,
                candidates);
    }

    static String normalise(Object rawValue) {
        if (rawValue == null) {
            return "";
        }
        return String.valueOf(rawValue)
                .toLowerCase(Locale.ROOT)
                .replaceAll("'s\\b", "")
                .replaceAll("[^\\p{Alnum}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
