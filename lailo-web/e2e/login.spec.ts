import { expect, problem, test } from "./fixtures";

test.describe("sign in / sign up page", () => {
  /**
   * Invalid input is caught in the browser without calling the API.
   * - Given an empty form, then an incomplete mobile number
   * - When Send OTP is clicked
   * - Then field messages appear and no send-OTP request is made
   */
  test("validates input on the client before calling the API", async ({ login, api }) => {
    await login.goto();
    await login.sendOtp().click();

    await expect(login.page.getByText("Enter your email or mobile number.")).toBeVisible();
    await expect(login.page.getByText("Type the characters from the image.")).toBeVisible();

    await login.identifier().fill("12345");
    await login.captcha().fill("abcde");
    await login.sendOtp().click();
    await expect(login.page.getByText("Enter a 10-digit mobile number.")).toBeVisible();
    expect(api.sendOtpBodies).toHaveLength(0);
  });

  /**
   * The input adapts to what is typed.
   * - Given the sign-in form
   * - When digits are typed, then an email fragment, then a lowercase captcha
   * - Then the +91 prefix shows only for the number, and the captcha is uppercased
   */
  test("shows +91 prefix for mobile numbers and uppercases captcha", async ({ login }) => {
    await login.goto();
    await login.identifier().fill("98765");
    await expect(login.page.getByText("+91", { exact: true })).toBeVisible();

    await login.identifier().fill("rahul@");
    await expect(login.page.getByText("+91", { exact: true })).toBeHidden();

    await login.captcha().fill("ab3cd");
    await expect(login.captcha()).toHaveValue("AB3CD");
  });

  /**
   * The happy path: send an OTP, verify it, and follow ?next.
   * - Given /login?next=/welcome with a mocked API
   * - When a spaced mobile number and captcha are submitted and 123456 is typed
   * - Then the code screen shows the masked number, the request bodies are correct, the confirmation appears and the browser goes to /welcome
   */
  test("sends OTP with captcha and verifies the code", async ({ login, api, page }) => {
    await login.goto("?next=/welcome");
    await login.requestOtp("98765 43210", "ab3cd");

    await expect(page.getByRole("heading", { name: "Enter the code" })).toBeVisible();
    await expect(page.getByText("+91******3210")).toBeVisible();
    expect(api.sendOtpBodies[0]).toEqual({ identifier: "98765 43210", captchaId: "cap-1", captchaAnswer: "AB3CD" });

    await expect(login.digit(1)).toBeFocused();
    await login.typeOtp("123456");

    await expect(login.otpStatus()).toHaveText("Signed in. Taking you to your account…");
    expect(api.verifyBodies[0]).toEqual({ requestId: "req-1", otp: "123456" });
    await page.waitForURL("**/welcome");
  });

  /**
   * Pasting a code fills every cell and submits.
   * - Given the code screen and 123456 on the clipboard (Chromium only)
   * - When the code is pasted into the first cell
   * - Then all six cells show their digits and exactly one verify request is sent
   */
  test("pasting a code fills every cell and submits", async ({ login, api, page, browserName }) => {
    test.skip(browserName !== "chromium", "clipboard permissions are Chromium-only");
    await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
    await login.goto();
    await login.requestOtp("rahul@example.com");

    await page.evaluate(() => navigator.clipboard.writeText("123456"));
    await login.digit(1).focus();
    await page.keyboard.press("ControlOrMeta+V");

    for (let i = 1; i <= 6; i++) await expect(login.digit(i)).toHaveValue(String(i));
    await expect.poll(() => api.verifyBodies.length).toBe(1);
  });

  /**
   * A wrong captcha shows the server's message and a fresh image.
   * - Given the API answers CAPTCHA_INVALID
   * - When step one is submitted
   * - Then the message is shown, a second captcha is fetched, the answer is cleared and the identifier is kept
   */
  test("wrong captcha shows the server message and loads a fresh image", async ({ login, api, page }) => {
    /** Simulates a wrong captcha. */
    api.sendOtp = (route) =>
      problem(route, { status: 400, code: "CAPTCHA_INVALID", detail: "The characters didn't match. Try the new image." });
    await login.goto();
    await login.requestOtp("9876543210", "WRONG");

    await expect(page.getByText("The characters didn't match. Try the new image.")).toBeVisible();
    await expect.poll(() => api.captchaCalls).toBe(2);
    await expect(login.captcha()).toHaveValue("");
    await expect(login.identifier()).toHaveValue("9876543210");
  });

  /**
   * Rate limiting is explained to the user.
   * - Given the API answers RATE_LIMITED
   * - When step one is submitted
   * - Then the alert shows the server's message
   */
  test("rate limiting is explained to the user", async ({ login, api, page }) => {
    /** Simulates the rate limit. */
    api.sendOtp = (route) =>
      problem(route, { status: 429, code: "RATE_LIMITED", detail: "Too many attempts. Wait a few minutes and try again.", retryAfterSeconds: 900 });
    await login.goto();
    await login.requestOtp("9876543210");

    await expect(page.getByRole("alert").filter({ hasText: /\S/ })).toHaveText("Too many attempts. Wait a few minutes and try again.");
  });

  /**
   * A wrong code clears the cells and reports attempts left.
   * - Given the API answers OTP_INVALID with 4 attempts left
   * - When 000000 is typed
   * - Then the message is shown, the first cell is empty, marked invalid and focused
   */
  test("incorrect code clears the cells and reports attempts left", async ({ login, api }) => {
    /** Simulates an incorrect code. */
    api.verify = (route) =>
      problem(route, { status: 401, code: "OTP_INVALID", detail: "Incorrect code. 4 attempts left.", attemptsLeft: 4 });
    await login.goto();
    await login.requestOtp("9876543210");
    await login.typeOtp("000000");

    await expect(login.otpStatus()).toHaveText("Incorrect code. 4 attempts left.");
    await expect(login.digit(1)).toHaveValue("");
    await expect(login.digit(1)).toHaveAttribute("aria-invalid", "true");
    await expect(login.digit(1)).toBeFocused();
  });

  /**
   * Resend unlocks after the cooldown and asks for a new captcha.
   * - Given a paused fake clock and a code just sent
   * - When 31 seconds pass and Get a new code is clicked
   * - Then the 30 s countdown was shown, and step one returns with a notice and the identifier kept
   */
  test("resend unlocks after the cooldown and asks for a new captcha", async ({ login, page }) => {
    const start = new Date("2026-11-06T10:00:00Z");
    await page.clock.install({ time: start });
    await login.goto();
    await page.clock.pauseAt(new Date(start.getTime() + 60_000)); // freeze time; we advance it explicitly
    await login.requestOtp("9876543210");
    await expect(page.getByText(/request a new code in 30s/)).toBeVisible();

    await page.clock.runFor(31_000);
    await page.getByRole("button", { name: "Get a new code" }).click();

    await expect(page.getByText("Solve the captcha to get a new code.")).toBeVisible();
    await expect(login.identifier()).toHaveValue("9876543210");
  });

  /**
   * Change returns to step one keeping the identifier.
   * - Given the code screen for an email
   * - When Change is clicked
   * - Then the identifier input holds the email and has focus
   */
  test("'Change' returns to the first step keeping the identifier", async ({ login, page }) => {
    await login.goto();
    await login.requestOtp("rahul@example.com");
    await page.getByRole("button", { name: "Change" }).click();

    await expect(login.identifier()).toHaveValue("rahul@example.com");
    await expect(login.identifier()).toBeFocused();
  });

  /**
   * Open-redirect targets in ?next are ignored.
   * - Given /login?next=//evil.example
   * - When the user signs in
   * - Then the browser stays on the same site
   */
  test("rejects open-redirect targets in ?next", async ({ login, page }) => {
    await login.goto("?next=//evil.example");
    await login.requestOtp("9876543210");
    await login.typeOtp("123456");
    await page.waitForURL((url) => url.host === new URL(page.url()).host && !url.href.includes("evil"));
  });

  /**
   * Backspace walks back through the cells.
   * - Given two digits typed into the code cells
   * - When Backspace is pressed
   * - Then focus moves to cell 2 and it is cleared
   */
  test("keyboard: backspace walks back through the cells", async ({ login, page }) => {
    await login.goto();
    await login.requestOtp("9876543210");
    await login.digit(1).click();
    await page.keyboard.type("12");
    await expect(login.digit(3)).toBeFocused();
    await page.keyboard.press("Backspace");
    await expect(login.digit(2)).toHaveValue("");
    await expect(login.digit(2)).toBeFocused();
  });
});

