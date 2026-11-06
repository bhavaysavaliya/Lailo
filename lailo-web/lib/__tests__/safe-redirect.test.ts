import { describe, expect, it } from "vitest";
import { safeNextPath } from "../safe-redirect";

describe("safeNextPath", () => {
  it("keeps same-site paths", () => {
    expect(safeNextPath("/orders?page=2")).toBe("/orders?page=2");
  });

  it.each([null, "", "https://evil.example", "//evil.example", "/\\evil.example", "/login?next=/x"])(
    "falls back for %j",
    (next) => {
      expect(safeNextPath(next)).toBe("/account");
    },
  );
});
