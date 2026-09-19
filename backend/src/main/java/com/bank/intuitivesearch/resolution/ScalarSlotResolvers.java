package com.bank.intuitivesearch.resolution;

import com.bank.intuitivesearch.model.SlotDefinition;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resolvers that normalise a value rather than look one up in customer data.
 *
 * <p>These matter because the LLM is prompted for a normalised form but is not
 * trusted to produce one. Normalising deterministically here means a wrong
 * model output becomes a blank field, not a bad pre-fill.
 */
@Configuration
public class ScalarSlotResolvers {

    private static final Pattern MONTH_ONLY = Pattern.compile(
            "^(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\s*(\\d{4})?$",
            Pattern.CASE_INSENSITIVE);

    /** Pass-through: the value goes on the form exactly as extracted. */
    @Bean
    SlotResolver noneResolver() {
        return new SimpleResolver("none", (slot, value) -> ResolvedSlot.scalar(slot.name(), value));
    }

    /** Uppercases and validates an ISO-4217 code; drops anything else. */
    @Bean
    SlotResolver currencyResolver() {
        return new SimpleResolver("currency", (slot, value) -> {
            String code = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z]{3}")) {
                return ResolvedSlot.unresolved(slot.name(), value);
            }
            return ResolvedSlot.scalar(slot.name(), code);
        });
    }

    /** Guarantees the form receives a number, never a string like "10,000". */
    @Bean
    SlotResolver amountResolver() {
        return new SimpleResolver("amount", (slot, value) -> {
            if (value instanceof Number number) {
                return ResolvedSlot.scalar(slot.name(), number);
            }
            String text = String.valueOf(value).replaceAll("[^0-9.\\-]", "");
            try {
                double parsed = Double.parseDouble(text);
                Object normalised = parsed == Math.rint(parsed)
                        ? (Object) (long) parsed
                        : parsed;
                return ResolvedSlot.scalar(slot.name(), normalised);
            } catch (NumberFormatException e) {
                return ResolvedSlot.unresolved(slot.name(), value);
            }
        });
    }

    /** Normalises absolute and relative dates to ISO-8601. */
    @Bean
    SlotResolver dateResolver() {
        return new SimpleResolver("date", (slot, value) -> {
            LocalDate date = parseDate(String.valueOf(value).trim());
            return date == null
                    ? ResolvedSlot.unresolved(slot.name(), value)
                    : ResolvedSlot.scalar(slot.name(), date.toString());
        });
    }

    /** Normalises a statement period to {@code YYYY-MM}. */
    @Bean
    SlotResolver periodResolver() {
        return new SimpleResolver("period", (slot, value) -> {
            String text = String.valueOf(value).trim();
            String period = parsePeriod(text);
            return period == null
                    ? ResolvedSlot.unresolved(slot.name(), value)
                    : ResolvedSlot.scalar(slot.name(), period);
        });
    }

    /** Strips formatting from a phone number, keeping any leading "+". */
    @Bean
    SlotResolver phoneResolver() {
        return new SimpleResolver("phone", (slot, value) -> {
            String raw = String.valueOf(value).trim();
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.length() < 7) {
                return ResolvedSlot.unresolved(slot.name(), value);
            }
            String normalised = raw.startsWith("+") ? "+" + digits : digits;
            return ResolvedSlot.scalar(slot.name(), normalised);
        });
    }

    static LocalDate parseDate(String text) {
        LocalDate today = LocalDate.now();
        String lower = text.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "today", "now" -> {
                return today;
            }
            case "tomorrow" -> {
                return today.plusDays(1);
            }
            case "yesterday" -> {
                return today.minusDays(1);
            }
            default -> { /* fall through to the parsers below */ }
        }

        Matcher nextWeekday = Pattern
                .compile("^next\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$",
                        Pattern.CASE_INSENSITIVE)
                .matcher(lower);
        if (nextWeekday.matches()) {
            DayOfWeek day = DayOfWeek.valueOf(nextWeekday.group(1).toUpperCase(Locale.ROOT));
            return today.with(TemporalAdjusters.next(day));
        }

        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String parsePeriod(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        LocalDate today = LocalDate.now();

        if (lower.equals("last month")) {
            return format(today.minusMonths(1));
        }
        if (lower.equals("this month")) {
            return format(today);
        }
        if (lower.matches("^\\d{4}-\\d{2}$")) {
            return lower;
        }

        Matcher month = MONTH_ONLY.matcher(lower);
        if (month.matches()) {
            int monthNumber = monthNumber(month.group(1));
            if (monthNumber < 1) {
                return null;
            }
            int year = month.group(2) != null
                    ? Integer.parseInt(month.group(2))
                    : (monthNumber > today.getMonthValue() ? today.getYear() - 1 : today.getYear());
            return "%04d-%02d".formatted(year, monthNumber);
        }
        return null;
    }

    private static String format(LocalDate date) {
        return "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
    }

    private static int monthNumber(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep", "sept" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> -1;
        };
    }

    /** Adapter for resolvers that need neither the database nor the user id. */
    private record SimpleResolver(String id, Normaliser normaliser) implements SlotResolver {
        @Override
        public ResolvedSlot resolve(SlotDefinition slot, Object rawValue, String userId) {
            if (rawValue == null || String.valueOf(rawValue).isBlank()) {
                return ResolvedSlot.unresolved(slot.name(), rawValue);
            }
            return normaliser.apply(slot, rawValue);
        }
    }

    @FunctionalInterface
    private interface Normaliser {
        ResolvedSlot apply(SlotDefinition slot, Object rawValue);
    }
}
