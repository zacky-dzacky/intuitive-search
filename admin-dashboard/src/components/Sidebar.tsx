"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

import { buttonClass } from "./ui";

export interface NavItem {
  href: string;
  label: string;
  icon: string;
  /** Shown on the right of the row: a count, or a warning marker. */
  badge?: string | null;
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

/**
 * Navigation.
 *
 * The "Registry" section is generated from the resource registry, so a new
 * `ResourceDef` appears here without anyone editing this file.
 */
export function Sidebar({ sections }: { sections: NavSection[] }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);

  const isActive = (href: string) =>
    href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className={buttonClass("secondary", "fixed left-4 top-3.5 z-30 md:hidden")}
        aria-expanded={open}
        aria-label="Toggle navigation"
      >
        ☰
      </button>

      <nav
        className={`fixed inset-y-0 left-0 z-20 w-60 shrink-0 overflow-y-auto border-r border-border bg-surface px-3 py-4 transition-transform md:translate-x-0 ${
          open ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <Link href="/" className="mb-6 block px-2 pt-9 md:pt-0" onClick={() => setOpen(false)}>
          <span className="block text-sm font-semibold tracking-tight text-text">
            Intuitive Search
          </span>
          <span className="block text-xs text-text-faint">admin</span>
        </Link>

        {sections.map((section) => (
          <div key={section.title} className="mb-5">
            <p className="mb-1.5 px-2 text-[0.68rem] font-semibold uppercase tracking-wider text-text-faint">
              {section.title}
            </p>
            <ul className="space-y-0.5">
              {section.items.map((item) => (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    onClick={() => setOpen(false)}
                    className={`flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm transition-colors ${
                      isActive(item.href)
                        ? "bg-accent-soft font-medium text-accent"
                        : "text-text-muted hover:bg-surface-muted hover:text-text"
                    }`}
                  >
                    <span aria-hidden className="text-base leading-none">
                      {item.icon}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{item.label}</span>
                    {item.badge ? (
                      <span className="rounded-full bg-warn-soft px-1.5 py-0.5 text-[0.65rem] font-semibold tabular-nums text-warn">
                        {item.badge}
                      </span>
                    ) : null}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      {open ? (
        <button
          type="button"
          aria-label="Close navigation"
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-10 bg-black/30 md:hidden"
        />
      ) : null}
    </>
  );
}
