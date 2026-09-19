import Link from "next/link";
import { notFound } from "next/navigation";

import { DataTable } from "@/components/DataTable";
import { ListControls } from "@/components/ListControls";
import { LinkButton, PageHeader, buttonClass } from "@/components/ui";
import { getResource } from "@/lib/resources/registry";
import { DEFAULT_PAGE_SIZE, dynamicOptions, listRows } from "@/lib/resources/repository";
import { can } from "@/lib/resources/types";

export const dynamic = "force-dynamic";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

/**
 * The list page for every resource in the registry.
 *
 * One file, five sections in the sidebar. Search, filters, sorting and paging
 * all live in the URL and are interpreted against the resource's own
 * description of itself.
 */
export default async function ResourceListPage({
  params,
  searchParams,
}: {
  params: Promise<{ resource: string }>;
  searchParams: SearchParams;
}) {
  const { resource: name } = await params;
  const resource = getResource(name);
  if (!resource) notFound();

  const search = await searchParams;
  const single = (key: string) => {
    const value = search[key];
    return Array.isArray(value) ? value[0] : value;
  };

  const filters: Record<string, string> = {};
  for (const field of resource.fields) {
    if (!field.filterable) continue;
    const value = single(field.name);
    if (value) filters[field.name] = value;
  }

  const sort = single("sort") ?? resource.defaultSort.column;
  const direction = (single("dir") ?? resource.defaultSort.direction) === "asc" ? "asc" : "desc";
  const page = Math.max(Number(single("page") ?? "1") || 1, 1);

  const [result, options] = await Promise.all([
    listRows(resource, {
      search: single("q"),
      filters,
      sort,
      direction,
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    }),
    dynamicOptions(resource),
  ]);

  const hrefWith = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams();
    for (const [key, value] of Object.entries(search)) {
      const first = Array.isArray(value) ? value[0] : value;
      if (first) next.set(key, first);
    }
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined) next.delete(key);
      else next.set(key, value);
    }
    const queryString = next.toString();
    return `/resources/${resource.name}${queryString ? `?${queryString}` : ""}`;
  };

  const first = result.total === 0 ? 0 : (result.page - 1) * result.pageSize + 1;
  const last = Math.min(result.page * result.pageSize, result.total);

  return (
    <>
      <PageHeader
        title={resource.labelPlural}
        subtitle={resource.description}
        actions={
          can(resource, "create") ? (
            <LinkButton href={`/resources/${resource.name}/new`} variant="primary">
              New {resource.label.toLowerCase()}
            </LinkButton>
          ) : null
        }
      />

      <div className="mb-4">
        <ListControls resourceName={resource.name} options={options} />
      </div>

      <div className="card overflow-hidden">
        <DataTable
          resource={resource}
          rows={result.rows}
          sort={sort}
          direction={direction}
          buildSortHref={(column) =>
            hrefWith({
              sort: column,
              // Clicking the active column flips it; a new column starts
              // descending, which is what "show me the interesting end" means
              // for dates and counts alike.
              dir: sort === column && direction === "desc" ? "asc" : "desc",
            })
          }
        />

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-4 py-3 text-sm text-text-muted">
          <span className="tabular-nums">
            {first}–{last} of {result.total}
          </span>
          <div className="flex gap-2">
            <Link
              href={hrefWith({ page: String(result.page - 1) })}
              aria-disabled={result.page <= 1}
              className={buttonClass(
                "secondary",
                result.page <= 1 ? "pointer-events-none opacity-50" : "",
              )}
            >
              Previous
            </Link>
            <Link
              href={hrefWith({ page: String(result.page + 1) })}
              aria-disabled={result.page >= result.pageCount}
              className={buttonClass(
                "secondary",
                result.page >= result.pageCount ? "pointer-events-none opacity-50" : "",
              )}
            >
              Next
            </Link>
          </div>
        </div>
      </div>
    </>
  );
}
