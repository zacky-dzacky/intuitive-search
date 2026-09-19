import "server-only";

import { query } from "./db";

/**
 * Records what the dashboard changed.
 *
 * Registry edits take effect within 60 seconds without a deploy, which is the
 * point of the design — and also means there is no deploy log to consult when
 * a search result changes. This is that log.
 *
 * It fails soft on purpose: an unavailable audit table must not turn a valid
 * registry edit into an error. The migration in db/04_admin.sql creates it.
 */
export type AdminAction = "create" | "update" | "delete" | "reembed";

export async function recordAdminAction(entry: {
  action: AdminAction;
  resource: string;
  recordId?: string | null;
  changes?: unknown;
}): Promise<void> {
  try {
    await query(
      `INSERT INTO admin_audit (action, resource, record_id, changes)
       VALUES ($1, $2, $3, $4::jsonb)`,
      [
        entry.action,
        entry.resource,
        entry.recordId ?? null,
        JSON.stringify(entry.changes ?? {}),
      ],
    );
  } catch (error) {
    console.error("[admin-audit] could not record action", entry.action, entry.resource, error);
  }
}

/**
 * The subset of a row worth logging: what actually changed.
 * Vectors and other bulk columns never reach here — only submitted fields do.
 */
export function diffRows(
  before: Record<string, unknown> | null,
  after: Record<string, unknown>,
  fields: string[],
): Record<string, { from: unknown; to: unknown }> {
  const changes: Record<string, { from: unknown; to: unknown }> = {};
  for (const field of fields) {
    const from = before ? before[field] : undefined;
    const to = after[field];
    if (JSON.stringify(from ?? null) !== JSON.stringify(to ?? null)) {
      changes[field] = { from: from ?? null, to: to ?? null };
    }
  }
  return changes;
}
