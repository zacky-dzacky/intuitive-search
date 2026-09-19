"use client";

import { type FormEvent, useState } from "react";

import { Meters } from "./charts/Meters";
import { Badge, Card, buttonClass } from "./ui";

/**
 * Runs a query against the live search API and shows the whole diagnostics
 * block.
 *
 * The point of this page is the *why*, not the answer: which channel ranked
 * the feature, what signal score the query earned, whether Stage 2 fired and
 * where the time went. That is what the weights and thresholds in
 * application.yml are tuned against, and reading it from a curl transcript is
 * how tuning stops happening.
 */

interface SearchResult {
  matched_feature: string | null;
  display_name: string | null;
  route: string | null;
  confidence: number;
  params_extracted: boolean;
  slots: Record<string, unknown> | null;
  action: string;
  suggestions: {
    feature_id: string;
    display_name: string;
    route: string;
    category: string;
    confidence: number;
  }[] | null;
  diagnostics: {
    timings_ms: Record<string, number>;
    total_ms: number;
    llm_invoked: boolean;
    llm_provider: string | null;
    signal_score: number;
    signals: string[] | null;
    stopped_at: string | null;
    warnings: string[] | null;
    score_breakdown: Record<string, number> | null;
  } | null;
}

interface Envelope {
  round_trip_ms: number;
  mode: string;
  result: SearchResult;
}

const EXAMPLES = [
  "trf",
  "e-stmt",
  "where did my money go",
  "transfer to mom's 10000 usd",
  "download e-statement for march",
];

const ACTION_TONE: Record<string, "accent" | "ok" | "warn" | "neutral"> = {
  navigate: "ok",
  prefill_form: "accent",
  suggest: "warn",
  none: "neutral",
};

