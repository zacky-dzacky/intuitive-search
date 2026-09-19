import "server-only";

import { createHash } from "node:crypto";

import { EMBEDDING_DIMENSIONS, env } from "./env";
import { query } from "./db";
import { FEATURE_EMBEDDING_STALE_SQL, featureDocument } from "./featureDocument";

/** Must match `FEATURE_DOCUMENT_HASH_SQL` — md5 over the trimmed document. */
function documentHash(document: string): string {
  return createHash("md5").update(document, "utf8").digest("hex");
}

/**
 * Embedding maintenance, moved off the command line.
 *
 * Editing a feature's text does not invalidate its vector: the old one keeps
 * working, slightly out of date, which beats dropping the vector channel for
 * that row until someone remembers to run a script. Staleness is instead made
 * *visible* — `embedding_source_hash` records the exact document that was
 * embedded, so the dashboard can say "these six rows have moved on" and offer
 * one button.
 *
 * Documents are built by the shared `featureDocument()` port and sent with
 * `is_query: false`, because bge is asymmetric — a document embedded with the
 * query prefix silently retrieves worse, and nothing would fail loudly.
 */

/** Well below the service's MAX_BATCH of 256, and one CPU-bound call at a time. */
const BATCH_SIZE = 64;

export interface EmbeddingStatus {
  total: number;
  embedded: number;
  missing: number;
  stale: number;
  lastEmbeddedAt: string | null;
  dimensions: number;
  serviceReachable: boolean;
  serviceModel: string | null;
  serviceDimensions: number | null;
  serviceError: string | null;
}

export interface StaleFeature {
  feature_id: string;
  display_name: string;
  category: string;
  enabled: boolean;
  has_embedding: boolean;
  embedded_at: string | null;
  updated_at: string;
}

interface EmbeddingRow {
  feature_id: string;
  display_name: string;
  description: string | null;
  keywords: string[] | null;
  aliases: string[] | null;
}

export async function embeddingStatus(): Promise<EmbeddingStatus> {
  const [counts] = await query<{
    total: string;
    embedded: string;
    stale: string;
    last_embedded_at: Date | null;
  }>(`
    SELECT COUNT(*)::text AS total,
           COUNT(embedding)::text AS embedded,
           COUNT(*) FILTER (WHERE ${FEATURE_EMBEDDING_STALE_SQL})::text AS stale,
           MAX(embedded_at) AS last_embedded_at
    FROM features
  `);

  const service = await probeEmbeddingService();
  const total = Number(counts?.total ?? 0);
  const embedded = Number(counts?.embedded ?? 0);

  return {
    total,
    embedded,
    missing: total - embedded,
    stale: Number(counts?.stale ?? 0),
    lastEmbeddedAt: counts?.last_embedded_at ? counts.last_embedded_at.toISOString() : null,
    dimensions: EMBEDDING_DIMENSIONS,
    serviceReachable: service.reachable,
    serviceModel: service.model,
    serviceDimensions: service.dimensions,
    serviceError: service.error,
  };
}

/** Just the number, for the sidebar badge — no service probe, one COUNT. */
export async function staleCount(): Promise<number> {
  try {
    const [row] = await query<{ count: string }>(
      `SELECT COUNT(*)::text AS count FROM features WHERE ${FEATURE_EMBEDDING_STALE_SQL}`,
    );
    return Number(row?.count ?? 0);
  } catch {
    // The sidebar must render even when the migration has not been run yet.
    return 0;
  }
}

export async function staleFeatures(limit = 200): Promise<StaleFeature[]> {
  return query<StaleFeature>(
    `SELECT feature_id, display_name, category, enabled,
            (embedding IS NOT NULL) AS has_embedding,
            embedded_at, updated_at
     FROM features
     WHERE ${FEATURE_EMBEDDING_STALE_SQL}
     ORDER BY updated_at DESC
     LIMIT $1`,
    [limit],
  );
}

