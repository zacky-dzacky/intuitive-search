import Link from "next/link";

import { ReembedControls } from "@/components/ReembedControls";
import { Badge, Card, EmptyState, Note, PageHeader, StatTile } from "@/components/ui";
import { embeddingStatus, staleFeatures } from "@/lib/embeddings";
import { formatDateTime, formatRelative } from "@/lib/format";

export const dynamic = "force-dynamic";

/**
 * Vector coverage of the registry.
 *
 * A feature with no embedding is still findable — the lexical channels carry
 * it — but it has lost the channel that finds phrasings nobody listed, which
 * is precisely the reason the vector channel exists. This page is here so that
 * loss is visible instead of silent.
 */
export default async function EmbeddingsPage() {
  const [status, stale] = await Promise.all([embeddingStatus(), staleFeatures()]);

  const dimensionMismatch =
    status.serviceDimensions !== null && status.serviceDimensions !== status.dimensions;

  return (
    <>
      <PageHeader
        title="Embeddings"
        subtitle="Which features have a current vector, and the button that fixes the ones that do not."
        actions={<ReembedControls staleCount={status.stale} compact />}
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Features" value={status.total} hint="Rows in the registry" />
        <StatTile
          label="With a vector"
          value={status.embedded}
          tone={status.missing > 0 ? "warn" : "ok"}
          hint={status.missing > 0 ? `${status.missing} have never been embedded` : "Full coverage"}
        />
        <StatTile
          label="Stale"
          value={status.stale}
          tone={status.stale > 0 ? "warn" : "ok"}
          hint="Text edited since the vector was written"
        />
        <StatTile
          label="Last embedded"
          value={
            <span className="text-lg">{status.lastEmbeddedAt ? formatRelative(status.lastEmbeddedAt) : "never"}</span>
          }
          hint={status.lastEmbeddedAt ? formatDateTime(status.lastEmbeddedAt) : undefined}
        />
      </div>

      {!status.serviceReachable ? (
        <p className="mb-5 rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger">
          The embedding service is unreachable ({status.serviceError}). Search still works — the
          vector channel is dropped and the remaining weights are renormalised — but nothing can be
          embedded until it is back.
        </p>
      ) : dimensionMismatch ? (
        <p className="mb-5 rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger">
          The service reports {status.serviceDimensions} dimensions but{" "}
          <code className="font-mono">features.embedding</code> is VECTOR({status.dimensions}).
          Re-embedding is refused until the model and the column agree.
        </p>
      ) : null}

      <Card
        title="Needs embedding"
        description="A row is listed when it has no vector, or when its text has changed since the vector was written."
        className="mb-5"
      >
        {stale.length === 0 ? (
          <EmptyState title="Every feature has a current vector.">
            Edit a feature's name, keywords, aliases or description and it will appear here.
          </EmptyState>
        ) : (
          <div className="scroll-x">
            <table className="w-full min-w-2xl text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-text-faint">
                  <th className="px-5 py-2.5 font-semibold">Feature</th>
                  <th className="px-5 py-2.5 font-semibold">Category</th>
                  <th className="px-5 py-2.5 font-semibold">State</th>
                  <th className="px-5 py-2.5 font-semibold">Edited</th>
                  <th className="px-5 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {stale.map((feature) => (
                  <tr key={feature.feature_id} className="border-b border-border last:border-0">
                    <td className="px-5 py-2.5">
                      <Link
                        href={`/resources/features/${encodeURIComponent(feature.feature_id)}`}
                        className="font-medium text-text hover:text-accent"
                      >
                        {feature.display_name}
                      </Link>
                      <span className="ml-2 font-mono text-xs text-text-faint">
                        {feature.feature_id}
                      </span>
                    </td>
                    <td className="px-5 py-2.5">
                      <Badge>{feature.category}</Badge>
                    </td>
                    <td className="px-5 py-2.5">
                      {feature.has_embedding ? (
                        <Badge tone="warn">text changed</Badge>
                      ) : (
                        <Badge tone="danger">no vector</Badge>
                      )}
                      {!feature.enabled ? (
                        <span className="ml-2">
                          <Badge tone="neutral">disabled</Badge>
                        </span>
                      ) : null}
                    </td>
                    <td className="px-5 py-2.5 text-text-muted" title={formatDateTime(feature.updated_at)}>
                      {formatRelative(feature.updated_at)}
                    </td>
                    <td className="px-5 py-2.5 text-right">
                      <ReembedControls featureId={feature.feature_id} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="space-y-3">
        <Note>
          Editing a feature does not drop its old vector. A slightly out-of-date vector still
          retrieves; no vector at all silently removes the channel that catches phrasings nobody
          listed. So the old one keeps working and the row is flagged here instead.
        </Note>
        <Note>
          Documents are sent with <code className="font-mono">is_query: false</code>. bge is
          asymmetric — embedding a document with the query prefix retrieves measurably worse and
          fails nothing loudly — so this page produces byte-identical vectors to{" "}
          <code className="font-mono">embedding-service/precompute_embeddings.py</code>, and either
          tool can be used.
          {status.serviceModel ? (
            <>
              {" "}
              Model in use: <code className="font-mono">{status.serviceModel}</code>.
            </>
          ) : null}
        </Note>
        <Note>
          Adding many features at once? The HNSW index in{" "}
          <code className="font-mono">db/03_indexes.sql</code> keeps working as rows are inserted —
          it needs no retraining, unlike ivfflat, which is why the project switched.
        </Note>
      </div>
    </>
  );
}
