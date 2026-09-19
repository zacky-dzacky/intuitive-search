/**
 * Environment, read once and validated loudly.
 *
 * Nothing here is bundled to the client — every consumer is a server
 * component, a route handler, or the middleware.
 */

function required(name: string, fallback?: string): string {
  const value = process.env[name] ?? fallback;
  if (!value) {
    throw new Error(
      `Missing required environment variable ${name}. See .env.example.`,
    );
  }
  return value;
}

/**
 * The placeholder values shipped in .env.example and docker-compose.
 *
 * They exist so the stack starts on a laptop. Running with them in production
 * would mean a dashboard with a published password, so that combination is
 * refused outright rather than warned about — a warning in a log is not a
 * control.
 */
const PLACEHOLDER_VALUES = new Set([
  "change-me",
  "admin",
  "password",
  "dev-only-secret-change-me-to-32-plus-random-bytes",
  "dev-only-change-me",
]);

function rejectPlaceholder(name: string, value: string, minLength: number): string {
  if (process.env.NODE_ENV !== "production") return value;
  if (PLACEHOLDER_VALUES.has(value) || value.length < minLength) {
    throw new Error(
      `${name} is still a placeholder (or shorter than ${minLength} characters). ` +
        "Set a real value before running in production — `openssl rand -hex 32`.",
    );
  }
  return value;
}

export const env = {
  databaseUrl: () => required("DATABASE_URL"),
  backendBaseUrl: () =>
    (process.env.BACKEND_BASE_URL ?? "http://localhost:8080").replace(/\/$/, ""),
  embeddingBaseUrl: () =>
    (process.env.EMBEDDING_BASE_URL ?? "http://localhost:8000").replace(/\/$/, ""),
  adminPassword: () => rejectPlaceholder("ADMIN_PASSWORD", required("ADMIN_PASSWORD"), 10),
  sessionSecret: () => rejectPlaceholder("SESSION_SECRET", required("SESSION_SECRET"), 24),
  sessionTtlHours: () => Number(process.env.SESSION_TTL_HOURS ?? "12"),
  isProduction: () => process.env.NODE_ENV === "production",
};

/** 384 — must match `features.embedding VECTOR(384)` and the model. */
export const EMBEDDING_DIMENSIONS = Number(process.env.EMBEDDING_DIMS ?? "384");
