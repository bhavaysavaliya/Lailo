import { request } from "./client";

export type CaptchaChallenge = {
  provider: "builtin" | "turnstile";
  captchaId: string | null;
  image: string | null;
  siteKey: string | null;
  expiresInSeconds: number;
};

export type OtpSent = {
  requestId: string;
  identifierType: "EMAIL" | "PHONE";
  maskedIdentifier: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
};

export type SignInResult = {
  userId: string;
  newUser: boolean;
  accessTokenExpiresAt: string;
};

export type UserProfile = {
  id: string;
  email: string | null;
  phone: string | null;
  registeredAt: string | null;
  lastLoginAt: string | null;
};

export const authApi = {
  captcha: () => request<CaptchaChallenge>("/api/v1/auth/captcha"),
  sendOtp: (body: { identifier: string; captchaId: string | null; captchaAnswer: string }) =>
    request<OtpSent>("/api/v1/auth/otp", { method: "POST", body: JSON.stringify(body) }),
  verifyOtp: (body: { requestId: string; otp: string }) =>
    request<SignInResult>("/api/v1/auth/otp/verify", { method: "POST", body: JSON.stringify(body) }),
  logout: () => request<void>("/api/v1/auth/logout", { method: "POST" }),
  me: () => request<UserProfile>("/api/v1/users/me"),
};
