import { notFound } from "next/navigation";

import { RecordDetail } from "@/components/RecordDetail";
import { ResourceForm } from "@/components/ResourceForm";
import { LinkButton, PageHeader } from "@/components/ui";
import { getResource } from "@/lib/resources/registry";
import { dynamicOptions, getRow } from "@/lib/resources/repository";
import { can } from "@/lib/resources/types";

export const dynamic = "force-dynamic";

/**
 * Record page: a form when the resource is writable, a read-only detail view
 * when it is not. Which one is a property of the registry entry, not of this
 * file.
 */
export default async function RecordPage({
  params,
}: {
  params: Promise<{ resource: string; id: string }>;
}) {
  const { resource: name, id } = await params;
  const resource = getResource(name);
  if (!resource) notFound();

  const record = await getRow(resource, decodeURIComponent(id));
  if (!record) notFound();

  const title = String(record[resource.titleField] ?? record[resource.primaryKey]);
  const writable = can(resource, "update");
  const options = writable ? await dynamicOptions(resource) : {};

  return (
    <>
      <PageHeader
        title={title}
        subtitle={
          <span className="font-mono text-xs">
            {resource.primaryKey}: {String(record[resource.primaryKey])}
          </span>
        }
        actions={
          <LinkButton href={`/resources/${resource.name}`}>Back to {resource.labelPlural.toLowerCase()}</LinkButton>
        }
      />

      {writable ? (
        <ResourceForm resourceName={resource.name} record={record} options={options} />
      ) : (
        <RecordDetail resource={resource} record={record} />
      )}
    </>
  );
}
