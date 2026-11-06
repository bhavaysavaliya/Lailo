import { describe, expect, it } from "vitest";
import { detectKind, validateIdentifier } from "../identifier";

describe("detectKind", () => {
  it.each([
    ["9876543210", "phone"],
    ["+91 98765-43210", "phone"],
    ["rahul@example.com", "email"],
    ["rahul", "email"],
    ["", "unknown"],
  ])("%s -> %s", (input, kind) => {
    expect(detectKind(input)).toBe(kind);
  });
});

describe("validateIdentifier", () => {
  it.each(["9876543210", "098765 43210", "919876543210", "+14155552671", "a.b@lailo.in"])("accepts %s", (v) => {
    expect(validateIdentifier(v)).toBeNull();
  });

  it.each([
    ["", "Enter your email or mobile number."],
    ["12345", "Enter a 10-digit mobile number."],
    ["5876543210", "Enter a 10-digit mobile number."],
    ["+12", "Check the number and country code."],
    ["rahul@", "That email address looks incomplete."],
  ])("rejects %j", (v, message) => {
    expect(validateIdentifier(v)).toBe(message);
  });
});
