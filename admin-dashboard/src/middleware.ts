import { type NextRequest, NextResponse } from "next/server";

import { SESSION_COOKIE, verifySessionCookie } from "@/lib/auth";

/**
 * The front door.
 *
 * Everything except the login page and the login endpoint requires a valid
 * session. Fails closed: if SESSION_SECRET is missing the middleware refuses
 * every request rather than waving them through, because a dashboard that
 * silently loses its authentication when an env var is unset is worse than one
 * that stops working.
 */
const PUBLIC_PATHS = new Set(["/login", "/api/auth/login", "/api/health"]);

export async function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const secret = process.env.SESSION_SECRET;

  if (!secret) {
    console.error("[auth] SESSION_SECRET is not set — refusing all requests.");
    return NextResponse.json(
      { error: "Server misconfigured: SESSION_SECRET is not set." },
      { status: 500 },
    );
  }

  const session = await verifySessionCookie(request.cookies.get(SESSION_COOKIE)?.value, secret);

  if (session) {
    // Already signed in: the login page has nothing to offer.
    if (pathname === "/login") {
      return NextResponse.redirect(new URL("/", request.url));
    }
    return NextResponse.next();
  }

  if (PUBLIC_PATHS.has(pathname)) return NextResponse.next();

  // API callers get a status they can act on; humans get the login page.
  if (pathname.startsWith("/api/")) {
    return NextResponse.json({ error: "Not signed in." }, { status: 401 });
  }

  const login = new URL("/login", request.url);
  if (pathname !== "/") login.searchParams.set("next", `${pathname}${search}`);
  return NextResponse.redirect(login);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
