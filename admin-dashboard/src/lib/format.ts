/** Display helpers shared by server and client components. */

export function formatDateTime(value: unknown): string {
  if (!value) return "—";
  const date = value instanceof Date ? value : new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatRelative(value: unknown): string {
  if (!value) return "never";
  const date = value instanceof Date ? value : new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);

  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  const units: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, "second"],
    [3600, "minute"],
    [86400, "hour"],
    [2592000, "day"],
    [31536000, "month"],
  ];
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

  if (seconds < 60) return formatter.format(-seconds, "second");
  for (let i = 1; i < units.length; i += 1) {
    if (seconds < units[i][0]) {
      return formatter.format(-Math.round(seconds / units[i - 1][0]), units[i][1]);
    }
  }
  return formatter.format(-Math.round(seconds / 31536000), "year");
}

export function formatNumber(value: number | null | undefined, digits = 0): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return value.toLocaleString(undefined, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function formatPercent(value: number | null | undefined, digits = 0): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return `${(value * 100).toFixed(digits)}%`;
}

export function truncate(value: string, max = 80): string {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}
