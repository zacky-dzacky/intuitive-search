import "server-only";

import { query } from "./db";

/**
 * Has db/04_admin.sql been applied?
 *
 * The dashboard is additive to an existing database, so the honest first-run
 * experience is to say which statement is missing rather than to fail on the
 * first page that touches a new column.
 */
export interface SetupState {
  databaseReachable: boolean;
  hasEmbeddingProvenance: boolean;
  hasAdminAudit: boolean;
  ready: boolean;
  error: string | null;
}

export async function setupState(): Promise<SetupState> {
  try {
    const [row] = await query<{ provenance: boolean; audit: boolean }>(`
      SELECT
        EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'features' AND column_name = 'embedding_source_hash') AS provenance,
        EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_name = 'admin_audit') AS audit
    `);
    const provenance = row?.provenance ?? false;
    const audit = row?.audit ?? false;
    return {
      databaseReachable: true,
      hasEmbeddingProvenance: provenance,
      hasAdminAudit: audit,
      ready: provenance && audit,
      error: null,
    };
  } catch (error) {
    return {
      databaseReachable: false,
      hasEmbeddingProvenance: false,
      hasAdminAudit: false,
      ready: false,
      error: error instanceof Error ? error.message : "database unreachable",
    };
  }
}
