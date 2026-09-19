"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { buttonClass } from "./ui";

/**
 * The button that replaces `python precompute_embeddings.py`.
 *
 * A full re-embed is a couple of minutes of forward passes, so the control
 * stays disabled and says what it is doing rather than pretending to be
 * instant.
 */
export function ReembedControls({
  staleCount,
  featureId,
  compact = false,
}: {
  staleCount?: number;
  /** Present on a per-row control: re-embed just this feature. */
  featureId?: string;
  compact?: boolean;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const run = async (payload: Record<string, unknown>, label: string) => {
    setBusy(label);
    setMessage(null);
    setFailed(false);

    try {
      const response = await fetch("/api/embeddings", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
      const body = (await response.json().catch(() => ({}))) as {
        embedded?: number;
        tookMs?: number;
        error?: string;
      };

      if (!response.ok) {
        setFailed(true);
        setMessage(body.error ?? `Failed (${response.status}).`);
        return;
      }

      setMessage(
        body.embedded
          ? `Embedded ${body.embedded} feature${body.embedded === 1 ? "" : "s"} in ${(
              (body.tookMs ?? 0) / 1000
            ).toFixed(1)}s.`
          : "Nothing needed embedding.",
      );
      router.refresh();
    } catch (error) {
      setFailed(true);
      setMessage(error instanceof Error ? error.message : "Failed.");
    } finally {
      setBusy(null);
    }
  };

  if (featureId) {
    return (
      <button
        type="button"
        disabled={busy !== null}
        className="text-sm font-medium text-accent hover:underline disabled:opacity-60"
        onClick={() => run({ featureIds: [featureId] }, "one")}
      >
        {busy ? "Embedding…" : "Re-embed"}
      </button>
    );
  }

  return (
    <div className={compact ? "flex flex-wrap items-center gap-3" : "space-y-3"}>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy !== null || staleCount === 0}
          className={buttonClass("primary")}
          onClick={() => run({ scope: "stale" }, "stale")}
        >
          {busy === "stale"
            ? "Embedding…"
            : staleCount
              ? `Re-embed ${staleCount} stale`
              : "Nothing stale"}
        </button>
        <button
          type="button"
          disabled={busy !== null}
          className={buttonClass("secondary")}
          onClick={() => {
            if (!confirm("Re-embed every feature? This runs the model over the whole registry.")) {
              return;
            }
            void run({ scope: "all" }, "all");
          }}
        >
          {busy === "all" ? "Embedding…" : "Re-embed all"}
        </button>
      </div>

      {message ? (
        <p className={`text-sm ${failed ? "text-danger" : "text-text-muted"}`} role="status">
          {message}
        </p>
      ) : null}
    </div>
  );
}
