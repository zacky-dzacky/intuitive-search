"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useMemo, useState } from "react";

import { getResource } from "@/lib/resources/registry";
import { can, writableFields } from "@/lib/resources/types";
import { checkInvariants, validateRecord } from "@/lib/resources/validate";

import { FieldWidget } from "./fields";
import { Note, buttonClass } from "./ui";

/**
 * The create/edit form for every resource.
 *
 * The resource is looked up from the registry by name rather than passed in as
 * a prop: `ResourceDef` carries invariant *functions*, which cannot cross the
 * server/client boundary — and importing it here means the same validation
 * runs before the request as runs inside it, so a mistake is caught while the
 * cursor is still in the field.
 */
export function ResourceForm({
  resourceName,
  record,
  options,
}: {
  resourceName: string;
  /** Existing row for edit; absent for create. */
  record?: Record<string, unknown>;
  /** Discovered values for open taxonomies, keyed by field name. */
  options?: Record<string, string[]>;
}) {
  const resource = getResource(resourceName)!;
  const mode = record ? "update" : "create";
  const router = useRouter();

  const fields = useMemo(() => writableFields(resource, mode), [resource, mode]);

  const [values, setValues] = useState<Record<string, unknown>>(() => {
    const initial: Record<string, unknown> = {};
    for (const field of resource.fields) {
      initial[field.name] = record ? record[field.name] : (field.defaultValue ?? null);
    }
    return initial;
  });

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [reindex, setReindex] = useState(Boolean(resource.reindexOnSave));
  const [status, setStatus] = useState<string | null>(null);

  const id = record ? String(record[resource.primaryKey]) : null;
  const listHref = `/resources/${resource.name}`;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setFormError(null);
    setStatus(null);

    // Only submit the fields this form actually owns.
    const payload: Record<string, unknown> = {};
    for (const field of fields) payload[field.name] = values[field.name];

    const validation = validateRecord(resource, payload, mode);
    const invariantErrors = validation.ok
      ? checkInvariants(resource, { ...(record ?? {}), ...validation.values })
      : [];
    const allErrors = [...validation.errors, ...invariantErrors];

    if (allErrors.length) {
      setErrors(Object.fromEntries(allErrors.map((error) => [error.field, error.message])));
      setFormError(allErrors[0].message);
      setBusy(false);
      return;
    }
    setErrors({});

    try {
      const response = await fetch(
        mode === "create" ? `/api/admin/${resource.name}` : `/api/admin/${resource.name}/${encodeURIComponent(id!)}`,
        {
          method: mode === "create" ? "POST" : "PATCH",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(validation.values),
        },
      );

      const body = (await response.json().catch(() => ({}))) as {
        error?: string;
        errors?: { field: string; message: string }[];
        [key: string]: unknown;
      };

      if (!response.ok) {
        setFormError(body.error ?? `Save failed (${response.status}).`);
        if (body.errors?.length) {
          setErrors(Object.fromEntries(body.errors.map((error) => [error.field, error.message])));
        }
        return;
      }

      const savedId = String(body[resource.primaryKey] ?? id);

      if (resource.reindexOnSave && reindex) {
        setStatus("Saved. Updating the search index…");
        // The backend re-embeds only the rows whose text changed, so this
        // is one embedding call for the row just saved.
        const indexResponse = await fetch("/api/embeddings", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ force: false }),
        });
        if (!indexResponse.ok) {
          const indexBody = (await indexResponse.json().catch(() => ({}))) as { error?: string };
          // The row is saved either way — say what happened rather than
          // rolling back a good edit over an unavailable backend. The
          // backend picks the change up on its next refresh regardless.
          setFormError(
            `Saved, but the index rebuild failed: ${indexBody.error ?? indexResponse.status}. The backend will pick the change up within 60 seconds.`,
          );
          setBusy(false);
          router.refresh();
          return;
        }
      }

      router.push(listHref);
      router.refresh();
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "Save failed.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!id) return;
    if (!confirm(`Delete ${resource.label.toLowerCase()} "${id}"? This cannot be undone.`)) return;

    setBusy(true);
    const response = await fetch(`/api/admin/${resource.name}/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as { error?: string };
      setFormError(body.error ?? "Delete failed.");
      setBusy(false);
      return;
    }
    router.push(listHref);
    router.refresh();
  };

  return (
    <form onSubmit={submit} className="space-y-5">
      {resource.formNote ? <Note>{resource.formNote}</Note> : null}

      <div className="card grid gap-5 p-5 sm:grid-cols-2">
        {fields.map((field) => {
          const error = errors[field.name];
          const full = field.span !== "half";
          return (
            <div key={field.name} className={full ? "sm:col-span-2" : ""}>
              <label className="block">
                <span className="mb-1.5 flex items-baseline gap-2">
                  <span className="text-sm font-medium text-text">{field.label}</span>
                  {field.required ? (
                    <span className="text-xs text-text-faint">required</span>
                  ) : null}
                  <span className="ml-auto font-mono text-[0.68rem] text-text-faint">
                    {field.name}
                  </span>
                </span>
                <FieldWidget
                  field={field}
                  value={values[field.name]}
                  options={options?.[field.name]}
                  invalid={Boolean(error)}
                  disabled={busy}
                  onChange={(next) =>
                    setValues((current) => ({ ...current, [field.name]: next }))
                  }
                />
              </label>
              {error ? (
                <p className="mt-1 text-xs text-danger">{error}</p>
              ) : field.help ? (
                <p className="mt-1 text-xs leading-relaxed text-text-muted">{field.help}</p>
              ) : null}
            </div>
          );
        })}
      </div>

      {formError ? (
        <p className="rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger" role="alert">
          {formError}
        </p>
      ) : null}
      {status ? <p className="text-sm text-text-muted">{status}</p> : null}

      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={busy} className={buttonClass("primary")}>
          {busy ? "Saving…" : mode === "create" ? `Create ${resource.label.toLowerCase()}` : "Save changes"}
        </button>
        <Link href={listHref} className={buttonClass("secondary")}>
          Cancel
        </Link>

        {resource.reindexOnSave ? (
          <label className="flex cursor-pointer items-center gap-2 text-sm text-text-muted">
            <input
              type="checkbox"
              className="h-4 w-4 accent-[var(--accent)]"
              checked={reindex}
              disabled={busy}
              onChange={(event) => setReindex(event.target.checked)}
            />
            Update search index after saving
          </label>
        ) : null}

        {mode === "update" && can(resource, "delete") ? (
          <button
            type="button"
            onClick={remove}
            disabled={busy}
            className={buttonClass("danger", "ml-auto")}
          >
            Delete
          </button>
        ) : null}
      </div>
    </form>
  );
}
