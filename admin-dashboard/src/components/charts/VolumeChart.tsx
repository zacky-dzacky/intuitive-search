"use client";

import { useState } from "react";

import { formatNumber } from "@/lib/format";

/**
 * Searches over time, split by whether Stage 2 fired.
 *
 * Stacked rather than two lines: the question is not "how many of each" but
 * "what share of traffic reached the LLM", and a stack answers that at a
 * glance while still showing total volume. Two series, so a legend is present;
 * a 2px surface gap separates the segments, and the exact numbers live in the
 * table under the chart.
 */

export interface VolumePoint {
  bucket: string;
  total: number;
  llm: number;
}

export function VolumeChart({ points, grain }: { points: VolumePoint[]; grain: "hour" | "day" }) {
  const [hover, setHover] = useState<number | null>(null);

  if (!points.length) {
    return <p className="px-5 py-10 text-center text-sm text-text-muted">No searches in this range.</p>;
  }

  const max = Math.max(...points.map((point) => point.total), 1);
  const label = (iso: string) =>
    new Date(iso).toLocaleString(undefined,
      grain === "hour"
        ? { hour: "2-digit", minute: "2-digit" }
        : { month: "short", day: "numeric" });

  const active = hover !== null ? points[hover] : null;

  return (
    <div className="viz px-5 pb-4 pt-3">
      <div className="mb-3 flex flex-wrap items-center gap-4 text-xs text-text-muted">
        <span className="flex items-center gap-1.5">
          <span
            aria-hidden
            className="inline-block h-2.5 w-2.5 rounded-sm"
            style={{ background: "var(--series-1)" }}
          />
          Stage 1 only
        </span>
        <span className="flex items-center gap-1.5">
          <span
            aria-hidden
            className="inline-block h-2.5 w-2.5 rounded-sm"
            style={{ background: "var(--series-2)" }}
          />
          LLM invoked
        </span>
        <span className="ml-auto tabular-nums text-text-faint">peak {formatNumber(max)}</span>
      </div>

      <div className="relative h-44">
        {/* Recessive gridlines: three hairlines, no boxed frame. */}
        {[0, 0.5, 1].map((fraction) => (
          <div
            key={fraction}
            className="pointer-events-none absolute inset-x-0 border-t"
            style={{
              bottom: `${fraction * 100}%`,
              borderColor: fraction === 0 ? "var(--viz-axis)" : "var(--viz-grid)",
            }}
          />
        ))}

        <div className="flex h-full items-end gap-[2px]">
          {points.map((point, index) => {
            const totalHeight = (point.total / max) * 100;
            const llmShare = point.total ? point.llm / point.total : 0;
            return (
              <button
                key={point.bucket}
                type="button"
                className="group relative flex h-full flex-1 flex-col justify-end"
                onMouseEnter={() => setHover(index)}
                onMouseLeave={() => setHover(null)}
                onFocus={() => setHover(index)}
                onBlur={() => setHover(null)}
                aria-label={`${label(point.bucket)}: ${point.total} searches, ${point.llm} with the LLM`}
              >
                <div
                  className="w-full overflow-hidden rounded-t"
                  style={{ height: `${Math.max(totalHeight, point.total ? 2 : 0)}%` }}
                >
                  <div
                    className="w-full rounded-t"
                    style={{
                      height: `${llmShare * 100}%`,
                      background: "var(--series-2)",
                      // 2px of surface between the two fills.
                      marginBottom: llmShare > 0 && llmShare < 1 ? 2 : 0,
                    }}
                  />
                  <div
                    className="w-full"
                    style={{
                      height: `${(1 - llmShare) * 100}%`,
                      background: "var(--series-1)",
                    }}
                  />
                </div>
                {hover === index ? (
                  <span className="pointer-events-none absolute inset-0 rounded bg-text/5" />
                ) : null}
              </button>
            );
          })}
        </div>
      </div>

      <div className="mt-1.5 flex justify-between text-[0.68rem] tabular-nums text-text-faint">
        <span>{label(points[0].bucket)}</span>
        {points.length > 2 ? <span>{label(points[Math.floor(points.length / 2)].bucket)}</span> : null}
        <span>{label(points[points.length - 1].bucket)}</span>
      </div>

      <p className="mt-2 h-5 text-xs text-text-muted" aria-live="polite">
        {active ? (
          <>
            <span className="font-medium text-text">{label(active.bucket)}</span> ·{" "}
            {formatNumber(active.total)} searches · {formatNumber(active.llm)} reached the LLM
          </>
        ) : null}
      </p>

      <details className="mt-1 text-xs text-text-muted">
        <summary className="cursor-pointer select-none">Show the numbers</summary>
        <div className="scroll-x mt-2">
          <table className="w-full text-left">
            <thead>
              <tr className="text-text-faint">
                <th className="py-1 pr-4 font-medium">Bucket</th>
                <th className="py-1 pr-4 font-medium">Searches</th>
                <th className="py-1 font-medium">LLM</th>
              </tr>
            </thead>
            <tbody className="tabular-nums">
              {points.map((point) => (
                <tr key={point.bucket}>
                  <td className="py-0.5 pr-4">{new Date(point.bucket).toLocaleString()}</td>
                  <td className="py-0.5 pr-4">{point.total}</td>
                  <td className="py-0.5">{point.llm}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
