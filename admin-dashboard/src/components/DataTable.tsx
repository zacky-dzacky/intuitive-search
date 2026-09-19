import Link from "next/link";

import { formatDateTime, formatNumber, formatRelative, truncate } from "@/lib/format";
import type { ComputedColumn, FieldDef, ResourceDef } from "@/lib/resources/types";
import { can, fieldByName } from "@/lib/resources/types";

import { Badge, EmptyState } from "./ui";

/**
 * The list table for every resource.
 *
 * Columns are whatever `listColumns` names — a real column or a computed
 * alias — and each cell is rendered from its declared type. Sorting is a link,
 * so the page stays a server component and a shared URL reproduces the view.
 */

function CellValue({ field, value }: { field: FieldDef; value: unknown }) {
  if (value === null || value === undefined) {
    return <span className="text-text-faint">—</span>;
  }

  switch (field.type) {
    case "boolean":
      return value ? (
        <Badge tone="ok">yes</Badge>
      ) : (
        <Badge tone="neutral">no</Badge>
      );

    case "timestamp":
      return (
        <span title={formatDateTime(value)} className="whitespace-nowrap text-text-muted">
          {formatRelative(value)}
        </span>
      );

    case "tags": {
      const tags = Array.isArray(value) ? (value as string[]) : [];
      if (!tags.length) return <span className="text-text-faint">—</span>;
      return (
        <span className="flex flex-wrap gap-1">
          {tags.slice(0, 3).map((tag) => (
            <span key={tag} className="rounded bg-surface-muted px-1.5 py-0.5 text-xs">
              {tag}
            </span>
          ))}
          {tags.length > 3 ? (
            <span className="text-xs text-text-faint">+{tags.length - 3}</span>
          ) : null}
        </span>
      );
    }

    case "slots":
      return (
        <span className="tabular-nums text-text-muted">
          {Array.isArray(value) ? value.length : 0}
        </span>
      );

    case "json":
      return (
        <span className="font-mono text-xs text-text-muted">
          {truncate(JSON.stringify(value), 60)}
        </span>
      );

    case "number":
      return <span className="tabular-nums">{formatNumber(Number(value), 0)}</span>;

    case "slug":
      return <span className="font-mono text-xs">{String(value)}</span>;

    case "enum":
      return <Badge>{String(value)}</Badge>;

    default:
      return <span>{truncate(String(value), 90)}</span>;
  }
}

function ComputedValue({ column, value }: { column: ComputedColumn; value: unknown }) {
  if (column.kind === "flag") {
    const on = value === true;
    return (
      <Badge tone={on ? (column.trueTone ?? "warn") : (column.falseTone ?? "ok")}>
        {on ? (column.trueLabel ?? "yes") : (column.falseLabel ?? "no")}
      </Badge>
    );
  }
  if (value === null || value === undefined) return <span className="text-text-faint">—</span>;
  if (typeof value === "boolean") return <Badge>{value ? "yes" : "no"}</Badge>;
  return <span className="tabular-nums text-text-muted">{String(value)}</span>;
}

export function DataTable({
  resource,
  rows,
  sort,
  direction,
  buildSortHref,
}: {
  resource: ResourceDef;
  rows: Record<string, unknown>[];
  sort: string;
  direction: "asc" | "desc";
  /** Given a column, the href that sorts by it — owned by the page. */
  buildSortHref: (column: string) => string;
}) {
  if (!rows.length) {
    return (
      <EmptyState title={`No ${resource.labelPlural.toLowerCase()} match this view.`}>
        {can(resource, "create")
          ? "Clear the search and filters, or create one."
          : "Clear the search and filters."}
      </EmptyState>
    );
  }

  const columns = resource.listColumns.map((name) => {
    const field = fieldByName(resource, name);
    const computed = resource.computedColumns?.[name];
    return { name, field, computed, label: field?.label ?? computed?.label ?? name };
  });

  return (
    <div className="scroll-x">
      <table className="w-full min-w-3xl border-collapse text-sm">
        <thead>
          <tr className="border-b border-border text-left">
            {columns.map((column) => {
              const active = sort === column.name;
              return (
                <th
                  key={column.name}
                  className="whitespace-nowrap px-4 py-2.5 text-xs font-semibold uppercase tracking-wide text-text-faint"
                >
                  <Link
                    href={buildSortHref(column.name)}
                    className={`inline-flex items-center gap-1 transition-colors hover:text-text ${
                      active ? "text-text" : ""
                    }`}
                  >
                    {column.label}
                    <span aria-hidden className={active ? "" : "opacity-0"}>
                      {direction === "asc" ? "↑" : "↓"}
                    </span>
                  </Link>
                </th>
              );
            })}
            <th className="w-px px-4 py-2.5" />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const id = String(row[resource.primaryKey]);
            return (
              <tr
                key={id}
                className="border-b border-border last:border-0 hover:bg-surface-muted/60"
              >
                {columns.map((column) => (
                  <td key={column.name} className="px-4 py-2.5 align-middle">
                    {column.field ? (
                      <CellValue field={column.field} value={row[column.name]} />
                    ) : column.computed ? (
                      <ComputedValue column={column.computed} value={row[column.name]} />
                    ) : (
                      <span className="text-text-faint">—</span>
                    )}
                  </td>
                ))}
                <td className="px-4 py-2.5 text-right">
                  <Link
                    href={`/resources/${resource.name}/${encodeURIComponent(id)}`}
                    className="text-sm font-medium text-accent hover:underline"
                  >
                    {can(resource, "update") ? "Edit" : "View"}
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
