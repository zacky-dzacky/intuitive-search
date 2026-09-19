/**
 * The text that represents a feature in vector space.
 *
 * This is a port of `feature_document()` in
 * `embedding-service/precompute_embeddings.py`, and it has to stay a faithful
 * one: the dashboard's "Re-embed" writes vectors that must be interchangeable
 * with the ones the offline backfill writes. Same parts, same order, same
 * separators — otherwise the two tools produce slightly different vectors for
 * the same row and recall quietly shifts depending on who last touched it.
 */

export interface FeatureDocumentSource {
  display_name: string;
  keywords?: string[] | null;
  aliases?: string[] | null;
  description?: string | null;
}

export function featureDocument(row: FeatureDocumentSource): string {
  const parts = [
    row.display_name,
    (row.keywords ?? []).join(", "),
    (row.aliases ?? []).join(", "),
    row.description ?? "",
  ];
  return parts.filter((part) => part && part.length > 0).join(". ").trim();
}

/**
 * The same document, expressed in SQL.
 *
 * `concat_ws` skips NULLs, so `nullif(x, '')` reproduces Python's
 * "drop the empty parts" filter exactly. Used to detect staleness: a row whose
 * stored `embedding_source_hash` no longer matches `md5(<this>)` has had its
 * text edited since it was last embedded.
 */
export const FEATURE_DOCUMENT_SQL = `concat_ws('. ',
  nullif(display_name, ''),
  nullif(array_to_string(keywords, ', '), ''),
  nullif(array_to_string(aliases, ', '), ''),
  nullif(description, ''))`;

export const FEATURE_DOCUMENT_HASH_SQL = `md5(btrim(${FEATURE_DOCUMENT_SQL}))`;

/** A row is stale when it has no vector, or the text moved on without it. */
export const FEATURE_EMBEDDING_STALE_SQL = `(embedding IS NULL
  OR embedding_source_hash IS NULL
  OR embedding_source_hash IS DISTINCT FROM ${FEATURE_DOCUMENT_HASH_SQL})`;
