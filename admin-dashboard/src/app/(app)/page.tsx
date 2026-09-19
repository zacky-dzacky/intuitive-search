import Link from "next/link";

import { RankedBars } from "@/components/charts/RankedBars";
import { Badge, Card, LinkButton, Note, PageHeader, StatTile } from "@/components/ui";
import { query } from "@/lib/db";
import { indexStatus } from "@/lib/embeddings";
import { formatRelative } from "@/lib/format";
import { setupState } from "@/lib/setup";

export const dynamic = "force-dynamic";

interface RegistryStats {
  total: string;
  enabled: string;
  with_params: string;
  categories: string;
  slots: string;
}

interface CategoryRow {
  category: string;
  count: string;
}

interface ChangeRow {
  at: Date;
  action: string;
  resource: string;
  record_id: string | null;
}

export default async function OverviewPage() {
  const setup = await setupState();

  if (!setup.databaseReachable) {
    return (
      <>
        <PageHeader title="Overview" />
        <div className="rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger">
          Cannot reach Postgres: {setup.error}. Check <code className="font-mono">DATABASE_URL</code>{" "}
          — the dashboard needs the same database the search backend reads.
        </div>
      </>
    );
  }

  if (!setup.ready) {
    return (
      <>
        <PageHeader title="Overview" />
        <Card title="One migration to run">
          <div className="space-y-3 p-5 text-sm text-text-muted">
            <p>
              The database is reachable but is missing the admin_audit table. The migration is
              additive and idempotent — it changes nothing the search pipeline reads, so the
              backend needs no restart:
            </p>
            <pre className="scroll-x rounded-lg bg-surface-muted p-3 font-mono text-xs">
              {`psql -U bank -d banksearch -f admin-dashboard/db/04_admin.sql`}
            </pre>
            <p>Reload this page once it has run.</p>
          </div>
        </Card>
      </>
    );
  }

  const [statsRows, categoryRows, changeRows, index] = await Promise.all([
    query<RegistryStats>(`
      SELECT COUNT(*)::text                                        AS total,
             COUNT(*) FILTER (WHERE enabled)::text                 AS enabled,
             COUNT(*) FILTER (WHERE has_params)::text              AS with_params,
             COUNT(DISTINCT category)::text                        AS categories,
             COALESCE(SUM(jsonb_array_length(slots)), 0)::text     AS slots
      FROM features
    `),
    query<CategoryRow>(`
      SELECT category, COUNT(*)::text AS count
      FROM features
      GROUP BY 1
      ORDER BY 2 DESC, 1
      LIMIT 10
    `),
    query<ChangeRow>(`
      SELECT at, action, resource, record_id
      FROM admin_audit
      ORDER BY at DESC
      LIMIT 6
    `),
    indexStatus(),
  ]);

  const stats = statsRows[0];
  const missingVectors = index.features - index.withVector;
  const disabled = Number(stats?.total ?? 0) - Number(stats?.enabled ?? 0);

  return (
    <>
      <PageHeader
        title="Overview"
        subtitle="The feature registry is the configuration. A change here is live within 60 seconds — no deploy, no restart."
        actions={
          <>
            <LinkButton href="/playground">Open playground</LinkButton>
            <LinkButton href="/resources/features/new" variant="primary">
              New feature
            </LinkButton>
          </>
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Features"
          value={stats?.total ?? "0"}
          hint={disabled > 0 ? `${disabled} disabled` : "all enabled"}
        />
        <StatTile
          label="Accept parameters"
          value={stats?.with_params ?? "0"}
          hint={`${stats?.slots ?? 0} slots defined — only these can reach Stage 2`}
        />
        <StatTile label="Categories" value={stats?.categories ?? "0"} hint="Open taxonomy" />
        <StatTile
          label="Search index"
          value={index.reachable ? `${index.withVector}/${index.features}` : "—"}
          tone={!index.reachable || missingVectors > 0 || index.lastError ? "warn" : "ok"}
          hint={
            !index.reachable
              ? "Search API unreachable"
              : missingVectors > 0
                ? `${missingVectors} without a vector — see Search index`
                : "Every indexed feature has a vector"
          }
        />
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card
          title="Registry by category"
          description="Category is an open taxonomy — a new one needs no code and no migration."
        >
          <RankedBars
            rows={categoryRows.map((row) => ({
              label: row.category,
              count: Number(row.count),
            }))}
          />
        </Card>

        <Card
          title="Recent changes"
          description="Every write this dashboard made, in place of a deploy log."
          actions={
            <Link href="/resources/changes" className="text-sm font-medium text-accent hover:underline">
              View all
            </Link>
          }
        >
          {changeRows.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-text-muted">
              No changes yet. Editing a feature, or re-embedding one, will show up here.
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {changeRows.map((change, index) => (
                <li
                  key={`${change.at.toISOString()}-${index}`}
                  className="flex flex-wrap items-baseline justify-between gap-2 px-5 py-2.5 text-sm"
                >
                  <span className="flex items-center gap-2">
                    <Badge
                      tone={
                        change.action === "delete"
                          ? "danger"
                          : change.action === "create"
                            ? "ok"
                            : "neutral"
                      }
                    >
                      {change.action}
                    </Badge>
                    <span className="text-text">{change.resource}</span>
                    {change.record_id ? (
                      <span className="font-mono text-xs text-text-faint">{change.record_id}</span>
                    ) : null}
                  </span>
                  <span className="text-xs text-text-muted">{formatRelative(change.at)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="mt-5">
        <Note>
          Adding a feature is one row: keywords and aliases drive full-text and trigram matching,
          the description widens vector recall, and <code className="font-mono">slots</code> drive
          extraction and resolution. The only thing that still needs backend code is a new{" "}
          <em>resolver kind</em> — the built-in set (payee, account, currency, amount, date, period,
          phone, none) covers the shapes a banking form actually has.
        </Note>
      </div>
    </>
  );
}
