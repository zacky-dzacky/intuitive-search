package com.bank.intuitivesearch.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.intuitivesearch.model.SlotDefinition;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 3 normalisation. The point of these is that a wrong model output
 * becomes a blank field the user fills in — never a plausible-looking wrong
 * pre-fill.
 */
class ScalarSlotResolversTest {

    private final ScalarSlotResolvers resolvers = new ScalarSlotResolvers();

    @Test
    @DisplayName("relative and absolute dates normalise to ISO-8601")
    void normalisesDates() {
        assertThat(ScalarSlotResolvers.parseDate("today")).isEqualTo(LocalDate.now());
        assertThat(ScalarSlotResolvers.parseDate("tomorrow")).isEqualTo(LocalDate.now().plusDays(1));
        assertThat(ScalarSlotResolvers.parseDate("2026-03-15")).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(ScalarSlotResolvers.parseDate("whenever")).isNull();
    }

    @Test
    @DisplayName("statement periods normalise to YYYY-MM")
    void normalisesPeriods() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);

        assertThat(ScalarSlotResolvers.parsePeriod("2026-03")).isEqualTo("2026-03");
        assertThat(ScalarSlotResolvers.parsePeriod("march 2025")).isEqualTo("2025-03");
        assertThat(ScalarSlotResolvers.parsePeriod("last month"))
                .isEqualTo("%04d-%02d".formatted(lastMonth.getYear(), lastMonth.getMonthValue()));
        assertThat(ScalarSlotResolvers.parsePeriod("sometime")).isNull();
    }

    @Test
    @DisplayName("a non-ISO currency is rejected rather than pre-filled")
    void rejectsBadCurrency() {
        SlotDefinition slot = slot("currency", "string", "currency");

        // "dollars" is not an ISO code, so the field stays as typed for the
        // user to correct rather than being guessed at.
        assertThat(resolvers.currencyResolver().resolve(slot, "dollars", "user_1").toJsonValue())
                .isEqualTo("dollars");
        // A valid code is normalised to upper case on the way to the form.
        assertThat(resolvers.currencyResolver().resolve(slot, "usd", "user_1").toJsonValue())
                .isEqualTo("USD");
    }

    @Test
    @DisplayName("amounts always reach the form as numbers")
    void normalisesAmounts() {
        SlotDefinition slot = slot("amount", "number", "amount");

        assertThat(resolvers.amountResolver().resolve(slot, "10,000", "user_1").toJsonValue())
                .isEqualTo(10000L);
        assertThat(resolvers.amountResolver().resolve(slot, 12.5, "user_1").toJsonValue())
                .isEqualTo(12.5);
        assertThat(resolvers.amountResolver().resolve(slot, "not a number", "user_1").toJsonValue())
                .isEqualTo("not a number");
    }

    @Test
    @DisplayName("phone numbers are stripped of formatting")
    void normalisesPhones() {
        SlotDefinition slot = slot("phone_number", "string", "phone");

        assertThat(resolvers.phoneResolver().resolve(slot, "+1 (555) 010-9999", "user_1").toJsonValue())
                .isEqualTo("+15550109999");
        assertThat(resolvers.phoneResolver().resolve(slot, "12345", "user_1").isResolved())
                .isFalse();
    }

    private static SlotDefinition slot(String name, String type, String resolver) {
        return new SlotDefinition(name, type, "", false, List.of(), resolver);
    }
}
