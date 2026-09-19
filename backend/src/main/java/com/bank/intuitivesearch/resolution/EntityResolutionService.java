package com.bank.intuitivesearch.resolution;

import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stage 3 — turn extracted strings into things that exist.
 *
 * <p>Dispatch is by the slot's declared {@code resolver}, so this class has no
 * knowledge of any particular feature. An unrecognised resolver id falls back
 * to pass-through and logs once, which means a typo in a registry row degrades
 * to "value shown as typed" rather than an error.
 */
@Service
public class EntityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(EntityResolutionService.class);

    private final Map<String, SlotResolver> resolvers;

    public EntityResolutionService(List<SlotResolver> resolverBeans) {
        this.resolvers = resolverBeans.stream()
                .collect(Collectors.toUnmodifiableMap(SlotResolver::id, Function.identity()));
        log.info("Stage 3 resolvers registered: {}", this.resolvers.keySet());
    }

    /**
     * @param extracted raw slot values from Stage 2
     * @param userId    resolution is always scoped to this customer
     * @return slot name -&gt; resolved slot, in the order the feature declares
     *         them so the form fills top to bottom
     */
    public Map<String, ResolvedSlot> resolveAll(Feature feature,
                                                Map<String, Object> extracted,
                                                String userId) {
        Map<String, ResolvedSlot> resolved = new LinkedHashMap<>();
        for (SlotDefinition slot : feature.slots()) {
            Object value = extracted.get(slot.name());
            if (value == null) {
                continue;
            }
            resolved.put(slot.name(), resolveOne(slot, value, userId));
        }
        return resolved;
    }

    private ResolvedSlot resolveOne(SlotDefinition slot, Object value, String userId) {
        SlotResolver resolver = resolvers.get(slot.resolver());
        if (resolver == null) {
            log.warn("Slot '{}' declares unknown resolver '{}'; passing the value through "
                    + "unresolved. Known resolvers: {}",
                    slot.name(), slot.resolver(), resolvers.keySet());
            return ResolvedSlot.scalar(slot.name(), value);
        }
        try {
            ResolvedSlot result = resolver.resolve(slot, value, userId);
            return result == null ? ResolvedSlot.scalar(slot.name(), value) : result;
        } catch (RuntimeException e) {
            // A resolver failure must not lose the extracted value — show it
            // raw and let the user confirm.
            log.error("Resolver '{}' failed for slot '{}'", slot.resolver(), slot.name(), e);
            return ResolvedSlot.unresolved(slot.name(), value);
        }
    }
}
