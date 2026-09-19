import { formatNumber } from "@/lib/format";

/**
 * A ranked list where the bar is the row.
 *
 * One series, so one hue — a different colour per row would encode rank, and
 * rank is already encoded by order and by the printed count. The count sits
 * beside every bar, which is both the direct label and the table view.
 */
export interface RankedRow {
  label: string;
  count: number;
  /** Small trailing annotation — a confidence, a matched feature. */
  extra?: string | null;
  href?: string;
}

export function RankedBars({
  rows,
  emptyMessage = "Nothing recorded yet.",
  tone = "var(--series-1)",
}: {
  rows: RankedRow[];
  emptyMessage?: string;
  tone?: string;
}) {
  if (!rows.length) {
    return <p className="px-5 py-8 text-center text-sm text-text-muted">{emptyMessage}</p>;
  }

  const max = Math.max(...rows.map((row) => row.count), 1);

  return (
    <ol className="viz space-y-2 px-5 py-4">
      {rows.map((row) => (
        <li key={row.label}>
          <div className="mb-1 flex items-baseline justify-between gap-3">
            <span className="min-w-0 truncate text-sm text-text" title={row.label}>
              {row.label}
            </span>
            <span className="shrink-0 text-xs tabular-nums text-text-muted">
              {row.extra ? <span className="mr-2 text-text-faint">{row.extra}</span> : null}
              {formatNumber(row.count)}
            </span>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-muted">
            <div
              className="h-full rounded-full"
              style={{ width: `${(row.count / max) * 100}%`, background: tone }}
            />
          </div>
        </li>
      ))}
    </ol>
  );
}
