import "server-only";

import { query } from "./db";

/**
 * Aggregates over `search_audit`.
 *
 * The two questions this page exists to answer:
 *
 *   1. Is the LLM gate holding? `llm_invoked` should be false for navigation
 *      queries and true only where the query carried parameters. A rising rate
 *      is a cost regression, and it is invisible from anywhere else.
 *   2. What did search fail to find? Queries that resolved to `none` or
 *      `suggest` are the registry's to-do list — each one is a missing
 *      keyword, alias, or feature.
 */

export type RangeKey = "24h" | "7d" | "30d" | "all";

const RANGE_SQL: Record<RangeKey, string> = {
  "24h": "created_at >= now() - interval '24 hours'",
  "7d": "created_at >= now() - interval '7 days'",
  "30d": "created_at >= now() - interval '30 days'",
  all: "TRUE",
};

export const RANGE_LABELS: Record<RangeKey, string> = {
  "24h": "Last 24 hours",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  all: "All time",
};

export function parseRange(value: string | undefined): RangeKey {
  return value === "24h" || value === "7d" || value === "30d" || value === "all" ? value : "7d";
}

export interface AnalyticsSummary {
  total: number;
  llmInvoked: number;
  llmRate: number;
  prefillCount: number;
  navigateCount: number;
  unresolvedCount: number;
  p50: number | null;
  p95: number | null;
  avgConfidence: number | null;
}

export interface Bucket {
  bucket: string;
  total: number;
  llm: number;
}

export interface Countable {
  label: string;
  count: number;
  extra?: string | null;
}

export interface LatencySplit {
  llm: boolean;
  count: number;
  p50: number | null;
  p95: number | null;
}

export async function summary(range: RangeKey): Promise<AnalyticsSummary> {
  const [row] = await query<Record<string, string | null>>(`
    SELECT COUNT(*)::text                                                   AS total,
           COUNT(*) FILTER (WHERE llm_invoked)::text                        AS llm_invoked,
           COUNT(*) FILTER (WHERE action = 'prefill_form')::text            AS prefill_count,
           COUNT(*) FILTER (WHERE action = 'navigate')::text                AS navigate_count,
           COUNT(*) FILTER (WHERE action IN ('none', 'suggest'))::text      AS unresolved_count,
           percentile_disc(0.5) WITHIN GROUP (ORDER BY latency_ms)::text    AS p50,
           percentile_disc(0.95) WITHIN GROUP (ORDER BY latency_ms)::text   AS p95,
           AVG(confidence)::text                                            AS avg_confidence
    FROM search_audit
    WHERE ${RANGE_SQL[range]}
  `);

  const total = Number(row?.total ?? 0);
  const llmInvoked = Number(row?.llm_invoked ?? 0);

  return {
    total,
    llmInvoked,
    llmRate: total ? llmInvoked / total : 0,
    prefillCount: Number(row?.prefill_count ?? 0),
    navigateCount: Number(row?.navigate_count ?? 0),
    unresolvedCount: Number(row?.unresolved_count ?? 0),
    p50: row?.p50 != null ? Number(row.p50) : null,
    p95: row?.p95 != null ? Number(row.p95) : null,
    avgConfidence: row?.avg_confidence != null ? Number(row.avg_confidence) : null,
  };
}

/** Hourly buckets for a day, daily otherwise — enough points to read a trend. */
export async function volume(range: RangeKey): Promise<Bucket[]> {
  const grain = range === "24h" ? "hour" : "day";
  const rows = await query<{ bucket: Date; total: string; llm: string }>(`
    SELECT date_trunc('${grain}', created_at) AS bucket,
           COUNT(*)::text                     AS total,
           COUNT(*) FILTER (WHERE llm_invoked)::text AS llm
    FROM search_audit
    WHERE ${RANGE_SQL[range]}
    GROUP BY 1
    ORDER BY 1
  `);
  return rows.map((row) => ({
    bucket: row.bucket.toISOString(),
    total: Number(row.total),
    llm: Number(row.llm),
  }));
}

