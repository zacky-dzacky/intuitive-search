import { formatDateTime } from "@/lib/format";
import type { ResourceDef } from "@/lib/resources/types";

import { Badge } from "./ui";

/**
 * Read-only view of a row.
 *
 * Used for resources the registry marks non-writable — the log tables — so
 * they get a detail page without a form and without a second code path.
 */
export function RecordDetail({
  resource,
  record,
}: {
  resource: ResourceDef;
  record: Record<string, unknown>;
}) {
  return (
    <dl className="card divide-y divide-border">
      {resource.fields.map((field) => {
        const value = record[field.name];
        return (
          <div key={field.name} className="grid gap-1 px-5 py-3 sm:grid-cols-[12rem_1fr] sm:gap-4">
            <dt className="text-sm font-medium text-text">
              {field.label}
              <span className="ml-2 font-mono text-[0.68rem] text-text-faint">{field.name}</span>
            </dt>
            <dd className="min-w-0 text-sm text-text-muted">
              <Value field={field.type} value={value} />
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

function Value({ field, value }: { field: string; value: unknown }) {
  if (value === null || value === undefined) return <span className="text-text-faint">—</span>;

  if (field === "timestamp") return <>{formatDateTime(value)}</>;
  if (field === "boolean") return <Badge tone={value ? "ok" : "neutral"}>{value ? "yes" : "no"}</Badge>;

  if (field === "tags" && Array.isArray(value)) {
    return (
      <span className="flex flex-wrap gap-1">
        {value.map((tag) => (
          <span key={String(tag)} className="rounded bg-surface-muted px-1.5 py-0.5 text-xs">
            {String(tag)}
          </span>
        ))}
      </span>
    );
  }

  if (field === "json" || field === "slots") {
    return (
      <pre className="scroll-x rounded-lg bg-surface-muted p-3 font-mono text-xs leading-relaxed">
        {JSON.stringify(value, null, 2)}
      </pre>
    );
  }

  return <span className="break-words">{String(value)}</span>;
}
