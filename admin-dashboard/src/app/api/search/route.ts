import { NextResponse } from "next/server";

import { guard, jsonError, readJsonObject } from "@/lib/api";
// Aliased: `query` is the user's search text everywhere in this file.
import { query as sql } from "@/lib/db";
import { env } from "@/lib/env";

/**
 * Search Playground proxy.
 *
 * The browser never talks to the search API directly: the backend's address
 * stays server-side (it is typically not exposed outside the compose network),
 * the dashboard's session is the only way in, and CORS stops being a
 * consideration. The response is passed through untouched — the diagnostics
 * block is the entire point of the page, and editing it here would mean the
 * playground was showing something other than what the API returns.
 *
 * `mode: "suggest"` hits GET /api/search/suggest, which hard-codes
 * allow_extraction=false server-side — the same thing typeahead does.
 *
 * It also writes the `search_audit` row, when asked to. The backend does not
 * write one today (the table is seeded in the schema but nothing populates
 * it), so playground traffic is the only source the Analytics page has until
 * it does — see README §"Where the analytics data comes from" for the ~20
 * lines that make the backend write its own. The checkbox exists so an
 * operator can probe the API without colouring the numbers.
 */

export const dynamic = "force-dynamic";

interface SearchApiResponse {
  matched_feature: string | null;
  confidence: number;
  action: string;
  diagnostics?: { total_ms?: number; llm_invoked?: boolean };
}

/**
 * Writes one `search_audit` row. Fails soft — a search that worked must not
 * be reported as failed because the log write did not.
 */
async function recordSearch(
  text: string,
  userId: string,
  result: SearchApiResponse,
): Promise<void> {
  try {
    await sql(
      `INSERT INTO search_audit
         (user_id, query, matched_feature, confidence, action, llm_invoked, latency_ms)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [
        userId || null,
        text,
        result.matched_feature ?? null,
        result.confidence ?? null,
        result.action ?? "none",
        result.diagnostics?.llm_invoked ?? false,
        result.diagnostics?.total_ms ?? null,
      ],
    );
  } catch (error) {
    console.error("[playground] could not write search_audit row", error);
  }
}

export async function POST(request: Request) {
  const denied = await guard();
  if (denied) return denied;

  const parsed = await readJsonObject(request);
  if (!parsed.ok) return parsed.response;

  const query = typeof parsed.body.query === "string" ? parsed.body.query.trim() : "";
  if (!query) return jsonError(400, "Type a query first.");
  if (query.length > 512) return jsonError(400, "The API accepts at most 512 characters.");

  const mode = parsed.body.mode === "suggest" ? "suggest" : "search";
  const userId = typeof parsed.body.user_id === "string" ? parsed.body.user_id.trim() : "";
  const allowExtraction = parsed.body.allow_extraction !== false;

  const base = env.backendBaseUrl();
  const started = Date.now();

  try {
    const response =
      mode === "suggest"
        ? await fetch(
            `${base}/api/search/suggest?q=${encodeURIComponent(query)}${
              userId ? `&userId=${encodeURIComponent(userId)}` : ""
            }`,
            { cache: "no-store", signal: AbortSignal.timeout(15_000) },
          )
        : await fetch(`${base}/api/search`, {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
              query,
              user_id: userId || undefined,
              allow_extraction: allowExtraction,
            }),
            cache: "no-store",
            signal: AbortSignal.timeout(15_000),
          });

    const text = await response.text();
    if (!response.ok) {
      return jsonError(
        response.status,
        `Search API returned ${response.status}. ${text.slice(0, 300)}`,
      );
    }

    const result = JSON.parse(text) as SearchApiResponse;

    if (parsed.body.record !== false) {
      await recordSearch(query, userId, result);
    }

    return NextResponse.json({
      // Round-trip time as the dashboard sees it, next to the API's own
      // per-stage timings — the gap between them is network and proxy.
      round_trip_ms: Date.now() - started,
      mode,
      result,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "unreachable";
    return jsonError(502, `Could not reach the search API at ${base}: ${message}`);
  }
}
