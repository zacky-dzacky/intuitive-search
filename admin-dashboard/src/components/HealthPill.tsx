"use client";

import { useEffect, useState } from "react";

interface Service {
  name: string;
  target: string;
  ok: boolean;
  detail: string;
}

/**
 * Liveness of Postgres, the search API and the embedding service.
 *
 * Polled from the client rather than rendered server-side, so three network
 * probes never sit in front of a page render — and so a service coming back up
 * shows without a reload.
 */
export function HealthPill() {
  const [services, setServices] = useState<Service[] | null>(null);

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const response = await fetch("/api/health", { cache: "no-store" });
        if (!response.ok) return;
        const body = (await response.json()) as { services: Service[] };
        if (!cancelled) setServices(body.services);
      } catch {
        // Leave the last known state on screen rather than flickering.
      }
    };

    void load();
    const timer = setInterval(load, 30_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  if (!services) {
    return <span className="text-xs text-text-faint">checking services…</span>;
  }

  const down = services.filter((service) => !service.ok);

  return (
    <div
      className="flex items-center gap-3"
      title={services.map((service) => `${service.name}: ${service.detail}`).join("\n")}
    >
      {services.map((service) => (
        <span key={service.name} className="flex items-center gap-1.5 text-xs text-text-muted">
          <span
            aria-hidden
            className={`inline-block h-1.5 w-1.5 rounded-full ${
              service.ok ? "bg-ok" : "bg-danger"
            }`}
          />
          <span className="hidden sm:inline">{service.name}</span>
        </span>
      ))}
      <span className="sr-only">
        {down.length ? `${down.map((s) => s.name).join(", ")} unreachable` : "all services reachable"}
      </span>
    </div>
  );
}