export async function actionBreakdown(range: RangeKey): Promise<Countable[]> {
  const rows = await query<{ action: string; count: string }>(`
    SELECT action, COUNT(*)::text AS count
    FROM search_audit
    WHERE ${RANGE_SQL[range]}
    GROUP BY 1
    ORDER BY 2 DESC
  `);
  return rows.map((row) => ({ label: row.action, count: Number(row.count) }));
}

export async function topQueries(range: RangeKey, limit = 12): Promise<Countable[]> {
  const rows = await query<{ query: string; count: string; matched: string | null }>(
    `SELECT lower(query) AS query,
            COUNT(*)::text AS count,
            mode() WITHIN GROUP (ORDER BY matched_feature) AS matched
     FROM search_audit
     WHERE ${RANGE_SQL[range]}
     GROUP BY 1
     ORDER BY 2 DESC, 1
     LIMIT $1`,
    [limit],
  );
  return rows.map((row) => ({
    label: row.query,
    count: Number(row.count),
    extra: row.matched,
  }));
}

/** The registry's to-do list: what people asked for and did not get. */
export async function unresolvedQueries(range: RangeKey, limit = 12): Promise<Countable[]> {
  const rows = await query<{ query: string; count: string; confidence: string | null }>(
    `SELECT lower(query) AS query,
            COUNT(*)::text AS count,
            MAX(confidence)::text AS confidence
     FROM search_audit
     WHERE ${RANGE_SQL[range]}
       AND (action IN ('none', 'suggest') OR matched_feature IS NULL)
     GROUP BY 1
     ORDER BY 2 DESC, 1
     LIMIT $1`,
    [limit],
  );
  return rows.map((row) => ({
    label: row.query,
    count: Number(row.count),
    extra: row.confidence != null ? `best ${Number(row.confidence).toFixed(2)}` : null,
  }));
}

export async function topFeatures(range: RangeKey, limit = 12): Promise<Countable[]> {
  const rows = await query<{ matched_feature: string; count: string; avg_confidence: string | null }>(
    `SELECT matched_feature, COUNT(*)::text AS count, AVG(confidence)::text AS avg_confidence
     FROM search_audit
     WHERE ${RANGE_SQL[range]} AND matched_feature IS NOT NULL
     GROUP BY 1
     ORDER BY 2 DESC
     LIMIT $1`,
    [limit],
  );
  return rows.map((row) => ({
    label: row.matched_feature,
    count: Number(row.count),
    extra: row.avg_confidence != null ? `avg ${Number(row.avg_confidence).toFixed(2)}` : null,
  }));
}

/**
 * Latency with and without the LLM stage, side by side. This is the number the
 * <100ms / <400ms budgets are written against.
 */
export async function latencyByPath(range: RangeKey): Promise<LatencySplit[]> {
  const rows = await query<{ llm_invoked: boolean; count: string; p50: string | null; p95: string | null }>(`
    SELECT llm_invoked,
           COUNT(*)::text AS count,
           percentile_disc(0.5) WITHIN GROUP (ORDER BY latency_ms)::text  AS p50,
           percentile_disc(0.95) WITHIN GROUP (ORDER BY latency_ms)::text AS p95
    FROM search_audit
    WHERE ${RANGE_SQL[range]}
    GROUP BY 1
    ORDER BY 1
  `);
  return rows.map((row) => ({
    llm: row.llm_invoked,
    count: Number(row.count),
    p50: row.p50 != null ? Number(row.p50) : null,
    p95: row.p95 != null ? Number(row.p95) : null,
  }));
}

/** True when the audit table has any rows at all — drives the empty state. */
export async function hasAuditData(): Promise<boolean> {
  const rows = await query<{ exists: boolean }>(
    "SELECT EXISTS (SELECT 1 FROM search_audit LIMIT 1) AS exists",
  );
  return rows[0]?.exists ?? false;
}
