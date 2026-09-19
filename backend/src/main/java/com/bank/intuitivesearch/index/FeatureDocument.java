package com.bank.intuitivesearch.index;

import com.bank.intuitivesearch.model.Feature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The text that represents a feature in each index field.
 *
 * <p>{@link #embeddingText} is what goes to the embedding model: display
 * name and keywords carry most of the signal, the description adds semantic
 * reach ("I want to see where my money went" → spending insights). Its hash
 * is the vector's provenance — a feature whose text has not changed keeps
 * its vector across rebuilds, so editing one row re-embeds one row.
 */
public final class FeatureDocument {

    private FeatureDocument() {}

    /** {@code display_name. keyword, keyword. alias, alias. description}, empty parts dropped. */
    public static String embeddingText(Feature feature) {
        return Stream.of(
                        feature.displayName(),
                        String.join(", ", feature.keywords()),
                        String.join(", ", feature.aliases()),
                        feature.description())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(". "))
                .trim();
    }

    /** Full-text field: everything a user might phrase, stemmed at index time. */
    static String fullText(Feature feature) {
        return embeddingText(feature);
    }

    /** Trigram field: only the naming parts, as pg_trgm's {@code match_text} was. */
    static String matchText(Feature feature) {
        return Stream.of(
                        List.of(feature.displayName() == null ? "" : feature.displayName()),
                        feature.keywords(),
                        feature.aliases())
                .flatMap(List::stream)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
    }

    public static String hash(String text) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
