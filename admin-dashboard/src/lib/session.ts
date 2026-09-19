import "server-only";

import { cookies } from "next/headers";

import { SESSION_COOKIE, verifySessionCookie } from "./auth";
import { env } from "./env";

/**
 * Session for server components and route handlers.
 *
 * The middleware already blocks unauthenticated requests; this is the second
 * check, at the point where data is actually read or written. Auth that lives
 * only in a matcher pattern is one `matcher` typo away from being absent.
 */
export async function getSession() {
  const store = await cookies();
  return verifySessionCookie(store.get(SESSION_COOKIE)?.value, env.sessionSecret());
}

export async function isAuthenticated(): Promise<boolean> {
  return (await getSession()) !== null;
}
