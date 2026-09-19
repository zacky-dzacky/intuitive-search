package com.bank.intuitivesearch.resolution;

import com.bank.intuitivesearch.model.SlotDefinition;

/**
 * Stage 3 strategy, selected by the {@code resolver} field on a slot
 * definition. Adding a feature never requires a new resolver — the built-in
 * set (payee, account, currency, amount, date, period, phone, none) covers the
 * shapes a banking form actually needs, and a feature picks one per slot as
 * data.
 */
public interface SlotResolver {

    /** The {@code resolver} value in {@code features.slots} this handles. */
    String id();

    /**
     * @param userId whose data to resolve against — resolution is always
     *               scoped to the authenticated customer
     */
    ResolvedSlot resolve(SlotDefinition slot, Object rawValue, String userId);
}
