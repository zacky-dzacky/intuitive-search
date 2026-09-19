import { Playground } from "@/components/Playground";
import { PageHeader } from "@/components/ui";

export const dynamic = "force-dynamic";

export default function PlaygroundPage() {
  return (
    <>
      <PageHeader
        title="Search playground"
        subtitle="Run a query against the live API and read the diagnostics: which channel matched, whether the LLM gate opened, and where the milliseconds went."
      />
      <Playground />
    </>
  );
}
