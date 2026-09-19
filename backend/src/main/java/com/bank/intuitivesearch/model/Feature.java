package com.bank.intuitivesearch.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A row of the feature registry. Pure data — there is no per-feature code
 * anywhere in this service.
 */
public record Feature(
        String featureId,
        String displayName,
        String description,
        String category,
        String route,
        List<String> keywords,
        List<String> aliases,
        boolean hasParams,
        List<SlotDefinition> slots) {

    public Feature {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    /**
     * Every word that "belongs" to this feature. Used by signal detection to
     * work out which query tokens are extra — i.e. potential parameters.
     */
    public Set<String> vocabulary() {
        return Stream.of(
                        Stream.of(displayName),
                        keywords.stream(),
                        aliases.stream())
                .flatMap(s -> s)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(s -> Stream.of(s.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The feature's own naming terms, each split into tokens.
     *
     * <p>Used by the containment channel: a query that literally contains one
     * of these ("…transfer…") names this feature, however much parameter text
     * surrounds it. That distinction is what separates "Transfer Money" from
     * "Transfer Receipt" on the query {@code transfer to mom's 10000 usd} —
     * full-text ranking alone favours whichever document repeats the word
     * most, which is the wrong answer.
     */
    public List<List<String>> namingTerms() {
        return Stream.of(Stream.of(displayName), keywords.stream(), aliases.stream())
                .flatMap(s -> s)
                .filter(s -> s != null && !s.isBlank())
                .map(term -> Stream.of(term.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+"))
                        .filter(t -> !t.isEmpty())
                        .toList())
                .filter(tokens -> !tokens.isEmpty())
                .distinct()
                .toList();
    }

    /** Exact surface forms that should short-circuit ranking ("trf"). */
    public Set<String> exactForms() {
        return Stream.concat(Stream.of(displayName), aliases.stream())
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toUnmodifiableSet());
    }

    public SlotDefinition slot(String name) {
        return slots.stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /** Stage 2 can only ever fire when this is true. */
    public boolean acceptsParameters() {
        return hasParams && !slots.isEmpty();
    }

    /** True when some slot can be filled by a bare word from the query. */
    public boolean acceptsFreeText() {
        return slots.stream().anyMatch(SlotDefinition::isFreeText);
    }
}
