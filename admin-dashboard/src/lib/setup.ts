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
  hasAdminAudit: boolean;
  ready: boolean;
  error: string | null;
}

export async function setupState(): Promise<SetupState> {
  try {
    const [row] = await query<{ audit: boolean }>(`
      SELECT EXISTS (SELECT 1 FROM information_schema.tables
                     WHERE table_name = 'admin_audit') AS audit
    `);
    const audit = row?.audit ?? false;
    return {
      databaseReachable: true,
      hasAdminAudit: audit,
      ready: audit,
      error: null,
    };
  } catch (error) {
    return {
      databaseReachable: false,
      hasAdminAudit: false,
      ready: false,
      error: error instanceof Error ? error.message : "database unreachable",
    };
  }
}
