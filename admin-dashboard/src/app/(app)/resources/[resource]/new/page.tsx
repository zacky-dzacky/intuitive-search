import { notFound } from "next/navigation";

import { ResourceForm } from "@/components/ResourceForm";
import { PageHeader } from "@/components/ui";
import { getResource } from "@/lib/resources/registry";
import { dynamicOptions } from "@/lib/resources/repository";
import { can } from "@/lib/resources/types";

export const dynamic = "force-dynamic";

export default async function NewRecordPage({
  params,
}: {
  params: Promise<{ resource: string }>;
}) {
  const { resource: name } = await params;
  const resource = getResource(name);
  if (!resource || !can(resource, "create")) notFound();

  const options = await dynamicOptions(resource);

  return (
    <>
      <PageHeader
        title={`New ${resource.label.toLowerCase()}`}
        subtitle={resource.description}
      />
      <ResourceForm resourceName={resource.name} options={options} />
    </>
  );
}