/**
 * Coming back to /login after signing in shows a fresh form.
 * - Given a user signed in with ?next=/
 * - When they follow the Sign in link back to /login (client-side navigation)
 * - Then the form is empty, no previous email or confirmation is shown, and a new captcha is fetched
 */
test("returning to /login after signing in shows a fresh form, not the previous user's details", async ({ login, api, page }) => {
  await login.goto("?next=/");
  await login.requestOtp("rahul@example.com", "ab3cd");
  await login.typeOtp("123456");
  await page.waitForURL((url) => url.pathname === "/");

  // client-side navigation: Next.js would re-show the preserved (hidden) /login tree
  await page.getByRole("link", { name: "Sign in" }).click();

  await expect(page.getByRole("heading", { name: "Sign in or create an account" })).toBeVisible();
  await expect(login.identifier()).toHaveValue("");
  await expect(login.captcha()).toHaveValue("");
  await expect(page.getByText("Signed in. Taking you to your account…")).toHaveCount(0);
  await expect(page.getByText("rahul@example.com")).toHaveCount(0);
  await expect.poll(() => api.captchaCalls).toBe(2); // the used captcha is replaced
});

/**
 * Anonymous visitors to /account are sent to sign in.
 * - Given no auth cookie
 * - When /account is opened
 * - Then the browser is redirected to /login?next=%2Faccount
 */
test("protected page redirects anonymous visitors to sign in", async ({ page }) => {
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login\?next=%2Faccount/);
});
