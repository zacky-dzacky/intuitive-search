import "server-only";

import { env } from "./env";
import { pingDatabase } from "./db";

/**
 * Liveness of the three things the dashboard depends on.
 *
 * Each is probed independently and none of them is fatal: the registry stays
 * editable while the backend is down, and the playground says so rather than
 * the whole page failing.
 */
export interface ServiceHealth {
  name: string;
  target: string;
  ok: boolean;
  detail: string;
}

async function probe(name: string, target: string, path: string): Promise<ServiceHealth> {
  try {
    const response = await fetch(`${target}${path}`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    return {
      name,
      target,
      ok: response.ok,
      detail: response.ok ? "reachable" : `HTTP ${response.status}`,
    };
  } catch (error) {
    return {
      name,
      target,
      ok: false,
      detail: error instanceof Error ? error.message : "unreachable",
    };
  }
}

export async function systemHealth(): Promise<ServiceHealth[]> {
  const [database, backend, embedding] = await Promise.all([
    pingDatabase().then((ok) => ({
      name: "Postgres",
      target: "features registry",
      ok,
      detail: ok ? "reachable" : "unreachable — check DATABASE_URL",
    })),
    probe("Search API", env.backendBaseUrl(), "/actuator/health"),
    probe("Embedding service", env.embeddingBaseUrl(), "/health"),
  ]);
  return [database, backend, embedding];
}
