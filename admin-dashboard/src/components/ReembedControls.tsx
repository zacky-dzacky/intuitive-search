"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { buttonClass } from "./ui";

/**
 * "Rebuild now" for operators who do not want to wait out the backend's
 * refresh interval, and "Re-embed all" for after an embedding model change.
 *
 * A forced rebuild sends the whole catalogue to the embedding provider in
 * one call, so the control stays disabled and says what it is doing rather
 * than pretending to be instant.
 */
export function ReembedControls({ compact = false }: { compact?: boolean }) {
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const run = async (force: boolean, label: string) => {
    setBusy(label);
    setMessage(null);
    setFailed(false);

    try {
      const response = await fetch("/api/embeddings", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ force }),
      });
      const body = (await response.json().catch(() => ({}))) as {
        rebuilt?: boolean;
        embedded?: number;
        withVector?: number;
        features?: number;
        tookMs?: number;
        error?: string;
      };

      if (!response.ok) {
        setFailed(true);
        setMessage(body.error ?? `Failed (${response.status}).`);
        return;
      }

      if (body.error) {
        setFailed(true);
        setMessage(
          `Index rebuilt with ${body.withVector ?? 0} of ${body.features ?? 0} vectors — embedding failed: ${body.error}`,
        );
      } else if (!body.rebuilt) {
        setMessage("Nothing changed since the last build.");
      } else {
        setMessage(
          `Rebuilt: ${body.features ?? 0} features, ${body.embedded ?? 0} embedded, in ${(
            (body.tookMs ?? 0) / 1000
          ).toFixed(1)}s.`,
        );
      }
      router.refresh();
    } catch (error) {
      setFailed(true);
      setMessage(error instanceof Error ? error.message : "Failed.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className={compact ? "flex flex-wrap items-center gap-3" : "space-y-3"}>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy !== null}
          className={buttonClass("primary")}
          onClick={() => run(false, "rebuild")}
        >
          {busy === "rebuild" ? "Rebuilding…" : "Rebuild index"}
        </button>
        <button
          type="button"
          disabled={busy !== null}
          className={buttonClass("secondary")}
          onClick={() => {
            if (!confirm("Re-embed every feature? This sends the whole registry to the embedding provider.")) {
              return;
            }
            void run(true, "all");
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
