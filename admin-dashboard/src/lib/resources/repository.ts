import "server-only";

import { ident, query, queryOne } from "@/lib/db";

import { type FieldDef, type ResourceDef, fieldByName, selectableColumns } from "./types";

/**
 * The one repository.
 *
 * Every list, read and write in the dashboard goes through these functions,
 * built from a `ResourceDef` at call time. There is no per-resource SQL in the
 * project, which is what makes "add a table to the registry" a complete
 * feature rather than a starting point.
 *
 * Identifiers are quoted and verified by `ident()`; values are always bound
 * parameters. The only raw SQL that reaches the planner unbound is
 * `computedColumns`, which is developer-authored config.
 */

export interface ListParams {
  search?: string;
  filters?: Record<string, string>;
  sort?: string;
  direction?: "asc" | "desc";
  page?: number;
  pageSize?: number;
}

export interface ListResult {
  rows: Record<string, unknown>[];
  total: number;
  page: number;
  pageSize: number;
  pageCount: number;
}

export const DEFAULT_PAGE_SIZE = 25;
const MAX_PAGE_SIZE = 200;

/** Postgres needs an explicit cast for the container types. */
function castFor(field: FieldDef | undefined): string {
  if (!field) return "";
  if (field.type === "tags") return "::text[]";
  if (field.type === "slots" || field.type === "json") return "::jsonb";
  return "";
}

function bindValue(field: FieldDef | undefined, value: unknown): unknown {
  if (field && (field.type === "slots" || field.type === "json")) {
    return JSON.stringify(value ?? null);
  }
  return value;
}

function selectList(resource: ResourceDef): string {
  const columns = selectableColumns(resource).map((column) => ident(column));
  for (const [alias, computed] of Object.entries(resource.computedColumns ?? {})) {
    columns.push(`(${computed.sql}) AS ${ident(alias)}`);
  }
  return columns.join(", ");
}

/** Sortable = a real column or a computed alias. Anything else is ignored. */
function resolveSort(resource: ResourceDef, sort?: string, direction?: string) {
  const known =
    sort &&
    (fieldByName(resource, sort) || Object.keys(resource.computedColumns ?? {}).includes(sort))
      ? sort
      : resource.defaultSort.column;
  const dir = (direction ?? resource.defaultSort.direction).toLowerCase() === "asc" ? "ASC" : "DESC";
  return { column: known, dir };
}

export async function listRows(resource: ResourceDef, params: ListParams): Promise<ListResult> {
  const values: unknown[] = [];
  const where: string[] = [];

  const search = params.search?.trim();
  if (search && resource.searchColumns.length) {
    values.push(`%${search}%`);
    const placeholder = `$${values.length}`;
    const clauses = resource.searchColumns.map(
      (column) => `${ident(column)}::text ILIKE ${placeholder}`,
    );
    where.push(`(${clauses.join(" OR ")})`);
  }

  for (const [name, raw] of Object.entries(params.filters ?? {})) {
    if (raw === undefined || raw === null || raw === "") continue;
    const field = fieldByName(resource, name);
    if (!field || !field.filterable) continue;

    if (field.type === "boolean") {
      const value = raw === "true";
      values.push(value);
      where.push(`${ident(name)} = $${values.length}`);
    } else {
      values.push(raw);
      where.push(`${ident(name)} = $${values.length}`);
    }
  }

  const { column, dir } = resolveSort(resource, params.sort, params.direction);
  const pageSize = Math.min(Math.max(params.pageSize ?? DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
  const page = Math.max(params.page ?? 1, 1);

  values.push(pageSize, (page - 1) * pageSize);

  const sql = `
    SELECT ${selectList(resource)}, COUNT(*) OVER() AS __total
    FROM ${ident(resource.table)}
    ${where.length ? `WHERE ${where.join(" AND ")}` : ""}
    ORDER BY ${ident(column)} ${dir} NULLS LAST, ${ident(resource.primaryKey)} ASC
    LIMIT $${values.length - 1} OFFSET $${values.length}
  `;

  const rows = await query(sql, values);
  const total = rows.length ? Number(rows[0].__total) : 0;
  for (const row of rows) delete row.__total;

  return {
    rows,
    total,
    page,
    pageSize,
    pageCount: Math.max(Math.ceil(total / pageSize), 1),
  };
}

export async function getRow(
  resource: ResourceDef,
  id: string,
): Promise<Record<string, unknown> | null> {
  return queryOne(
    `SELECT ${selectList(resource)}
     FROM ${ident(resource.table)}
     WHERE ${ident(resource.primaryKey)}::text = $1`,
    [id],
  );
}

export async function insertRow(
  resource: ResourceDef,
  values: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const names = Object.keys(values);
  if (!names.length) throw new Error("Nothing to insert.");

  const columns = names.map((name) => ident(name));
  const placeholders = names.map((name, index) => `$${index + 1}${castFor(fieldByName(resource, name))}`);
  const bound = names.map((name) => bindValue(fieldByName(resource, name), values[name]));

  const rows = await query(
    `INSERT INTO ${ident(resource.table)} (${columns.join(", ")})
     VALUES (${placeholders.join(", ")})
     RETURNING ${selectList(resource)}`,
    bound,
  );
  return rows[0];
}

export async function updateRow(
  resource: ResourceDef,
  id: string,
  values: Record<string, unknown>,
): Promise<Record<string, unknown> | null> {
  const names = Object.keys(values);
  const assignments = names.map(
    (name, index) => `${ident(name)} = $${index + 1}${castFor(fieldByName(resource, name))}`,
  );
  if (resource.touchColumn) {
    assignments.push(`${ident(resource.touchColumn)} = now()`);
  }
  if (!assignments.length) return getRow(resource, id);

  const bound = names.map((name) => bindValue(fieldByName(resource, name), values[name]));
  bound.push(id);

  const rows = await query(
    `UPDATE ${ident(resource.table)}
     SET ${assignments.join(", ")}
     WHERE ${ident(resource.primaryKey)}::text = $${bound.length}
     RETURNING ${selectList(resource)}`,
    bound,
  );
  return rows[0] ?? null;
}

export async function deleteRow(resource: ResourceDef, id: string): Promise<boolean> {
  const rows = await query(
    `DELETE FROM ${ident(resource.table)}
     WHERE ${ident(resource.primaryKey)}::text = $1
     RETURNING ${ident(resource.primaryKey)}`,
    [id],
  );
  return rows.length > 0;
}

/** Values already in use for an open taxonomy (`optionsFromColumn`). */
export async function distinctValues(resource: ResourceDef, column: string): Promise<string[]> {
  const field = fieldByName(resource, column);
  if (!field) return [];
  const rows = await query<{ value: string | null }>(
    `SELECT DISTINCT ${ident(column)}::text AS value
     FROM ${ident(resource.table)}
     WHERE ${ident(column)} IS NOT NULL
     ORDER BY 1
     LIMIT 200`,
  );
  return rows.map((row) => row.value).filter((value): value is string => Boolean(value));
}

/** Enum options for every `optionsFromColumn` field on a resource. */
export async function dynamicOptions(resource: ResourceDef): Promise<Record<string, string[]>> {
  const dynamic = resource.fields.filter((field) => field.optionsFromColumn);
  const entries = await Promise.all(
    dynamic.map(async (field) => {
      const existing = await distinctValues(resource, field.name);
      const merged = Array.from(new Set([...(field.options ?? []), ...existing])).sort();
      return [field.name, merged] as const;
    }),
  );
  return Object.fromEntries(entries);
}
