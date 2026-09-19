import { ReembedControls } from "@/components/ReembedControls";
import { Note, PageHeader, StatTile } from "@/components/ui";
import { indexStatus } from "@/lib/embeddings";
import { formatDateTime, formatRelative } from "@/lib/format";

export const dynamic = "force-dynamic";

/**
 * The search index, as the backend sees it.
 *
 * A feature with no vector is still findable — the lexical channels carry
 * it — but it has lost the channel that finds phrasings nobody listed, which
 * is precisely the reason the vector channel exists. This page is here so
 * that loss is visible instead of silent.
 */
export default async function SearchIndexPage() {
  const status = await indexStatus();
  const missing = status.features - status.withVector;

  return (
    <>
      <PageHeader
        title="Search index"
        subtitle="Built in-process by the search API from the feature registry. It keeps itself current; this page shows what it holds."
        actions={<ReembedControls compact />}
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Features indexed" value={status.features} hint="Enabled rows in the registry" />
        <StatTile
          label="With a vector"
          value={status.withVector}
          tone={missing > 0 ? "warn" : "ok"}
          hint={missing > 0 ? `${missing} waiting on the embedding provider` : "Full coverage"}
        />
        <StatTile
          label="Embedding model"
          value={<span className="text-base">{status.embeddingModel ?? "—"}</span>}
          tone={status.embeddingDegraded ? "warn" : undefined}
          hint={
            status.dimensions
              ? `${status.dimensions} dimensions${status.embeddingDegraded ? " — currently degraded" : ""}`
              : undefined
          }
        />
        <StatTile
          label="Last built"
          value={
            <span className="text-lg">{status.builtAt ? formatRelative(status.builtAt) : "never"}</span>
          }
          hint={
            status.builtAt
              ? `${formatDateTime(status.builtAt)} · ${status.embeddedInBuild ?? 0} embedded in ${status.buildMs ?? 0}ms`
              : undefined
          }
        />
      </div>

      {!status.reachable ? (
        <p className="mb-5 rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger">
          The search API is unreachable ({status.error}). The index lives inside it, so there is
          nothing to show until it is back.
        </p>
      ) : !status.embeddingEnabled ? (
        <p className="mb-5 rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger">
          The embedding provider is not configured ({status.embeddingModel}). Search runs
          lexical-only — the vector weight is dropped and the rest are renormalised — until
          <code className="font-mono"> OPENAI_BASE_URL</code>,
          <code className="font-mono"> OPENAI_API_KEY</code> and
          <code className="font-mono"> OPENAI_EMBEDDING_MODEL</code> are set on the backend.
        </p>
      ) : status.lastError ? (
        <p className="mb-5 rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger">
          The last build could not embed every feature: {status.lastError}. The index was built
          with the vectors it had and the backend retries on its next refresh.
        </p>
      ) : null}

      <div className="space-y-3">
        <Note>
          Editing a feature re-embeds that one feature on the backend&apos;s next registry refresh
          (within 60 seconds), or immediately with <em>Rebuild index</em>. Every other vector is
          carried over — an edit costs one embedding call, not sixty-seven.
        </Note>
        <Note>
          <em>Re-embed all</em> is for after the embedding model or its dimensions change. It
          sends the whole catalogue to the provider in one batch and swaps the new index in
          atomically; searches in flight finish on the old one.
        </Note>
        <Note>
          The index is a cache, not a store. Postgres holds the registry; nothing in the index
          survives a restart and nothing needs to — a cold start rebuilds it in one embedding call.
        </Note>
      </div>
    </>
  );
}
