import Link from "next/link";

import { RankedBars } from "@/components/charts/RankedBars";
import { VolumeChart } from "@/components/charts/VolumeChart";
import { Card, EmptyState, Note, PageHeader, StatTile, buttonClass } from "@/components/ui";
import {
  RANGE_LABELS,
  type RangeKey,
  actionBreakdown,
  hasAuditData,
  latencyByPath,
  parseRange,
  summary,
  topFeatures,
  topQueries,
  unresolvedQueries,
  volume,
} from "@/lib/analytics";
import { formatNumber, formatPercent } from "@/lib/format";

export const dynamic = "force-dynamic";

const RANGES: RangeKey[] = ["24h", "7d", "30d", "all"];

/**
 * What search actually did.
 *
 * The two numbers worth watching are the LLM invocation rate — the cost
 * control, and the thing a badly written keyword list quietly breaks — and the
 * unresolved queries, which are the registry's to-do list in the users' own
 * words.
 */
export default async function AnalyticsPage({
  searchParams,
}: {
  searchParams: Promise<{ range?: string }>;
}) {
  const { range: rawRange } = await searchParams;
  const range = parseRange(rawRange);

  const anyData = await hasAuditData();
  if (!anyData) {
    return (
      <>
        <PageHeader title="Analytics" subtitle="Aggregates over the search_audit table." />
        <div className="card">
          <EmptyState title="No search events recorded yet.">
            <p>
              Rows land in <code className="font-mono text-xs">search_audit</code> when a search
              runs through the playground with “record this search” left on. The Spring backend
              does not write its own rows yet — the README shows the ~20 lines that make it do so,
              after which this page reflects real traffic.
            </p>
          </EmptyState>
        </div>
      </>
    );
  }

  const [stats, points, actions, features, queries, unresolved, latency] = await Promise.all([
    summary(range),
    volume(range),
    actionBreakdown(range),
    topFeatures(range),
    topQueries(range),
    unresolvedQueries(range),
    latencyByPath(range),
  ]);

  const llmTone = stats.llmRate > 0.5 ? "warn" : "ok";

  return (
    <>
      <PageHeader
        title="Analytics"
        subtitle="Aggregates over search_audit — what was asked, what matched, and what it cost."
        actions={
          <div className="flex gap-1.5">
            {RANGES.map((option) => (
              <Link
                key={option}
                href={`/analytics?range=${option}`}
                className={buttonClass(option === range ? "primary" : "secondary")}
              >
                {option}
              </Link>
            ))}
          </div>
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Searches"
          value={formatNumber(stats.total)}
          hint={RANGE_LABELS[range]}
        />
        <StatTile
          label="LLM invocation rate"
          value={formatPercent(stats.llmRate, 1)}
          tone={llmTone}
          hint={`${formatNumber(stats.llmInvoked)} of ${formatNumber(stats.total)} reached Stage 2`}
        />
        <StatTile
          label="Latency p50 / p95"
          value={`${formatNumber(stats.p50)} / ${formatNumber(stats.p95)} ms`}
          hint="Budgets: <100ms navigation, <400ms command"
        />
        <StatTile
          label="Unresolved"
          value={formatNumber(stats.unresolvedCount)}
          tone={stats.unresolvedCount > 0 ? "warn" : "ok"}
          hint="Queries that returned suggestions or nothing"
        />
      </div>

      <Card
        title="Search volume"
        description="Stacked by whether the query reached Stage 2. A rising orange share is a cost regression."
        className="mb-5"
      >
        <VolumeChart points={points} grain={range === "24h" ? "hour" : "day"} />
      </Card>

      <div className="mb-5 grid gap-5 lg:grid-cols-2">
        <Card
          title="Unresolved queries"
          description="The registry's to-do list: what users asked for and did not get."
        >
          <RankedBars
            rows={unresolved}
            tone="var(--series-2)"
            emptyMessage="Every query in this range matched a feature."
          />
        </Card>

        <Card title="Top queries" description="Most frequent, with the feature they usually match.">
          <RankedBars rows={queries} />
        </Card>

        <Card title="Matched features" description="Which features the traffic actually lands on.">
          <RankedBars rows={features} />
        </Card>

        <Card title="Actions returned" description="The strongest action search can return is prefill_form.">
          <RankedBars rows={actions} />
        </Card>
      </div>

      <Card
        title="Latency by path"
        description="The LLM path is a different budget from the navigation path; averaging them together hides both."
      >
        <div className="scroll-x">
          <table className="w-full min-w-lg text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-text-faint">
                <th className="px-5 py-2.5 font-semibold">Path</th>
                <th className="px-5 py-2.5 font-semibold">Requests</th>
                <th className="px-5 py-2.5 font-semibold">p50</th>
                <th className="px-5 py-2.5 font-semibold">p95</th>
              </tr>
            </thead>
            <tbody>
              {latency.map((row) => (
                <tr key={String(row.llm)} className="border-b border-border last:border-0">
                  <td className="px-5 py-2.5">
                    {row.llm ? "Stage 2 fired (LLM)" : "Stage 1 only"}
                  </td>
                  <td className="px-5 py-2.5 tabular-nums">{formatNumber(row.count)}</td>
                  <td className="px-5 py-2.5 tabular-nums">{formatNumber(row.p50)} ms</td>
                  <td className="px-5 py-2.5 tabular-nums">{formatNumber(row.p95)} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <div className="mt-5">
        <Note>
          Average confidence across this range is{" "}
          {stats.avgConfidence === null ? "—" : stats.avgConfidence.toFixed(2)}. Confidence is
          diluted by parameter text — a command query shares fewer words with the registry than a
          bare feature name does — so a lower average on command-heavy traffic is expected, not a
          regression.
        </Note>
      </div>
    </>
  );
}
