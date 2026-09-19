package com.bank.intuitivesearch.registry;

import com.bank.intuitivesearch.model.Feature;
import com.bank.intuitivesearch.model.SlotDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the feature registry. All three retrieval channels live here as SQL;
 * merging and reranking happen in the service layer.
 */
@Repository
public class FeatureRepository {

    private static final Logger log = LoggerFactory.getLogger(FeatureRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public FeatureRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Full registry load, used to build the in-memory cache. */
    public List<Feature> findAllEnabled() {
        return jdbc.sql("""
                    SELECT feature_id, display_name, description, category, route,
                           keywords, aliases, has_params, slots::text AS slots_json
                    FROM features
                    WHERE enabled
                    ORDER BY feature_id
                """)
                .query(this::mapFeature)
                .list();
    }

    /**
     * Lexical channel: PostgreSQL full-text search with prefix matching, plus
     * pg_trgm for typo and abbreviation tolerance. Both run in one pass so the
     * planner can share the scan.
     *
     * @param tsQuery a pre-sanitised {@code to_tsquery} expression, e.g.
     *                {@code transfer:* | mom:*}
     * @param rawQuery the normalised query, for trigram similarity
     */
    public List<ChannelHit> lexicalSearch(String tsQuery, String rawQuery, int limit) {
        return jdbc.sql("""
                    SELECT feature_id,
                           CASE WHEN :tsq <> ''
                                THEN ts_rank_cd(search_document, to_tsquery('english', :tsq), 32)
                                ELSE 0 END                                  AS keyword_score,
                           GREATEST(word_similarity(:raw, match_text),
                                    similarity(:raw, match_text))           AS trigram_score
                    FROM features
                    WHERE enabled
                      AND (
                            (:tsq <> '' AND search_document @@ to_tsquery('english', :tsq))
                         OR :raw %> match_text
                         OR match_text % :raw
                      )
                    ORDER BY GREATEST(
                            CASE WHEN :tsq <> ''
                                 THEN ts_rank_cd(search_document, to_tsquery('english', :tsq), 32)
                                 ELSE 0 END,
                            word_similarity(:raw, match_text)) DESC
                    LIMIT :lim
                """)
                .param("tsq", tsQuery)
                .param("raw", rawQuery)
                .param("lim", limit)
                .query((ResultSet rs, int rowNum) -> new ChannelHit(
                        rs.getString("feature_id"),
                        rs.getDouble("keyword_score"),
                        rs.getDouble("trigram_score"),
                        0.0))
                .list();
    }

    /**
     * Vector channel: cosine similarity in pgvector. The embedding is bound as
     * a text literal and cast, which avoids pulling in a pgvector JDBC type
     * just for one parameter.
     */
    public List<ChannelHit> vectorSearch(float[] queryEmbedding, int limit) {
        String literal = toVectorLiteral(queryEmbedding);
        return jdbc.sql("""
                    SELECT feature_id,
                           1 - (embedding <=> CAST(:vec AS vector)) AS vector_score
                    FROM features
                    WHERE enabled AND embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(:vec AS vector)
                    LIMIT :lim
                """)
                .param("vec", literal)
                .param("lim", limit)
                .query((ResultSet rs, int rowNum) -> new ChannelHit(
                        rs.getString("feature_id"),
                        0.0,
                        0.0,
                        rs.getDouble("vector_score")))
                .list();
    }

    public boolean anyEmbeddingsPresent() {
        Integer count = jdbc.sql("SELECT count(*) FROM features WHERE embedding IS NOT NULL")
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private Feature mapFeature(ResultSet rs, int rowNum) throws SQLException {
        return new Feature(
                rs.getString("feature_id"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getString("route"),
                readTextArray(rs.getArray("keywords")),
                readTextArray(rs.getArray("aliases")),
                rs.getBoolean("has_params"),
                parseSlots(rs.getString("feature_id"), rs.getString("slots_json")));
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        return List.of();
    }

    private List<SlotDefinition> parseSlots(String featureId, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw =
                    objectMapper.readValue(json, new TypeReference<>() {});
            List<SlotDefinition> slots = new ArrayList<>(raw.size());
            for (Map<String, Object> entry : raw) {
                slots.add(new SlotDefinition(
                        asString(entry.get("name")),
                        asString(entry.get("type")),
                        asString(entry.get("description")),
                        Boolean.TRUE.equals(entry.get("required")),
                        asStringList(entry.get("enum")),
                        asString(entry.get("resolver"))));
            }
            return List.copyOf(slots);
        } catch (Exception e) {
            // A malformed slot schema must not take the whole registry down;
            // the feature simply degrades to navigation-only.
            log.error("Feature '{}' has an unparseable slots schema; treating it as "
                    + "navigation-only. Fix the row in the features table.", featureId, e);
            return List.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** One feature's score from a single retrieval channel. */
    public record ChannelHit(String featureId, double keywordScore,
                             double trigramScore, double vectorScore) {}
}
