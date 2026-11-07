import { execFileSync } from "node:child_process";
import { expect, test } from "@playwright/test";

/**
 * Real end-to-end run against Next.js + Spring Boot + Postgres/Redis/Kafka/Mongo.
 * Opt-in: E2E_FULLSTACK=1 npm run test:e2e -- fullstack
 * The captcha answer is read from Redis (via redis-cli) because the image is, by design, not machine-readable.
 */
test.skip(!process.env.E2E_FULLSTACK, "set E2E_FULLSTACK=1 with the backend running");

/**
 * Reads the expected captcha answer straight from Redis, since the image is deliberately not machine-readable.
 *
 * @param captchaId challenge id from the captcha response
 * @returns the answer
 */
function captchaAnswer(captchaId: string): string {
  return execFileSync("redis-cli", ["GET", `captcha:${captchaId}`]).toString().trim();
}

/**
 * A new user signs up with a mobile number through the real stack, then signs out.
 * - Given Next.js, the Spring Boot API and its services running (E2E_FULLSTACK=1), and a random new mobile number
 * - When the user solves the captcha, enters 123456, lands on /account, then clicks Sign out
 * - Then the account shows +91 and the number; the access cookie is HttpOnly and invisible to page scripts; after sign-out the form is empty and /account redirects to /login
 */
test("new user signs up with a mobile number and signs out @fullstack", async ({ page }) => {
  const phone = `9${Math.floor(100_000_000 + Math.random() * 899_999_999)}`;
  const captchaResponse = page.waitForResponse("**/api/v1/auth/captcha");
  await page.goto("/login");
  const { captchaId } = await (await captchaResponse).json();

  await page.getByLabel("Email or mobile number").fill(phone);
  await page.getByLabel("Type the characters you see").fill(captchaAnswer(captchaId));
  await page.getByRole("button", { name: "Send OTP" }).click();

  await expect(page.getByRole("heading", { name: "Enter the code" })).toBeVisible();
  await page.getByLabel("Digit 1 of 6").click();
  await page.keyboard.type("123456");

  await page.waitForURL("**/account");
  await expect(page.getByText(`+91${phone}`)).toBeVisible();

  const cookies = await page.context().cookies();
  const access = cookies.find((c) => c.name === "lailo_at");
  expect(access?.httpOnly).toBe(true);
  expect(await page.evaluate(() => document.cookie)).not.toContain("lailo_at");

  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL("**/login");
  await expect(page.getByLabel("Email or mobile number")).toHaveValue("");
  await expect(page.getByRole("heading", { name: "Sign in or create an account" })).toBeVisible();
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login/);
});
