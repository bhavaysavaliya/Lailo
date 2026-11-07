import { describe, expect, it } from "vitest";
import { safeNextPath } from "../safe-redirect";

describe("safeNextPath", () => {
  /**
   * Same-site paths are kept as post-login targets.
   * - Given /orders?page=2
   * - When safeNextPath runs
   * - Then the path is returned unchanged
   */
  it("keeps same-site paths", () => {
    expect(safeNextPath("/orders?page=2")).toBe("/orders?page=2");
  });

  /**
   * Unsafe or pointless targets fall back to /account.
   * - Given null, empty, an absolute URL, protocol-relative and backslash tricks, or /login itself
   * - When safeNextPath runs
   * - Then /account is returned
   */
  it.each([null, "", "https://evil.example", "//evil.example", "/\\evil.example", "/login?next=/x"])(
    "falls back for %j",
    (next) => {
      expect(safeNextPath(next)).toBe("/account");
    },
  );
});
