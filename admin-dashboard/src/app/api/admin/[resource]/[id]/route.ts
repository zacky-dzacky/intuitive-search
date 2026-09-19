import { NextResponse } from "next/server";

import { guard, jsonError, pgErrorResponse, readJsonObject } from "@/lib/api";
import { diffRows, recordAdminAction } from "@/lib/adminAudit";
import { getResource } from "@/lib/resources/registry";
import { deleteRow, getRow, updateRow } from "@/lib/resources/repository";
import { can } from "@/lib/resources/types";
import { checkInvariants, validateRecord } from "@/lib/resources/validate";

/**
 * The single-record endpoint for every resource: GET, PATCH, DELETE.
 *
 * PATCH is a genuine partial update — only submitted keys are written — so a
 * form that shows six of a row's ten columns cannot blank the other four.
 */

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ resource: string; id: string }> };

export async function GET(_request: Request, context: Context) {
  const denied = await guard();
  if (denied) return denied;

  const { resource: name, id } = await context.params;
  const resource = getResource(name);
  if (!resource) return jsonError(404, `Unknown resource "${name}".`);

  try {
    const row = await getRow(resource, decodeURIComponent(id));
    if (!row) return jsonError(404, `No ${resource.label.toLowerCase()} with id "${id}".`);
    return NextResponse.json(row);
  } catch (error) {
    return pgErrorResponse(error);
  }
}

export async function PATCH(request: Request, context: Context) {
  const denied = await guard();
  if (denied) return denied;

  const { resource: name, id: rawId } = await context.params;
  const id = decodeURIComponent(rawId);
  const resource = getResource(name);
  if (!resource) return jsonError(404, `Unknown resource "${name}".`);
  if (!can(resource, "update")) return jsonError(405, `${resource.labelPlural} cannot be modified from the dashboard.`);

  const parsed = await readJsonObject(request);
  if (!parsed.ok) return parsed.response;

  const validation = validateRecord(resource, parsed.body, "update");
  if (!validation.ok) {
    return jsonError(400, validation.errors[0].message, validation.errors);
  }

  try {
    const before = await getRow(resource, id);
    if (!before) return jsonError(404, `No ${resource.label.toLowerCase()} with id "${id}".`);

    // Invariants are checked against the row as it will be, not the patch.
    const invariantErrors = checkInvariants(resource, { ...before, ...validation.values });
    if (invariantErrors.length) {
      return jsonError(400, invariantErrors[0].message, invariantErrors);
    }

    const row = await updateRow(resource, id, validation.values);
    if (!row) return jsonError(404, `No ${resource.label.toLowerCase()} with id "${id}".`);

    const changes = diffRows(before, validation.values, Object.keys(validation.values));
    if (Object.keys(changes).length) {
      await recordAdminAction({
        action: "update",
        resource: resource.name,
        recordId: id,
        changes,
      });
    }
    return NextResponse.json(row);
  } catch (error) {
    return pgErrorResponse(error);
  }
}

export async function DELETE(_request: Request, context: Context) {
  const denied = await guard();
  if (denied) return denied;

  const { resource: name, id: rawId } = await context.params;
  const id = decodeURIComponent(rawId);
  const resource = getResource(name);
  if (!resource) return jsonError(404, `Unknown resource "${name}".`);
  if (!can(resource, "delete")) return jsonError(405, `${resource.labelPlural} cannot be modified from the dashboard.`);

  try {
    const before = await getRow(resource, id);
    const deleted = await deleteRow(resource, id);
    if (!deleted) return jsonError(404, `No ${resource.label.toLowerCase()} with id "${id}".`);

    await recordAdminAction({
      action: "delete",
      resource: resource.name,
      recordId: id,
      // The whole row, so a deletion is recoverable by hand from the log.
      changes: before ?? {},
    });
    return NextResponse.json({ deleted: true, id });
  } catch (error) {
    return pgErrorResponse(error);
  }
}
