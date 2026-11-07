import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, refreshSession, request } from "../api/client";

/**
 * Builds a JSON response.
 *
 * @param status HTTP status
 * @param body value to serialise
 * @returns the response
 */
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

afterEach(() => vi.unstubAllGlobals());

describe("request", () => {
  /**
   * Problem responses become ApiError with the server's code and details.
   * - Given fetch answers 401 with code OTP_INVALID and attemptsLeft 2
   * - When a request is made
   * - Then it rejects with an ApiError carrying status, code, message and attemptsLeft
   */
  it("maps RFC 9457 problems to ApiError with code and details", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      json(401, { code: "OTP_INVALID", detail: "Incorrect code. 2 attempts left.", attemptsLeft: 2 }),
    ));

    const err = (await request("/api/v1/auth/otp/verify", { method: "POST", body: "{}" }).catch((e: unknown) => e)) as ApiError;

    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 401, code: "OTP_INVALID", message: "Incorrect code. 2 attempts left." });
    expect(err.details.attemptsLeft).toBe(2);
  });

  /**
   * Network failures get a friendly error.
   * - Given fetch rejects with a TypeError
   * - When a request is made
   * - Then it rejects with code NETWORK_ERROR
   */
  it("reports network failures with a friendly message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(request("/api/v1/users/me")).rejects.toMatchObject({ code: "NETWORK_ERROR" });
  });

  /**
   * A 401 on a protected call refreshes once and retries.
   * - Given fetch answers 401, then the refresh succeeds, then the retry succeeds
   * - When /api/v1/users/me is requested
   * - Then the profile is returned after exactly three calls: me, refresh, me
   */
  it("refreshes once on 401 and retries protected calls", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))       // /users/me
      .mockResolvedValueOnce(json(200, { userId: "u" }))                  // /auth/refresh
      .mockResolvedValueOnce(json(200, { id: "u" }));                     // retried /users/me
    vi.stubGlobal("fetch", fetchMock);

    await expect(request("/api/v1/users/me")).resolves.toEqual({ id: "u" });
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/v1/users/me",
      "/api/v1/auth/refresh",
      "/api/v1/users/me",
    ]);
  });

  /**
   * Concurrent callers share one refresh request.
   * - Given a successful refresh endpoint
   * - When refreshSession is called three times at once
   * - Then only one refresh request is sent
   */
  it("shares one refresh request between concurrent callers", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, {}));
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([refreshSession(), refreshSession(), refreshSession()]);

    expect(fetchMock.mock.calls.filter((c) => c[0] === "/api/v1/auth/refresh")).toHaveLength(1);
  });
});
