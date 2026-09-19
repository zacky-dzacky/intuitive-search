import Link from "next/link";
import type { ReactNode } from "react";

/** Small presentational primitives, shared by every page. */

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight text-text">{title}</h1>
        {subtitle ? (
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-text-muted">{subtitle}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap gap-2">{actions}</div> : null}
    </header>
  );
}

export function Card({
  title,
  description,
  actions,
  children,
  className = "",
}: {
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      {title ? (
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-text">{title}</h2>
            {description ? (
              <p className="mt-0.5 text-xs leading-relaxed text-text-muted">{description}</p>
            ) : null}
          </div>
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  );
}

type Tone = "neutral" | "accent" | "ok" | "warn" | "danger";

const TONES: Record<Tone, string> = {
  neutral: "bg-surface-muted text-text-muted border-border",
  accent: "bg-accent-soft text-accent border-transparent",
  ok: "bg-ok-soft text-ok border-transparent",
  warn: "bg-warn-soft text-warn border-transparent",
  danger: "bg-danger-soft text-danger border-transparent",
};

export function Badge({
  children,
  tone = "neutral",
  title,
}: {
  children: ReactNode;
  tone?: Tone;
  title?: string;
}) {
  return (
    <span
      title={title}
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-medium ${TONES[tone]}`}
    >
      {children}
    </span>
  );
}

const BUTTON_VARIANTS = {
  primary: "bg-accent text-accent-text hover:bg-accent-hover border-transparent",
  secondary: "bg-surface text-text hover:bg-surface-muted border-border-strong",
  danger: "bg-danger-soft text-danger hover:brightness-95 border-transparent",
  ghost: "bg-transparent text-text-muted hover:bg-surface-muted border-transparent",
} as const;

export type ButtonVariant = keyof typeof BUTTON_VARIANTS;

export function buttonClass(variant: ButtonVariant = "secondary", extra = ""): string {
  return `inline-flex items-center justify-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${BUTTON_VARIANTS[variant]} ${extra}`;
}

export function LinkButton({
  href,
  children,
  variant = "secondary",
}: {
  href: string;
  children: ReactNode;
  variant?: ButtonVariant;
}) {
  return (
    <Link href={href} className={buttonClass(variant)}>
      {children}
    </Link>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-14 text-center">
      <p className="text-sm font-semibold text-text">{title}</p>
      {children ? (
        <div className="max-w-md text-sm leading-relaxed text-text-muted">{children}</div>
      ) : null}
      {action}
    </div>
  );
}

export function StatTile({
  label,
  value,
  hint,
  tone = "neutral",
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: Tone;
}) {
  const valueTone =
    tone === "ok"
      ? "text-ok"
      : tone === "warn"
        ? "text-warn"
        : tone === "danger"
          ? "text-danger"
          : "text-text";
  return (
    <div className="card px-4 py-3.5">
      <p className="text-xs font-medium uppercase tracking-wide text-text-faint">{label}</p>
      <p className={`mt-1.5 text-2xl font-semibold tabular-nums ${valueTone}`}>{value}</p>
      {hint ? <p className="mt-1 text-xs leading-snug text-text-muted">{hint}</p> : null}
    </div>
  );
}

/** A short block of explanation attached to a page or a form. */
export function Note({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-border bg-surface-muted px-4 py-3 text-xs leading-relaxed text-text-muted">
      {children}
    </p>
  );
}
