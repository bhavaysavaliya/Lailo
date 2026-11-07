import { describe, expect, it } from "vitest";
import { detectKind, validateIdentifier } from "../identifier";

describe("detectKind", () => {
  /**
   * Input is classified as phone, email or unknown.
   * - Given numbers, formatted numbers, an email, plain text and an empty string
   * - When detectKind runs
   * - Then each gets the expected kind
   */
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
  /**
   * Valid identifiers pass client-side validation.
   * - Given Indian numbers in several formats, an international number and an email
   * - When validateIdentifier runs
   * - Then it returns null (no error)
   */
  it.each(["9876543210", "098765 43210", "919876543210", "+14155552671", "a.b@lailo.in"])("accepts %s", (v) => {
    expect(validateIdentifier(v)).toBeNull();
  });

  /**
   * Invalid identifiers get the right message.
   * - Given empty input, short or non-mobile numbers, a too-short international number, and an incomplete email
   * - When validateIdentifier runs
   * - Then each returns its specific message
   */
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
