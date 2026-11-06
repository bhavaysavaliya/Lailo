import { expect, problem, test } from "./fixtures";

test.describe("sign in / sign up page", () => {
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

  test("shows +91 prefix for mobile numbers and uppercases captcha", async ({ login }) => {
    await login.goto();
    await login.identifier().fill("98765");
    await expect(login.page.getByText("+91", { exact: true })).toBeVisible();

    await login.identifier().fill("rahul@");
    await expect(login.page.getByText("+91", { exact: true })).toBeHidden();

    await login.captcha().fill("ab3cd");
    await expect(login.captcha()).toHaveValue("AB3CD");
  });

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

  test("wrong captcha shows the server message and loads a fresh image", async ({ login, api, page }) => {
    api.sendOtp = (route) =>
      problem(route, { status: 400, code: "CAPTCHA_INVALID", detail: "The characters didn't match. Try the new image." });
    await login.goto();
    await login.requestOtp("9876543210", "WRONG");

    await expect(page.getByText("The characters didn't match. Try the new image.")).toBeVisible();
    await expect.poll(() => api.captchaCalls).toBe(2);
    await expect(login.captcha()).toHaveValue("");
    await expect(login.identifier()).toHaveValue("9876543210");
  });

  test("rate limiting is explained to the user", async ({ login, api, page }) => {
    api.sendOtp = (route) =>
      problem(route, { status: 429, code: "RATE_LIMITED", detail: "Too many attempts. Wait a few minutes and try again.", retryAfterSeconds: 900 });
    await login.goto();
    await login.requestOtp("9876543210");

    await expect(page.getByRole("alert").filter({ hasText: /\S/ })).toHaveText("Too many attempts. Wait a few minutes and try again.");
  });

  test("incorrect code clears the cells and reports attempts left", async ({ login, api }) => {
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

  test("'Change' returns to the first step keeping the identifier", async ({ login, page }) => {
    await login.goto();
    await login.requestOtp("rahul@example.com");
    await page.getByRole("button", { name: "Change" }).click();

    await expect(login.identifier()).toHaveValue("rahul@example.com");
    await expect(login.identifier()).toBeFocused();
  });

  test("rejects open-redirect targets in ?next", async ({ login, page }) => {
    await login.goto("?next=//evil.example");
    await login.requestOtp("9876543210");
    await login.typeOtp("123456");
    await page.waitForURL((url) => url.host === new URL(page.url()).host && !url.href.includes("evil"));
  });

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

test("protected page redirects anonymous visitors to sign in", async ({ page }) => {
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login\?next=%2Faccount/);
});
