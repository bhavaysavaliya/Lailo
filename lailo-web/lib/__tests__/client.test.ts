import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, refreshSession, request } from "../api/client";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

afterEach(() => vi.unstubAllGlobals());

describe("request", () => {
  it("maps RFC 9457 problems to ApiError with code and details", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      json(401, { code: "OTP_INVALID", detail: "Incorrect code. 2 attempts left.", attemptsLeft: 2 }),
    ));

    const err = (await request("/api/v1/auth/otp/verify", { method: "POST", body: "{}" }).catch((e: unknown) => e)) as ApiError;

    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 401, code: "OTP_INVALID", message: "Incorrect code. 2 attempts left." });
    expect(err.details.attemptsLeft).toBe(2);
  });

  it("reports network failures with a friendly message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(request("/api/v1/users/me")).rejects.toMatchObject({ code: "NETWORK_ERROR" });
  });

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

  it("shares one refresh request between concurrent callers", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, {}));
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([refreshSession(), refreshSession(), refreshSession()]);

    expect(fetchMock.mock.calls.filter((c) => c[0] === "/api/v1/auth/refresh")).toHaveLength(1);
  });
});
