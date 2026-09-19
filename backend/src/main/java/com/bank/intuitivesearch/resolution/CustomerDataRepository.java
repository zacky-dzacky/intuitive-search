package com.bank.intuitivesearch.resolution;

import java.sql.ResultSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the existing payee/account tables for Stage 3.
 *
 * <p>Fuzzy name matching uses pg_trgm, the same extension already carrying
 * typo tolerance in Stage 1 — "mom" matches the nickname "Mom", "jane"
 * matches "Jane Doe", "acme corp" matches "Acme Corporation".
 *
 * <p>Every query is scoped by {@code user_id}. A search feature must never be
 * able to enumerate another customer's payees.
 */
@Repository
public class CustomerDataRepository {

    private final JdbcClient jdbc;

    public CustomerDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<NamedEntity> findPayees(String userId, String needle, double minSimilarity, int limit) {
        return jdbc.sql("""
                    SELECT payee_id                          AS id,
                           full_name                         AS name,
                           coalesce(nickname, '')            AS nickname,
                           bank_name                         AS detail_bank,
                           account_number                    AS detail_account,
                           GREATEST(
                               similarity(lower(coalesce(nickname, '')), :needle),
                               similarity(lower(full_name), :needle),
                               word_similarity(:needle, lower(full_name)),
                               CASE WHEN account_number = :needle THEN 1.0 ELSE 0 END
                           )                                 AS score
                    FROM payees
                    WHERE user_id = :userId
                      AND GREATEST(
                              similarity(lower(coalesce(nickname, '')), :needle),
                              similarity(lower(full_name), :needle),
                              word_similarity(:needle, lower(full_name)),
                              CASE WHEN account_number = :needle THEN 1.0 ELSE 0 END
                          ) >= :minSim
                    ORDER BY score DESC, last_used_at DESC NULLS LAST
                    LIMIT :lim
                """)
                .param("userId", userId)
                .param("needle", needle)
                .param("minSim", minSimilarity)
                .param("lim", limit)
                .query((ResultSet rs, int rowNum) -> new NamedEntity(
                        rs.getString("id"),
                        displayName(rs.getString("name"), rs.getString("nickname")),
                        rs.getString("detail_bank") + " ****"
                                + lastFour(rs.getString("detail_account")),
                        rs.getDouble("score")))
                .list();
    }

    public List<NamedEntity> findAccounts(String userId, String needle, double minSimilarity, int limit) {
        return jdbc.sql("""
                    SELECT account_id      AS id,
                           label           AS name,
                           account_type    AS detail_type,
                           currency        AS detail_currency,
                           account_number  AS detail_account,
                           GREATEST(
                               similarity(lower(label), :needle),
                               word_similarity(:needle, lower(label)),
                               CASE WHEN lower(currency) = :needle THEN 0.8 ELSE 0 END,
                               CASE WHEN account_number = :needle THEN 1.0 ELSE 0 END
                           )                AS score
                    FROM accounts
                    WHERE user_id = :userId
                      AND GREATEST(
                              similarity(lower(label), :needle),
                              word_similarity(:needle, lower(label)),
                              CASE WHEN lower(currency) = :needle THEN 0.8 ELSE 0 END,
                              CASE WHEN account_number = :needle THEN 1.0 ELSE 0 END
                          ) >= :minSim
                    ORDER BY score DESC
                    LIMIT :lim
                """)
                .param("userId", userId)
                .param("needle", needle)
                .param("minSim", minSimilarity)
                .param("lim", limit)
                .query((ResultSet rs, int rowNum) -> new NamedEntity(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("detail_currency") + " " + rs.getString("detail_type")
                                + " ****" + lastFour(rs.getString("detail_account")),
                        rs.getDouble("score")))
                .list();
    }

    private static String displayName(String fullName, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return fullName;
        }
        return nickname + " (" + fullName + ")";
    }

    private static String lastFour(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return accountNumber == null ? "" : accountNumber;
        }
        return accountNumber.substring(accountNumber.length() - 4);
    }

    /** A resolved customer-data row: payee or own account. */
    public record NamedEntity(String id, String name, String detail, double score) {}
}
