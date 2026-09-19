import { NextResponse } from "next/server";

import { guard, jsonError, readJsonObject } from "@/lib/api";
import { recordAdminAction } from "@/lib/adminAudit";
import { indexStatus, rebuildIndex } from "@/lib/embeddings";

/**
 * `GET`  — the backend's view of the search index.
 * `POST` — rebuild it now: `{ "force": true }` re-embeds every feature,
 *          anything else re-embeds only what changed.
 *
 * Both are thin proxies to the search API, which owns the index. The route
 * keeps its old name so the feature form's "update index after saving" call
 * did not have to move.
 */

export const dynamic = "force-dynamic";
export const maxDuration = 300;

export async function GET() {
  const denied = await guard();
  if (denied) return denied;
  return NextResponse.json(await indexStatus());
}

export async function POST(request: Request) {
  const denied = await guard();
  if (denied) return denied;

  const parsed = await readJsonObject(request);
  if (!parsed.ok) return parsed.response;

  const { force } = parsed.body as { force?: unknown };

  try {
    const result = await rebuildIndex(force === true);
    if (result.embedded > 0) {
      await recordAdminAction({
        action: "reembed",
        resource: "features",
        recordId: null,
        changes: { count: result.embedded, took_ms: result.tookMs, forced: force === true },
      });
    }
    return NextResponse.json(result);
  } catch (error) {
    // The common cause is the search API being down, and the operator needs
    // to read that rather than a stack trace.
    const message = error instanceof Error ? error.message : "Rebuild failed.";
    return jsonError(502, message);
  }
}
