import { HealthPill } from "@/components/HealthPill";
import { LogoutButton } from "@/components/LogoutButton";
import { type NavSection, Sidebar } from "@/components/Sidebar";
import { staleCount } from "@/lib/embeddings";
import { RESOURCES } from "@/lib/resources/registry";
import { can } from "@/lib/resources/types";

export const dynamic = "force-dynamic";

/**
 * The authenticated shell.
 *
 * Navigation is derived, not written: everything under "Registry" and "Logs"
 * comes from the resource registry, split by whether the resource can be
 * written to. Adding a resource adds its nav entry.
 */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const stale = await staleCount();

  const toItem = (resource: (typeof RESOURCES)[number]) => ({
    href: `/resources/${resource.name}`,
    label: resource.labelPlural,
    icon: resource.icon,
  });

  const sections: NavSection[] = [
    {
      title: "Operate",
      items: [
        { href: "/", label: "Overview", icon: "◎" },
        { href: "/playground", label: "Search playground", icon: "🔎" },
        {
          href: "/embeddings",
          label: "Embeddings",
          icon: "🧬",
          badge: stale > 0 ? String(stale) : null,
        },
        { href: "/analytics", label: "Analytics", icon: "📈" },
      ],
    },
    {
      title: "Registry",
      items: RESOURCES.filter((resource) => can(resource, "create")).map(toItem),
    },
    {
      title: "Logs",
      items: RESOURCES.filter((resource) => !can(resource, "create")).map(toItem),
    },
  ];

  return (
    <div className="min-h-screen md:pl-60">
      <Sidebar sections={sections} />
      <div className="flex min-h-screen flex-col">
        <header className="sticky top-0 z-10 flex h-14 items-center justify-end gap-4 border-b border-border bg-canvas/85 px-4 backdrop-blur md:px-8">
          <HealthPill />
          <LogoutButton />
        </header>
        <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-7 md:px-8">{children}</main>
      </div>
    </div>
  );
}
