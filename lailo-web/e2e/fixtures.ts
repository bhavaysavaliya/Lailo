import { test as base, expect, type Page, type Route } from "@playwright/test";

/** 1x1 PNG so the captcha <img> renders without the backend. */
export const CAPTCHA_IMAGE =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

export type Problem = { status: number; code: string; detail: string; [k: string]: unknown };

export function problem(route: Route, p: Problem) {
  return route.fulfill({ status: p.status, contentType: "application/problem+json", body: JSON.stringify(p) });
}

/** Fake backend: every /api call is intercepted so these specs test the UI contract only. */
export class AuthApiMock {
  captchaCalls = 0;
  sendOtpBodies: Array<Record<string, string>> = [];
  verifyBodies: Array<Record<string, string>> = [];
  sendOtp: (route: Route) => Promise<void> = (route) =>
    route.fulfill({
      json: {
        requestId: "req-1",
        identifierType: "PHONE",
        maskedIdentifier: "+91******3210",
        expiresInSeconds: 300,
        resendAfterSeconds: 30,
      },
    });
  verify: (route: Route) => Promise<void> = (route) =>
    route.fulfill({ json: { userId: "u-1", newUser: true, accessTokenExpiresAt: new Date().toISOString() } });

  constructor(private readonly page: Page) {}

  async install() {
    await this.page.route("**/api/v1/auth/csrf", (r) => r.fulfill({ status: 204 }));
    await this.page.route("**/api/v1/auth/refresh", (r) => problem(r, { status: 401, code: "SESSION_INVALID", detail: "Your session has ended. Sign in again." }));
    await this.page.route("**/api/v1/auth/captcha", (r) => {
      this.captchaCalls++;
      return r.fulfill({
        json: { provider: "builtin", captchaId: `cap-${this.captchaCalls}`, image: CAPTCHA_IMAGE, siteKey: null, expiresInSeconds: 120 },
      });
    });
    await this.page.route("**/api/v1/auth/otp", (r) => {
      this.sendOtpBodies.push(r.request().postDataJSON());
      return this.sendOtp(r);
    });
    await this.page.route("**/api/v1/auth/otp/verify", (r) => {
      this.verifyBodies.push(r.request().postDataJSON());
      return this.verify(r);
    });
  }
}

/** Page object for the single sign-in / sign-up page. */
export class LoginPage {
  constructor(readonly page: Page) {}

  identifier = () => this.page.getByLabel("Email or mobile number");
  captcha = () => this.page.getByLabel("Type the characters you see");
  sendOtp = () => this.page.getByRole("button", { name: "Send OTP" });
  digit = (n: number) => this.page.getByLabel(`Digit ${n} of 6`);
  otpStatus = () => this.page.locator("#otp-status");

  async goto(query = "") {
    await this.page.goto(`/login${query}`);
    await expect(this.page.getByRole("heading", { name: "Sign in or create an account" })).toBeVisible();
    await expect(this.page.getByAltText(/Captcha/)).toBeVisible();
  }

  async requestOtp(identifier: string, captcha = "AB3CD") {
    await this.identifier().fill(identifier);
    await this.captcha().fill(captcha);
    await this.sendOtp().click();
  }

  async typeOtp(code: string) {
    await this.digit(1).click();
    await this.page.keyboard.type(code);
  }
}

export const test = base.extend<{ api: AuthApiMock; login: LoginPage }>({
  // auto: every spec runs against the fake backend, even ones that don't reference `api`
  api: [
    async ({ page }, use) => {
      const api = new AuthApiMock(page);
      await api.install();
      await use(api);
    },
    { auto: true },
  ],
  login: async ({ page }, use) => {
    await use(new LoginPage(page));
  },
});

export { expect };
