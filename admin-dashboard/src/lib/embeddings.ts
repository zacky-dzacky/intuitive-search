import "server-only";

import { env } from "./env";

/**
 * The search index, as the backend reports it.
 *
 * There is nothing to manage here any more. The backend owns the index: it
 * rebuilds it from the `features` table on every registry refresh, embeds
 * only the rows whose text changed, and keeps serving the previous
 * generation if the embedding provider is down. This module just reads that
 * state back and offers one button for operators who do not want to wait
 * out the refresh interval — or who changed the embedding model and want
 * every vector redone.
 */

export interface IndexStatus {
  reachable: boolean;
  ready: boolean;
  features: number;
  withVector: number;
  dimensions: number | null;
  embeddingModel: string | null;
  embeddingEnabled: boolean;
  embeddingDegraded: boolean;
  builtAt: string | null;
  buildMs: number | null;
  embeddedInBuild: number | null;
  lastError: string | null;
  error: string | null;
}

export interface RebuildResult {
  rebuilt: boolean;
  features: number;
  embedded: number;
  withVector: number;
  tookMs: number;
  error: string | null;
}

const UNREACHABLE: IndexStatus = {
  reachable: false,
  ready: false,
  features: 0,
  withVector: 0,
  dimensions: null,
  embeddingModel: null,
  embeddingEnabled: false,
  embeddingDegraded: false,
  builtAt: null,
  buildMs: null,
  embeddedInBuild: null,
  lastError: null,
  error: null,
};

export async function indexStatus(): Promise<IndexStatus> {
  try {
    const response = await fetch(`${env.backendBaseUrl()}/api/admin/index`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    if (!response.ok) {
      return { ...UNREACHABLE, error: `HTTP ${response.status}` };
    }
    const body = (await response.json()) as {
      ready: boolean;
      features: number;
      with_vector: number;
      dimensions: number | null;
      embedding_model: string | null;
      embedding_enabled: boolean;
      embedding_degraded: boolean;
      built_at: string | null;
      build_ms: number | null;
      embedded_in_build: number | null;
      last_error: string | null;
    };
    return {
      reachable: true,
      ready: body.ready,
      features: body.features,
      withVector: body.with_vector,
      dimensions: body.dimensions,
      embeddingModel: body.embedding_model,
      embeddingEnabled: body.embedding_enabled,
      embeddingDegraded: body.embedding_degraded,
      builtAt: body.built_at,
      buildMs: body.build_ms,
      embeddedInBuild: body.embedded_in_build,
      lastError: body.last_error,
      error: null,
    };
  } catch (error) {
    return { ...UNREACHABLE, error: error instanceof Error ? error.message : "unreachable" };
  }
}

/**
 * Asks the backend to refresh the registry and rebuild the index now.
 *
 * `force` re-embeds every feature rather than only the changed ones — the
 * escape hatch after switching embedding model or dimensions.
 */
export async function rebuildIndex(force: boolean): Promise<RebuildResult> {
  const response = await fetch(
    `${env.backendBaseUrl()}/api/admin/index/rebuild?force=${force ? "true" : "false"}`,
    {
      method: "POST",
      cache: "no-store",
      // A forced rebuild is one embedding call for the whole catalogue.
      signal: AbortSignal.timeout(120_000),
    },
  );
  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`Search API returned ${response.status}. ${detail.slice(0, 200)}`);
  }
  const body = (await response.json()) as {
    rebuilt: boolean;
    features: number;
    embedded: number;
    with_vector: number;
    took_ms: number;
    error: string | null;
  };
  return {
    rebuilt: body.rebuilt,
    features: body.features,
    embedded: body.embedded,
    withVector: body.with_vector,
    tookMs: body.took_ms,
    error: body.error ?? null,
  };
}
