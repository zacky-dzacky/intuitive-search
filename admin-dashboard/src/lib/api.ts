import "server-only";

import { NextResponse } from "next/server";

import { isAuthenticated } from "./session";
import type { ValidationError } from "./resources/validate";

/** Shared response shapes so the client never has to guess. */
export function jsonError(status: number, message: string, errors?: ValidationError[]) {
  return NextResponse.json({ error: message, errors: errors ?? [] }, { status });
}

/** Returns a 401 response when there is no valid session, else null. */
export async function guard(): Promise<NextResponse | null> {
  if (await isAuthenticated()) return null;
  return jsonError(401, "Not signed in.");
}

interface PgError {
  code?: string;
  detail?: string;
  message?: string;
  constraint?: string;
  column?: string;
  table?: string;
}

/**
 * Turns a Postgres error into something an operator can act on.
 *
 * The interesting cases are the ones caused by drift between the registry and
 * the database — a column renamed, a migration not run. Those must not
 * surface as "500 internal error", because the fix is a one-liner and the
 * error message is the only place that says so.
 */
export function pgErrorResponse(error: unknown): NextResponse {
  const pg = error as PgError;
  switch (pg?.code) {
    case "23505":
      return jsonError(409, `That already exists. ${pg.detail ?? ""}`.trim());
    case "23503":
      return jsonError(409, `Referenced row does not exist. ${pg.detail ?? ""}`.trim());
    case "23502":
      return jsonError(400, `Column "${pg.column ?? "?"}" cannot be empty.`);
    case "23514":
      return jsonError(
        400,
        `The database rejected this row (constraint ${pg.constraint ?? "?"}). ${pg.detail ?? ""}`.trim(),
      );
    case "22001":
      return jsonError(400, "A value is longer than its column allows.");
    case "42703":
      return jsonError(
        500,
        `Column missing: ${pg.message ?? ""}. The resource registry describes a column this table does not have.`,
      );
    case "42P01":
      return jsonError(
        500,
        `Table missing: ${pg.message ?? ""}. Run admin-dashboard/db/04_admin.sql against the database.`,
      );
    default:
      console.error("[api] unhandled database error", error);
      return jsonError(500, pg?.message ?? "Unexpected database error.");
  }
}

/** Parses a JSON body, rejecting anything that isn't an object. */
export async function readJsonObject(
  request: Request,
): Promise<{ ok: true; body: Record<string, unknown> } | { ok: false; response: NextResponse }> {
  try {
    const body = await request.json();
    if (typeof body !== "object" || body === null || Array.isArray(body)) {
      return { ok: false, response: jsonError(400, "Request body must be a JSON object.") };
    }
    return { ok: true, body: body as Record<string, unknown> };
  } catch {
    return { ok: false, response: jsonError(400, "Request body must be valid JSON.") };
  }
}
