/**
 * Browser-side fetch wrapper for the Lailo API.
 * - Same-origin requests (Next.js rewrites /api to Spring Boot), so HttpOnly auth cookies flow automatically.
 * - Echoes the XSRF-TOKEN cookie in X-XSRF-TOKEN on state-changing calls (double-submit CSRF).
 * - Turns RFC 9457 problem responses into ApiError with a stable `code`.
 * - On 401 from a protected endpoint, refreshes the session once (single-flight) and retries.
 */
export class ApiError extends Error {
  /**
   * Creates an API error.
   *
   * @param status HTTP status (0 for network failures)
   * @param code stable error code from the server, e.g. `OTP_INVALID`
   * @param message user-facing message
   * @param details extra problem properties, e.g. `attemptsLeft` or `errors`
   */
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }

  /**
   * Field-level validation messages from a `VALIDATION_FAILED` response.
   *
   * @returns field name to message; empty for other errors
   */
  get fieldErrors(): Record<string, string> {
    return (this.details.errors as Record<string, string> | undefined) ?? {};
  }
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const AUTH_PREFIX = "/api/v1/auth/";

/**
 * Reads a cookie visible to JavaScript (only non-HttpOnly cookies such as `XSRF-TOKEN`).
 *
 * @param name cookie name
 * @returns the raw value, or undefined on the server or when absent
 */
function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  return document.cookie
    .split("; ")
    .find((row) => row.startsWith(`${name}=`))
    ?.split("=")[1];
}

/**
 * Returns the CSRF token, fetching `/api/v1/auth/csrf` first if the cookie isn't set yet.
 *
 * @returns the token, or undefined during server rendering
 */
async function ensureCsrfToken(): Promise<string | undefined> {
  if (typeof document === "undefined") return undefined; // server-side callers use bearer tokens, not cookies
  let token = readCookie("XSRF-TOKEN");
  if (!token) {
    // the backend attaches the cookie to any response; this endpoint exists just for that
    await fetch("/api/v1/auth/csrf", { credentials: "same-origin", cache: "no-store" }).catch(() => undefined);
    token = readCookie("XSRF-TOKEN");
  }
  return token ? decodeURIComponent(token) : undefined;
}

/**
 * Turns a failed response into an {@link ApiError}, using the problem body when there is one.
 *
 * @param res the failed response
 * @returns the error with code, message and details
 */
async function toApiError(res: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {};
  try {
    body = await res.json();
  } catch {
    /* empty or non-JSON body */
  }
  const { code, detail, ...rest } = body as { code?: string; detail?: string };
  const fallback =
    res.status === 403
      ? "Your session expired. Refresh the page and try again."
      : res.status >= 500
        ? "Something went wrong on our side. Try again in a moment."
        : "Request failed.";
  return new ApiError(res.status, code ?? `HTTP_${res.status}`, detail ?? fallback, rest);
}

let refreshInFlight: Promise<boolean> | null = null;

/** Rotates the refresh token. Concurrent callers share one request so the token is never presented twice. */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= request("/api/v1/auth/refresh", { method: "POST" }, false)
    .then(() => true)
    .catch(() => false)
    .finally(() => {
      refreshInFlight = null;
    });
  return refreshInFlight;
}

/**
 * Same-origin JSON request to the Lailo API.
 *
 * Adds the CSRF header to state-changing calls, maps problem responses to {@link ApiError}, and on a 401 from a
 * protected endpoint refreshes the session once and retries.
 *
 * @param path API path starting with `/api/`
 * @param init fetch options
 * @param retryOn401 whether a 401 may trigger one refresh-and-retry
 * @returns the parsed JSON body, or undefined for 204
 * @throws ApiError for non-2xx responses and network failures
 */
export async function request<T>(path: string, init: RequestInit = {}, retryOn401 = true): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (!SAFE_METHODS.has(method)) {
    const csrf = await ensureCsrfToken();
    if (csrf) headers.set("X-XSRF-TOKEN", csrf);
  }

  let res: Response;
  try {
    res = await fetch(path, { ...init, method, headers, credentials: "same-origin", cache: "no-store" });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "Can't reach Lailo right now. Check your connection and try again.");
  }

  if (res.status === 401 && retryOn401 && !path.startsWith(AUTH_PREFIX) && (await refreshSession())) {
    return request<T>(path, init, false);
  }
  if (!res.ok) throw await toApiError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
