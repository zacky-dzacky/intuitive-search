import { Pool, type QueryResultRow } from "pg";

import { env } from "./env";

/**
 * One pool per process, kept on `globalThis` so Next's dev-mode module
 * reloading doesn't leak a new pool on every edit.
 */
declare global {
  // eslint-disable-next-line no-var
  var __adminPgPool: Pool | undefined;
}

function pool(): Pool {
  if (!globalThis.__adminPgPool) {
    globalThis.__adminPgPool = new Pool({
      connectionString: env.databaseUrl(),
      max: 3,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
    });
    globalThis.__adminPgPool.on("error", (err) => {
      console.error("[db] idle client error", err);
    });
  }
  return globalThis.__adminPgPool;
}

export async function query<T extends QueryResultRow = QueryResultRow>(
  text: string,
  values: unknown[] = [],
): Promise<T[]> {
  const result = await pool().query<T>(text, values);
  return result.rows;
}

export async function queryOne<T extends QueryResultRow = QueryResultRow>(
  text: string,
  values: unknown[] = [],
): Promise<T | null> {
  const rows = await query<T>(text, values);
  return rows[0] ?? null;
}

/** Runs `fn` inside a transaction, rolling back on any throw. */
export async function transaction<T>(
  fn: (exec: typeof query) => Promise<T>,
): Promise<T> {
  const client = await pool().connect();
  try {
    await client.query("BEGIN");
    const scoped = async <R extends QueryResultRow = QueryResultRow>(
      text: string,
      values: unknown[] = [],
    ) => (await client.query<R>(text, values)).rows;
    const result = await fn(scoped as typeof query);
    await client.query("COMMIT");
    return result;
  } catch (err) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw err;
  } finally {
    client.release();
  }
}

/**
 * Quotes an SQL identifier, refusing anything that isn't a plain name.
 *
 * Table and column names come from the resource registry (developer-authored
 * config, not user input), but the whole point of this dashboard is that the
 * registry is edited often — so the builder verifies rather than trusts.
 */
export function ident(name: string): string {
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
    throw new Error(`Unsafe SQL identifier: ${JSON.stringify(name)}`);
  }
  return `"${name}"`;
}

/** True when Postgres is reachable — used by the health endpoint. */
export async function pingDatabase(): Promise<boolean> {
  try {
    await query("SELECT 1");
    return true;
  } catch {
    return false;
  }
}
