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
 * Reads the feature registry from Postgres. Retrieval itself does not happen
 * here any more — the rows are loaded whole and indexed in process by
 * {@link com.bank.intuitivesearch.index.FeatureIndex}.
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
}
