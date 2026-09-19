import { NextResponse } from "next/server";

import { guard, jsonError, pgErrorResponse, readJsonObject } from "@/lib/api";
import { diffRows, recordAdminAction } from "@/lib/adminAudit";
import { getResource } from "@/lib/resources/registry";
import { DEFAULT_PAGE_SIZE, insertRow, listRows } from "@/lib/resources/repository";
import { can } from "@/lib/resources/types";
import { checkInvariants, validateRecord } from "@/lib/resources/validate";

/**
 * The collection endpoint for every resource in the registry.
 *
 * `GET  /api/admin/<resource>` — list, with search / filter / sort / paging
 * `POST /api/admin/<resource>` — create
 *
 * There is deliberately no per-resource route file. Adding a resource to the
 * registry adds its endpoints here, already validated and already audited.
 */

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ resource: string }> };

export async function GET(request: Request, context: Context) {
  const denied = await guard();
  if (denied) return denied;

  const { resource: name } = await context.params;
  const resource = getResource(name);
  if (!resource) return jsonError(404, `Unknown resource "${name}".`);

  const url = new URL(request.url);
  const filters: Record<string, string> = {};
  for (const field of resource.fields) {
    if (!field.filterable) continue;
    const value = url.searchParams.get(field.name);
    if (value) filters[field.name] = value;
  }

  try {
    const result = await listRows(resource, {
      search: url.searchParams.get("q") ?? undefined,
      filters,
      sort: url.searchParams.get("sort") ?? undefined,
      direction: url.searchParams.get("dir") === "asc" ? "asc" : "desc",
      page: Number(url.searchParams.get("page") ?? "1"),
      pageSize: Number(url.searchParams.get("pageSize") ?? String(DEFAULT_PAGE_SIZE)),
    });
    return NextResponse.json(result);
  } catch (error) {
    return pgErrorResponse(error);
  }
}

export async function POST(request: Request, context: Context) {
  const denied = await guard();
  if (denied) return denied;

  const { resource: name } = await context.params;
  const resource = getResource(name);
  if (!resource) return jsonError(404, `Unknown resource "${name}".`);
  if (!can(resource, "create")) return jsonError(405, `${resource.labelPlural} cannot be modified from the dashboard.`);

  const parsed = await readJsonObject(request);
  if (!parsed.ok) return parsed.response;

  const validation = validateRecord(resource, parsed.body, "create");
  if (!validation.ok) {
    return jsonError(400, validation.errors[0].message, validation.errors);
  }

  const invariantErrors = checkInvariants(resource, validation.values);
  if (invariantErrors.length) {
    return jsonError(400, invariantErrors[0].message, invariantErrors);
  }

  try {
    const row = await insertRow(resource, validation.values);
    await recordAdminAction({
      action: "create",
      resource: resource.name,
      recordId: String(row[resource.primaryKey]),
      changes: diffRows(null, validation.values, Object.keys(validation.values)),
    });
    return NextResponse.json(row, { status: 201 });
  } catch (error) {
    return pgErrorResponse(error);
  }
}
