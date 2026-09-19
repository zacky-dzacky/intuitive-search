/**
 * Labelled meters for a set of 0–1 scores.
 *
 * Used for the per-channel score breakdown, which is the one number that
 * explains a ranking: four bounded values that are read against each other,
 * not summed. Every row carries its own label and value, so identity never
 * depends on colour — which is also what licenses the two low-contrast
 * categorical slots on a light surface.
 */

const SERIES = ["var(--series-1)", "var(--series-2)", "var(--series-3)", "var(--series-4)"];

/**
 * Slots are assigned in fixed order and never cycled — a fifth row would get a
 * hue already carrying a different meaning. Past the fourth, the mark goes
 * neutral and the label carries identity on its own. (The score breakdown has
 * exactly four channels, so this is a guard, not a common path.)
 */
const NEUTRAL = "var(--border-strong)";

export interface MeterRow {
  label: string;
  value: number;
  /** Optional right-hand annotation, e.g. the channel's weight. */
  note?: string;
}

export function Meters({ rows, max = 1 }: { rows: MeterRow[]; max?: number }) {
  if (!rows.length) return null;
  const ceiling = Math.max(max, ...rows.map((row) => row.value)) || 1;

  return (
    <ul className="viz space-y-2.5">
      {rows.map((row, index) => (
        <li key={row.label}>
          <div className="mb-1 flex items-baseline justify-between gap-3 text-xs">
            <span className="font-medium text-text">{row.label}</span>
            <span className="tabular-nums text-text-muted">
              {row.value.toFixed(2)}
              {row.note ? <span className="ml-2 text-text-faint">{row.note}</span> : null}
            </span>
          </div>
          <div className="h-2 w-full overflow-hidden rounded-full bg-surface-muted">
            <div
              className="h-full rounded-full"
              style={{
                width: `${Math.max(0, Math.min(1, row.value / ceiling)) * 100}%`,
                background: SERIES[index] ?? NEUTRAL,
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}
