"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, useEffect, useState } from "react";

import { getResource } from "@/lib/resources/registry";

import { buttonClass } from "./ui";

/**
 * Search and filter bar.
 *
 * Filters are whichever fields the registry marks `filterable`, so this
 * component never learns what a "category" or an "action" is. State lives in
 * the URL: the page stays a server component, the back button works, and a
 * filtered view can be pasted to someone else.
 */
export function ListControls({
  resourceName,
  options,
}: {
  resourceName: string;
  /** Values discovered in the data, for open taxonomies like `category`. */
  options?: Record<string, string[]>;
}) {
  const resource = getResource(resourceName)!;
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();

  const [term, setTerm] = useState(params.get("q") ?? "");

  // Keep the box in step when navigation changes the query (back button).
  useEffect(() => {
    setTerm(params.get("q") ?? "");
  }, [params]);

  const push = (mutate: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params.toString());
    mutate(next);
    // Any change to the result set invalidates the current page number.
    next.delete("page");
    router.push(`${pathname}?${next.toString()}`);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    push((next) => {
      if (term.trim()) next.set("q", term.trim());
      else next.delete("q");
    });
  };

  const filterFields = resource.fields.filter((field) => field.filterable);
  const active = filterFields.some((field) => params.get(field.name)) || params.get("q");

  return (
    <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
      <input
        className="field w-56"
        type="search"
        value={term}
        placeholder={`Search ${resource.searchColumns.join(", ")}`}
        onChange={(event) => setTerm(event.target.value)}
      />

      {filterFields.map((field) => {
        const value = params.get(field.name) ?? "";
        const choices =
          field.type === "boolean"
            ? ["true", "false"]
            : (options?.[field.name] ?? [...(field.options ?? [])]);

        // A filterable column with no known value set gets a box to type in
        // rather than an empty dropdown.
        if (!choices.length) {
          return (
            <input
              key={field.name}
              className="field w-40"
              defaultValue={value}
              placeholder={`${field.label}…`}
              onKeyDown={(event) => {
                if (event.key !== "Enter") return;
                event.preventDefault();
                const entered = event.currentTarget.value.trim();
                push((next) => {
                  if (entered) next.set(field.name, entered);
                  else next.delete(field.name);
                });
              }}
            />
          );
        }

        return (
          <select
            key={field.name}
            className="field w-auto"
            value={value}
            onChange={(event) =>
              push((next) => {
                if (event.target.value) next.set(field.name, event.target.value);
                else next.delete(field.name);
              })
            }
          >
            <option value="">{field.label}: any</option>
            {choices.map((choice) => (
              <option key={choice} value={choice}>
                {field.type === "boolean"
                  ? choice === "true"
                    ? `${field.label}: yes`
                    : `${field.label}: no`
                  : choice}
              </option>
            ))}
          </select>
        );
      })}

      <button type="submit" className={buttonClass("secondary")}>
        Search
      </button>

      {active ? (
        <button
          type="button"
          className={buttonClass("ghost")}
          onClick={() => router.push(pathname)}
        >
          Clear
        </button>
      ) : null}
    </form>
  );
}
