/**
 * Single-admin session.
 *
 * One password in the environment, exchanged for an HMAC-signed cookie that
 * carries nothing but an expiry. There is no user table and no server-side
 * session store, so there is nothing to keep in sync — and swapping this for
 * SSO later means replacing exactly one function pair (`createSession` /
 * `verifySession`) plus the login route.
 *
 * Everything here uses Web Crypto rather than `node:crypto`, so the same code
 * runs in the Next middleware (edge runtime) and in route handlers.
 */

export const SESSION_COOKIE = "is_admin_session";

const encoder = new TextEncoder();

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlDecode(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function hmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

async function sign(payload: string, secret: string): Promise<string> {
  const signature = await crypto.subtle.sign(
    "HMAC",
    await hmacKey(secret),
    encoder.encode(payload),
  );
  return base64UrlEncode(new Uint8Array(signature));
}

/** Length-independent comparison, so a mismatch leaks no position. */
function constantTimeEquals(a: string, b: string): boolean {
  const left = encoder.encode(a);
  const right = encoder.encode(b);
  let diff = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i += 1) {
    diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  }
  return diff === 0;
}

export interface SessionPayload {
  /** Unix seconds. */
  exp: number;
  /** Issued-at, purely informational (shown in the UI). */
  iat: number;
}

export async function createSessionCookie(
  secret: string,
  ttlHours: number,
): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const payload: SessionPayload = {
    iat: now,
    exp: now + Math.round(ttlHours * 3600),
  };
  const body = base64UrlEncode(encoder.encode(JSON.stringify(payload)));
  return `${body}.${await sign(body, secret)}`;
}

export async function verifySessionCookie(
  cookie: string | undefined,
  secret: string,
): Promise<SessionPayload | null> {
  if (!cookie) return null;
  const [body, signature] = cookie.split(".");
  if (!body || !signature) return null;

  const expected = await sign(body, secret);
  if (!constantTimeEquals(signature, expected)) return null;

  try {
    const payload = JSON.parse(
      new TextDecoder().decode(base64UrlDecode(body)),
    ) as SessionPayload;
    if (typeof payload.exp !== "number") return null;
    if (payload.exp * 1000 < Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}

/**
 * Password check. Constant-time so the endpoint can't be used as an oracle,
 * and rejects an unset/blank password outright rather than letting `""` in.
 */
export function passwordMatches(input: string, expected: string): boolean {
  if (!expected || expected.length < 1) return false;
  return constantTimeEquals(input, expected);
}
