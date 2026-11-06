/** Client-side mirror of the backend Identifier rules - for instant feedback only; the server is authoritative. */
export type IdentifierKind = "email" | "phone" | "unknown";

const EMAIL = /^[^\s@]{1,64}@[^\s@]+\.[^\s@]{2,}$/;
const SEPARATORS = /[\s\-().]/g;

export function detectKind(raw: string): IdentifierKind {
  const value = raw.trim();
  if (!value) return "unknown";
  if (value.includes("@") || /[a-z]/i.test(value)) return "email";
  if (/^[+\d][\d\s\-().]*$/.test(value)) return "phone";
  return "unknown";
}

/** Returns a user-facing problem with the input, or null when it looks valid. */
export function validateIdentifier(raw: string): string | null {
  const value = raw.trim();
  if (!value) return "Enter your email or mobile number.";
  const kind = detectKind(value);
  if (kind === "email") {
    return EMAIL.test(value) ? null : "That email address looks incomplete.";
  }
  if (kind === "phone") {
    let digits = value.replace(SEPARATORS, "");
    if (digits.startsWith("+")) {
      return /^\+[1-9]\d{7,14}$/.test(digits) ? null : "Check the number and country code.";
    }
    if (digits.length === 11 && digits.startsWith("0")) digits = digits.slice(1);
    if (digits.length === 12 && digits.startsWith("91")) digits = digits.slice(2);
    return /^[6-9]\d{9}$/.test(digits) ? null : "Enter a 10-digit mobile number.";
  }
  return "Enter a valid email address or 10-digit mobile number.";
}
