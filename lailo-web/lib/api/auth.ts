import { request } from "./client";

/** Captcha to display: an image and id for the built-in provider, or a site key for Turnstile. */
export type CaptchaChallenge = {
  provider: "builtin" | "turnstile";
  captchaId: string | null;
  image: string | null;
  siteKey: string | null;
  expiresInSeconds: number;
};

/** Result of requesting an OTP. */
export type OtpSent = {
  requestId: string;
  identifierType: "EMAIL" | "PHONE";
  maskedIdentifier: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
};

/** Result of a successful sign-in or refresh; tokens are in HttpOnly cookies, not here. */
export type SignInResult = {
  userId: string;
  newUser: boolean;
  accessTokenExpiresAt: string;
};

/** The signed-in user's profile. */
export type UserProfile = {
  id: string;
  email: string | null;
  phone: string | null;
  registeredAt: string | null;
  lastLoginAt: string | null;
};

/** Body of `POST /api/v1/auth/otp`. */
export type SendOtpBody = {
  /** Email or mobile number as typed. */
  identifier: string;
  /** Built-in captcha id; null for Turnstile. */
  captchaId: string | null;
  /** Captcha answer, or the Turnstile widget token. */
  captchaAnswer: string;
};

/** Body of `POST /api/v1/auth/otp/verify`. */
export type VerifyOtpBody = {
  /** Id returned by the send-OTP call. */
  requestId: string;
  /** The code the user entered. */
  otp: string;
};

/** Typed calls to the auth endpoints, all going through {@link request}. */
export const authApi = {
  /**
   * Gets a new captcha challenge.
   *
   * @returns the challenge
   */
  captcha: () => request<CaptchaChallenge>("/api/v1/auth/captcha"),
  /**
   * Requests a one-time code for an email or mobile number.
   *
   * @param body identifier and captcha answer
   * @returns request id, masked destination and timings
   */
  sendOtp: (body: SendOtpBody) =>
    request<OtpSent>("/api/v1/auth/otp", { method: "POST", body: JSON.stringify(body) }),
  /**
   * Verifies a code and signs in; the server sets the auth cookies.
   *
   * @param body request id and code
   * @returns user id and whether the account is new
   */
  verifyOtp: (body: VerifyOtpBody) =>
    request<SignInResult>("/api/v1/auth/otp/verify", { method: "POST", body: JSON.stringify(body) }),
  /**
   * Signs out of this device; the server revokes the session and clears the cookies.
   *
   * @returns nothing (204)
   */
  logout: () => request<void>("/api/v1/auth/logout", { method: "POST" }),
  /**
   * Gets the signed-in user's profile (refreshes the session once on 401).
   *
   * @returns the profile
   */
  me: () => request<UserProfile>("/api/v1/users/me"),
};
