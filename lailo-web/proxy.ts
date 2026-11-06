import { NextResponse, type NextRequest } from "next/server";

const ACCESS_COOKIE = "lailo_at";
const PROTECTED = ["/account"];

/**
 * Optimistic route guard - only checks cookie presence (no crypto at the edge).
 * Real authorization happens in Spring Boot on every API call.
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hasAccess = request.cookies.has(ACCESS_COOKIE);

  if (PROTECTED.some((p) => pathname === p || pathname.startsWith(`${p}/`)) && !hasAccess) {
    // The refresh cookie is scoped to /api/v1/auth, so it isn't visible here; /login attempts a silent refresh.
    const url = new URL("/login", request.url);
    url.searchParams.set("next", pathname + search);
    return NextResponse.redirect(url);
  }

  if (pathname === "/login" && hasAccess) {
    return NextResponse.redirect(new URL("/account", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/account/:path*", "/login"],
};