export function Playground() {
  const [query, setQuery] = useState("");
  const [userId, setUserId] = useState("user_1");
  const [mode, setMode] = useState<"search" | "suggest">("search");
  const [allowExtraction, setAllowExtraction] = useState(true);
  const [record, setRecord] = useState(true);

  const [response, setResponse] = useState<Envelope | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (text: string) => {
    if (!text.trim()) return;
    setBusy(true);
    setError(null);

    try {
      const result = await fetch("/api/search", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          query: text,
          user_id: userId,
          allow_extraction: allowExtraction,
          mode,
          record,
        }),
      });
      const body = await result.json();
      if (!result.ok) {
        setError((body as { error?: string }).error ?? `Request failed (${result.status}).`);
        setResponse(null);
        return;
      }
      setResponse(body as Envelope);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed.");
    } finally {
      setBusy(false);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void run(query);
  };

  const diagnostics = response?.result.diagnostics ?? null;
  const breakdown = diagnostics?.score_breakdown ?? null;
  const timings = diagnostics?.timings_ms ?? null;
  const totalTime = diagnostics?.total_ms ?? 0;

  return (
    <div className="space-y-5">
      <Card>
        <form onSubmit={submit} className="space-y-4 p-5">
          <div className="flex flex-wrap gap-2">
            <input
              className="field flex-1"
              value={query}
              autoFocus
              placeholder="transfer to mom's 10000 usd"
              maxLength={512}
              onChange={(event) => setQuery(event.target.value)}
            />
            <button type="submit" disabled={busy || !query.trim()} className={buttonClass("primary")}>
              {busy ? "Running…" : "Run"}
            </button>
          </div>

          <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-text-muted">
            <label className="flex items-center gap-2">
              <span className="text-xs font-medium">user_id</span>
              <input
                className="field w-32 py-1"
                value={userId}
                onChange={(event) => setUserId(event.target.value)}
              />
            </label>

            <label className="flex items-center gap-2">
              <span className="text-xs font-medium">endpoint</span>
              <select
                className="field w-auto py-1"
                value={mode}
                onChange={(event) => setMode(event.target.value as "search" | "suggest")}
              >
                <option value="search">POST /api/search</option>
                <option value="suggest">GET /api/search/suggest</option>
              </select>
            </label>

            <label
              className={`flex items-center gap-2 ${mode === "suggest" ? "opacity-50" : ""}`}
              title={
                mode === "suggest"
                  ? "The suggest endpoint hard-codes allow_extraction=false server-side."
                  : undefined
              }
            >
              <input
                type="checkbox"
                className="h-4 w-4 accent-[var(--accent)]"
                checked={mode === "suggest" ? false : allowExtraction}
                disabled={mode === "suggest"}
                onChange={(event) => setAllowExtraction(event.target.checked)}
              />
              <span className="text-xs">allow_extraction</span>
            </label>

            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                className="h-4 w-4 accent-[var(--accent)]"
                checked={record}
                onChange={(event) => setRecord(event.target.checked)}
              />
              <span className="text-xs">record this search in the log</span>
            </label>
          </div>

          <div className="flex flex-wrap gap-1.5">
            {EXAMPLES.map((example) => (
              <button
                key={example}
                type="button"
                className="rounded-full border border-border px-2.5 py-1 text-xs text-text-muted transition-colors hover:border-border-strong hover:text-text"
                onClick={() => {
                  setQuery(example);
                  void run(example);
                }}
              >
                {example}
              </button>
            ))}
          </div>
        </form>
      </Card>

      {error ? (
        <p className="rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger" role="alert">
          {error}
        </p>
      ) : null}

      {response ? (
        <>
          <Card>
            <div className="flex flex-wrap items-start justify-between gap-4 p-5">
              <div className="min-w-0">
                <div className="mb-1.5 flex flex-wrap items-center gap-2">
                  <Badge tone={ACTION_TONE[response.result.action] ?? "neutral"}>
                    {response.result.action}
                  </Badge>
                  {diagnostics?.llm_invoked ? (
                    <Badge tone="warn">LLM · {diagnostics.llm_provider ?? "?"}</Badge>
                  ) : (
                    <Badge tone="ok">no LLM call</Badge>
                  )}
                  {response.mode === "suggest" ? <Badge>suggest endpoint</Badge> : null}
                </div>
                <p className="text-lg font-semibold text-text">
                  {response.result.display_name ?? "No feature matched"}
                </p>
                <p className="font-mono text-xs text-text-muted">
                  {response.result.matched_feature ?? "—"}
                  {response.result.route ? ` · ${response.result.route}` : ""}
                </p>
              </div>

              <div className="flex gap-6 text-right">
                <div>
                  <p className="text-xs uppercase tracking-wide text-text-faint">Confidence</p>
                  <p className="text-xl font-semibold tabular-nums text-text">
                    {response.result.confidence.toFixed(3)}
                  </p>
                </div>
                <div>
                  <p className="text-xs uppercase tracking-wide text-text-faint">API / round trip</p>
                  <p className="text-xl font-semibold tabular-nums text-text">
                    {totalTime}
                    <span className="text-sm text-text-muted"> / {response.round_trip_ms} ms</span>
                  </p>
                </div>
              </div>
            </div>
          </Card>

          <div className="grid gap-5 lg:grid-cols-2">
            <Card
              title="Score breakdown"
              description="Which retrieval channel actually found this feature. Containment is the one that survives a query padded with parameter text."
            >
              <div className="p-5">
                {breakdown && Object.keys(breakdown).length ? (
                  <Meters
                    rows={Object.entries(breakdown).map(([label, value]) => ({
                      label,
                      value: Number(value),
                    }))}
                  />
                ) : (
                  <p className="text-sm text-text-muted">No breakdown returned.</p>
                )}
              </div>
            </Card>

            <Card
              title="The LLM gate"
              description="Four gates must all open before Stage 2 runs. This is the signal half."
            >
              <div className="space-y-3 p-5 text-sm">
                <p className="flex items-baseline justify-between gap-3">
                  <span className="text-text-muted">Signal score</span>
                  <span className="text-lg font-semibold tabular-nums text-text">
                    {diagnostics?.signal_score ?? 0}
                  </span>
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {diagnostics?.signals?.length ? (
                    diagnostics.signals.map((signal) => (
                      <span
                        key={signal}
                        className="rounded bg-surface-muted px-2 py-0.5 font-mono text-xs text-text-muted"
                      >
                        {signal}
                      </span>
                    ))
                  ) : (
                    <span className="text-sm text-text-muted">
                      No extractable content detected — the pipeline stops at Stage 1.
                    </span>
                  )}
                </div>
                {diagnostics?.stopped_at ? (
                  <p className="text-xs text-text-muted">
                    Stopped at <span className="font-mono">{diagnostics.stopped_at}</span>
                  </p>
                ) : null}
                {diagnostics?.warnings?.length ? (
                  <ul className="space-y-1 text-xs text-warn">
                    {diagnostics.warnings.map((warning) => (
                      <li key={warning}>⚠ {warning}</li>
                    ))}
                  </ul>
                ) : null}
              </div>
            </Card>

            <Card title="Stage timings" description="Where the milliseconds went.">
              <div className="viz space-y-2.5 p-5">
                {timings && Object.keys(timings).length ? (
                  Object.entries(timings).map(([stage, ms]) => (
                    <div key={stage}>
                      <div className="mb-1 flex items-baseline justify-between gap-3 text-xs">
                        <span className="font-mono text-text">{stage}</span>
                        <span className="tabular-nums text-text-muted">{ms} ms</span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-muted">
                        <div
                          className="h-full rounded-full"
                          style={{
                            width: `${totalTime ? (ms / totalTime) * 100 : 0}%`,
                            background: "var(--series-1)",
                          }}
                        />
                      </div>
                    </div>
                  ))
                ) : (
                  <p className="text-sm text-text-muted">No timings returned.</p>
                )}
              </div>
            </Card>

            <Card
              title="Extracted slots"
              description="Stage 2 output after Stage 3 re-normalised every value."
            >
              <div className="p-5">
                {response.result.slots && Object.keys(response.result.slots).length ? (
                  <dl className="space-y-2 text-sm">
                    {Object.entries(response.result.slots).map(([name, value]) => (
                      <div key={name} className="flex flex-wrap items-baseline gap-2">
                        <dt className="font-mono text-xs text-text-muted">{name}</dt>
                        <dd className="min-w-0 flex-1 break-words text-text">
                          {typeof value === "object" && value !== null ? (
                            <span className="font-mono text-xs">{JSON.stringify(value)}</span>
                          ) : (
                            String(value)
                          )}
                        </dd>
                      </div>
                    ))}
                  </dl>
                ) : (
                  <p className="text-sm text-text-muted">
                    Nothing extracted — this was a navigation query.
                  </p>
                )}
              </div>
            </Card>
          </div>

          {response.result.suggestions?.length ? (
            <Card title="Suggestions" description="Returned when no single feature is certain enough.">
              <ul className="divide-y divide-border">
                {response.result.suggestions.map((suggestion) => (
                  <li
                    key={suggestion.feature_id}
                    className="flex flex-wrap items-baseline justify-between gap-3 px-5 py-2.5 text-sm"
                  >
                    <span>
                      <span className="text-text">{suggestion.display_name}</span>{" "}
                      <span className="font-mono text-xs text-text-faint">
                        {suggestion.feature_id}
                      </span>
                    </span>
                    <span className="tabular-nums text-text-muted">
                      {suggestion.confidence.toFixed(3)}
                    </span>
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}

          <details className="card px-5 py-4">
            <summary className="cursor-pointer select-none text-sm font-medium text-text">
              Raw response
            </summary>
            <pre className="scroll-x mt-3 rounded-lg bg-surface-muted p-3 font-mono text-xs leading-relaxed">
              {JSON.stringify(response.result, null, 2)}
            </pre>
          </details>
        </>
      ) : null}
    </div>
  );
}
