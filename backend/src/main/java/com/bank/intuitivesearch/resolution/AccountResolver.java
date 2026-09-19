package com.bank.intuitivesearch.resolution;

import com.bank.intuitivesearch.config.SearchProperties;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.bank.intuitivesearch.resolution.CustomerDataRepository.NamedEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/** Resolves "my savings", "salary account", "EUR" against the user's own accounts. */
@Component
public class AccountResolver implements SlotResolver {

    private final CustomerDataRepository repository;
    private final SearchProperties properties;

    public AccountResolver(CustomerDataRepository repository, SearchProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "account";
    }

    @Override
    public ResolvedSlot resolve(SlotDefinition slot, Object rawValue, String userId) {
        String needle = PayeeResolver.normalise(rawValue);
        if (needle.isEmpty()) {
            return ResolvedSlot.unresolved(slot.name(), rawValue);
        }

        SearchProperties.Resolution cfg = properties.getResolution();
        List<NamedEntity> matches = repository.findAccounts(
                userId, needle, cfg.getMinSimilarity(), cfg.getMaxCandidates());

        if (matches.isEmpty()) {
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
                slot.name(), rawValue,
                ambiguous ? null : best.id(),
                best.name(), best.detail(), best.score(), ambiguous, candidates);
    }
}
