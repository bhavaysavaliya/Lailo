import { test as base, expect, type Page, type Route } from "@playwright/test";

/** 1x1 PNG so the captcha <img> renders without the backend. */
export const CAPTCHA_IMAGE =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

/** Body of an RFC 9457 problem response as the API returns it. */
export type Problem = { status: number; code: string; detail: string; [k: string]: unknown };

/**
 * Fulfils a route with a problem response.
 *
 * @param route intercepted request
 * @param p status, code, detail and any extra properties
 * @returns the fulfil promise
 */
export function problem(route: Route, p: Problem) {
  return route.fulfill({ status: p.status, contentType: "application/problem+json", body: JSON.stringify(p) });
}

/** Fake backend: every /api call is intercepted so these specs test the UI contract only. */
export class AuthApiMock {
  captchaCalls = 0;
  sendOtpBodies: Array<Record<string, string>> = [];
  verifyBodies: Array<Record<string, string>> = [];
  /** Response for `POST /api/v1/auth/otp`; defaults to success. Tests reassign it to simulate errors. */
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
  /** Response for `POST /api/v1/auth/otp/verify`; defaults to a new-user sign-in. Tests reassign it to simulate errors. */
  verify: (route: Route) => Promise<void> = (route) =>
    route.fulfill({ json: { userId: "u-1", newUser: true, accessTokenExpiresAt: new Date().toISOString() } });

  /**
   * Creates the mock for one page.
   *
   * @param page the Playwright page whose requests are intercepted
   */
  constructor(private readonly page: Page) {}

  /**
   * Intercepts every auth endpoint: CSRF (204), refresh (401), captcha (counted, numbered ids), send OTP and
   * verify (recording request bodies).
   */
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
  /**
   * Creates the page object.
   *
   * @param page the Playwright page
   */
  constructor(readonly page: Page) {}

  /**
   * The identifier input.
   *
   * @returns its locator
   */
  identifier = () => this.page.getByLabel("Email or mobile number");
  /**
   * The captcha answer input.
   *
   * @returns its locator
   */
  captcha = () => this.page.getByLabel("Type the characters you see");
  /**
   * The "Send OTP" button.
   *
   * @returns its locator
   */
  sendOtp = () => this.page.getByRole("button", { name: "Send OTP" });
  /**
   * One OTP cell.
   *
   * @param n 1-based cell number
   * @returns its locator
   */
  digit = (n: number) => this.page.getByLabel(`Digit ${n} of 6`);
  /**
   * The status line under the OTP cells.
   *
   * @returns its locator
   */
  otpStatus = () => this.page.locator("#otp-status");

  /**
   * Opens `/login` and waits until the form and captcha are visible.
   *
   * @param query optional query string, e.g. `?next=/welcome`
   */
  async goto(query = "") {
    await this.page.goto(`/login${query}`);
    await expect(this.page.getByRole("heading", { name: "Sign in or create an account" })).toBeVisible();
    await expect(this.page.getByAltText(/Captcha/)).toBeVisible();
  }

  /**
   * Fills step one and clicks "Send OTP".
   *
   * @param identifier email or mobile number
   * @param captcha captcha answer
   */
  async requestOtp(identifier: string, captcha = "AB3CD") {
    await this.identifier().fill(identifier);
    await this.captcha().fill(captcha);
    await this.sendOtp().click();
  }

  /**
   * Types a code starting from the first cell.
   *
   * @param code digits to type
   */
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
  /** Provides the sign-in page object. */
  login: async ({ page }, use) => {
    await use(new LoginPage(page));
  },
});

export { expect };
