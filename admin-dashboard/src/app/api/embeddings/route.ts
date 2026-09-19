import { NextResponse } from "next/server";

import { guard, jsonError, readJsonObject } from "@/lib/api";
import { recordAdminAction } from "@/lib/adminAudit";
import { embeddingStatus, reembedFeatures } from "@/lib/embeddings";

/**
 * `GET`  — registry vector coverage plus the embedding service's own health.
 * `POST` — re-embed: `{ "scope": "stale" | "all" }` or `{ "featureIds": [...] }`.
 *
 * This does what `embedding-service/precompute_embeddings.py` does, over HTTP
 * against the running service rather than by loading a second copy of the
 * model. Same documents, same `is_query: false`, same vectors.
 */

export const dynamic = "force-dynamic";
// A full re-embed of the registry is a couple of minutes of forward passes.
export const maxDuration = 300;

export async function GET() {
  const denied = await guard();
  if (denied) return denied;
  return NextResponse.json(await embeddingStatus());
}

export async function POST(request: Request) {
  const denied = await guard();
  if (denied) return denied;

  const parsed = await readJsonObject(request);
  if (!parsed.ok) return parsed.response;

  const { scope, featureIds } = parsed.body as { scope?: string; featureIds?: unknown };

  let target: "stale" | "all" | { featureIds: string[] };
  if (Array.isArray(featureIds)) {
    target = { featureIds: featureIds.map(String) };
  } else if (scope === "all") {
    target = "all";
  } else if (scope === "stale" || scope === undefined) {
    target = "stale";
  } else {
    return jsonError(400, `Unknown scope "${scope}". Use "stale", "all", or featureIds.`);
  }

  try {
    const result = await reembedFeatures(target);
    if (result.embedded > 0) {
      await recordAdminAction({
        action: "reembed",
        resource: "features",
        recordId: result.embedded === 1 ? result.featureIds[0] : null,
        changes: { count: result.embedded, took_ms: result.tookMs, feature_ids: result.featureIds },
      });
    }
    return NextResponse.json(result);
  } catch (error) {
    // The common cause is the embedding service being down, and the operator
    // needs to read that rather than a stack trace.
    const message = error instanceof Error ? error.message : "Re-embedding failed.";
    return jsonError(502, message);
  }
}