async function probeEmbeddingService(): Promise<{
  reachable: boolean;
  model: string | null;
  dimensions: number | null;
  error: string | null;
}> {
  try {
    const response = await fetch(`${env.embeddingBaseUrl()}/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    if (!response.ok) {
      return { reachable: false, model: null, dimensions: null, error: `HTTP ${response.status}` };
    }
    const body = (await response.json()) as { model?: string; dimensions?: number };
    return {
      reachable: true,
      model: body.model ?? null,
      dimensions: body.dimensions ?? null,
      error: null,
    };
  } catch (error) {
    return {
      reachable: false,
      model: null,
      dimensions: null,
      error: error instanceof Error ? error.message : "unreachable",
    };
  }
}

async function embedBatch(texts: string[]): Promise<number[][]> {
  const response = await fetch(`${env.embeddingBaseUrl()}/embed/batch`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    // Documents, not queries: no retrieval prefix. See check_embeddings.py.
    body: JSON.stringify({ texts, is_query: false }),
    cache: "no-store",
    // The forward pass is ~25ms per item on one core; a batch of 64 with a
    // cold-ish worker can legitimately take a while.
    signal: AbortSignal.timeout(120_000),
  });

  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`Embedding service returned ${response.status}. ${detail.slice(0, 200)}`);
  }

  const body = (await response.json()) as { embeddings: number[][]; dimensions: number };
  if (body.dimensions !== EMBEDDING_DIMENSIONS) {
    throw new Error(
      `Embedding service produced ${body.dimensions} dimensions but features.embedding is VECTOR(${EMBEDDING_DIMENSIONS}). Refusing to write.`,
    );
  }
  return body.embeddings;
}

/** pgvector's text input format, at the precision precompute_embeddings.py uses. */
function vectorLiteral(vector: number[]): string {
  return `[${vector.map((value) => value.toFixed(6)).join(",")}]`;
}

export interface ReembedResult {
  requested: number;
  embedded: number;
  tookMs: number;
  featureIds: string[];
}

/**
 * Re-embeds features and records what was embedded.
 *
 * `scope: "stale"` is the everyday case — it covers rows that have never been
 * embedded and rows whose text has changed since. `"all"` is the escape hatch
 * for a model change.
 */
export async function reembedFeatures(
  scope: "stale" | "all" | { featureIds: string[] },
): Promise<ReembedResult> {
  const started = Date.now();

  let rows: EmbeddingRow[];
  if (typeof scope === "object") {
    if (!scope.featureIds.length) {
      return { requested: 0, embedded: 0, tookMs: 0, featureIds: [] };
    }
    rows = await query<EmbeddingRow>(
      `SELECT feature_id, display_name, description, keywords, aliases
       FROM features WHERE feature_id = ANY($1::text[]) ORDER BY feature_id`,
      [scope.featureIds],
    );
  } else {
    rows = await query<EmbeddingRow>(
      `SELECT feature_id, display_name, description, keywords, aliases
       FROM features
       ${scope === "stale" ? `WHERE ${FEATURE_EMBEDDING_STALE_SQL}` : ""}
       ORDER BY feature_id`,
    );
  }

  const embeddedIds: string[] = [];

  for (let offset = 0; offset < rows.length; offset += BATCH_SIZE) {
    const batch = rows.slice(offset, offset + BATCH_SIZE);
    const documents = batch.map((row) => featureDocument(row));
    const vectors = await embedBatch(documents);

    if (vectors.length !== batch.length) {
      throw new Error(
        `Embedding service returned ${vectors.length} vectors for ${batch.length} features.`,
      );
    }

    for (let index = 0; index < batch.length; index += 1) {
      const row = batch[index];
      // Hash the document we actually embedded, not the row as it stands now.
      // If someone edits a feature mid-backfill, the stored hash then differs
      // from the live text and the row stays correctly marked stale — writing
      // a freshly computed SQL hash here would mark it current while holding a
      // vector of the old text.
      await query(
        `UPDATE features
         SET embedding = $1::vector,
             embedding_source_hash = $2,
             embedded_at = now()
         WHERE feature_id = $3`,
        [vectorLiteral(vectors[index]), documentHash(documents[index]), row.feature_id],
      );
      embeddedIds.push(row.feature_id);
    }
  }

  return {
    requested: rows.length,
    embedded: embeddedIds.length,
    tookMs: Date.now() - started,
    featureIds: embeddedIds,
  };
}
