import { NextResponse } from "next/server";

import { SESSION_COOKIE, createSessionCookie, passwordMatches } from "@/lib/auth";
import { env } from "@/lib/env";
import { jsonError, readJsonObject } from "@/lib/api";

export const dynamic = "force-dynamic";

/**
 * Password exchange.
 *
 * One password means one thing worth brute-forcing, so attempts are counted
 * per client and refused past a threshold. The window is in-process: this is a
 * single-instance dashboard, and a shared store would be more machinery than
 * the threat deserves.
 */
const WINDOW_MS = 5 * 60_000;
const MAX_ATTEMPTS = 10;

declare global {
  // eslint-disable-next-line no-var
  var __loginAttempts: Map<string, { count: number; resetAt: number }> | undefined;
}

function attempts() {
  if (!globalThis.__loginAttempts) globalThis.__loginAttempts = new Map();
  return globalThis.__loginAttempts;
}

function throttle(key: string): boolean {
  const now = Date.now();
  const record = attempts().get(key);
  if (!record || record.resetAt < now) {
    attempts().set(key, { count: 1, resetAt: now + WINDOW_MS });
    return false;
  }
  record.count += 1;
  return record.count > MAX_ATTEMPTS;
}

export async function POST(request: Request) {
  const client =
    request.headers.get("x-forwarded-for")?.split(",")[0].trim() ??
    request.headers.get("x-real-ip") ??
    "local";

  if (throttle(client)) {
    return jsonError(429, "Too many attempts. Wait a few minutes and try again.");
  }

  const parsed = await readJsonObject(request);
  if (!parsed.ok) return parsed.response;

  const password = typeof parsed.body.password === "string" ? parsed.body.password : "";
  if (!passwordMatches(password, env.adminPassword())) {
    return jsonError(401, "Incorrect password.");
  }

  attempts().delete(client);

  const proto = request.headers.get("x-forwarded-proto") ?? new URL(request.url).protocol.replace(":", "");
  const secure = proto === "https";

  const response = NextResponse.json({ ok: true });
  response.cookies.set({
    name: SESSION_COOKIE,
    value: await createSessionCookie(env.sessionSecret(), env.sessionTtlHours()),
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
    maxAge: Math.round(env.sessionTtlHours() * 3600),
  });
  return response;
}
