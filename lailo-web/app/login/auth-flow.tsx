"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { CaptchaField } from "@/components/auth/captcha-field";
import { OtpInput } from "@/components/auth/otp-input";
import { authApi, type CaptchaChallenge, type OtpSent } from "@/lib/api/auth";
import { ApiError, refreshSession } from "@/lib/api/client";
import { detectKind, validateIdentifier } from "@/lib/identifier";
import { safeNextPath } from "@/lib/safe-redirect";

type Step = "identify" | "verify";
type FieldErrors = { identifier?: string; captcha?: string; otp?: string; form?: string };

const IS_DEV = process.env.NODE_ENV !== "production";

export function AuthFlow() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = safeNextPath(searchParams.get("next"));

  const [step, setStep] = useState<Step>("identify");
  const [identifier, setIdentifier] = useState("");
  const [captcha, setCaptcha] = useState<CaptchaChallenge | null>(null);
  const [captchaLoading, setCaptchaLoading] = useState(true);
  const [captchaAnswer, setCaptchaAnswer] = useState("");
  const [sent, setSent] = useState<OtpSent | null>(null);
  const [otp, setOtp] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  // Deadline-based countdown: stays correct even when a background tab throttles timers.
  const [resendAt, setResendAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const resendIn = Math.max(0, Math.ceil((resendAt - now) / 1000));
  const [notice, setNotice] = useState<string | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const identifierRef = useRef<HTMLInputElement>(null);
  const otpWrapRef = useRef<HTMLDivElement>(null);

  const loadCaptcha = useCallback(async () => {
    setCaptchaLoading(true);
    setCaptchaAnswer("");
    try {
      setCaptcha(await authApi.captcha());
    } catch (e) {
      setErrors((prev) => ({ ...prev, form: e instanceof ApiError ? e.message : "Couldn't load the captcha." }));
    } finally {
      setCaptchaLoading(false);
    }
  }, []);

  // Arriving with ?next= means the access token expired; try the refresh cookie before asking to sign in again.
  // Guarded so StrictMode's double mount in dev doesn't issue two captchas (each one counts against rate limits).
  const initialised = useRef(false);
  const resetOnHide = useRef(false);

  // Next.js keeps this page alive (hidden) in React <Activity> after navigating away. Once a sign-in has
  // succeeded, wipe the identifier/OTP when the page is hidden so it never reappears for the next visitor,
  // and re-arm the mount effect so a fresh captcha is fetched when /login is shown again.
  useLayoutEffect(() => {
    return () => {
      if (!resetOnHide.current) return;
      resetOnHide.current = false;
      initialised.current = false;
      setStep("identify");
      setIdentifier("");
      setCaptcha(null);
      setCaptchaAnswer("");
      setSent(null);
      setOtp("");
      setBusy(false);
      setConfirmed(false);
      setResendAt(0);
      setNotice(null);
      setErrors({});
    };
  }, []);
  useEffect(() => {
    if (initialised.current) return;
    initialised.current = true;
    (async () => {
      if (searchParams.has("next") && (await refreshSession())) {
        router.replace(next);
        return;
      }
      await loadCaptcha();
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run once on mount
  }, []);

  useEffect(() => {
    if (resendAt <= Date.now()) return;
    const t = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= resendAt) clearInterval(t);
    }, 1000);
    return () => clearInterval(t);
  }, [resendAt]);

  const startCooldown = (seconds: number) => {
    const current = Date.now();
    setNow(current);
    setResendAt(current + seconds * 1000);
  };

  useEffect(() => {
    if (step === "identify") identifierRef.current?.focus();
  }, [step]);

  // Focus the first cell on entering the step and after a rejected code - only once the cells are enabled again.
  useEffect(() => {
    if (step === "verify" && !busy && !confirmed) otpWrapRef.current?.querySelector("input")?.focus();
  }, [step, busy, confirmed, errors.otp]);

  async function sendOtp(e: FormEvent) {
    e.preventDefault();
    const identifierError = validateIdentifier(identifier);
    const captchaError = captchaAnswer.trim() ? undefined : "Type the characters from the image.";
    if (identifierError || captchaError) {
      setErrors({ identifier: identifierError ?? undefined, captcha: captchaError });
      return;
    }
    setBusy(true);
    setErrors({});
    setNotice(null);
    try {
      const result = await authApi.sendOtp({ identifier, captchaId: captcha?.captchaId ?? null, captchaAnswer });
      setSent(result);
      setOtp("");
      startCooldown(result.resendAfterSeconds);
      setStep("verify");
    } catch (err) {
      const apiError = err instanceof ApiError ? err : null;
      switch (apiError?.code) {
        case "INVALID_IDENTIFIER":
          setErrors({ identifier: apiError.message });
          break;
        case "CAPTCHA_INVALID":
          setErrors({ captcha: apiError.message });
          break;
        case "VALIDATION_FAILED":
          setErrors({ identifier: apiError.fieldErrors.identifier, captcha: apiError.fieldErrors.captchaAnswer });
          break;
        default:
          setErrors({ form: apiError?.message ?? "Couldn't send the code. Try again." });
      }
      await loadCaptcha(); // a captcha is single use, right or wrong
    } finally {
      setBusy(false);
    }
  }

  async function verify(code: string) {
    if (!sent || busy) return;
    setBusy(true);
    setErrors({});
    try {
      await authApi.verifyOtp({ requestId: sent.requestId, otp: code });
      setConfirmed(true);
      resetOnHide.current = true;
      setTimeout(() => router.replace(next), 550);
    } catch (err) {
      const apiError = err instanceof ApiError ? err : null;
      setOtp("");
      if (apiError?.code === "OTP_EXPIRED" || apiError?.code === "OTP_ATTEMPTS_EXCEEDED") {
        setResendAt(0);
      }
      setErrors({ otp: apiError?.message ?? "Couldn't check the code. Try again." });
      setBusy(false);
    }
  }

  function backToIdentify(message: string | null) {
    setStep("identify");
    setSent(null);
    setOtp("");
    setErrors({});
    setNotice(message);
    void loadCaptcha();
  }

  const kind = detectKind(identifier);

  if (step === "verify" && sent) {
    return (
      <section aria-labelledby="verify-heading" className="flex flex-col gap-6">
        <div className="flex flex-col gap-2">
          <h1 id="verify-heading" className="text-3xl font-bold tracking-tight">
            Enter the code
          </h1>
          <p className="text-muted">
            We sent a 6-digit code to <span className="font-semibold text-ink">{sent.maskedIdentifier}</span>.{" "}
            <button type="button" onClick={() => backToIdentify(null)} className="font-semibold text-jamun underline-offset-4 hover:underline">
              Change
            </button>
          </p>
        </div>

        <div ref={otpWrapRef} className="flex flex-col gap-3">
          <OtpInput
            value={otp}
            onChange={(v) => {
              setOtp(v);
              if (errors.otp) setErrors({});
            }}
            onComplete={verify}
            disabled={busy || confirmed}
            invalid={!!errors.otp}
            confirmed={confirmed}
            describedBy="otp-status"
          />
          <p id="otp-status" role="status" aria-live="polite" className={`min-h-6 text-sm ${errors.otp ? "text-danger" : "text-muted"}`}>
            {confirmed ? "Signed in. Taking you to your account…" : errors.otp ?? (busy ? "Checking…" : "")}
          </p>
        </div>

        <button
          type="button"
          onClick={() => verify(otp)}
          disabled={otp.length !== 6 || busy || confirmed}
          className="h-12 rounded-lg bg-jamun font-semibold text-white transition-colors hover:bg-jamun-deep disabled:cursor-not-allowed disabled:opacity-50 dark:text-jamun-deep dark:hover:text-white"
        >
          Verify and continue
        </button>

        <div className="text-sm text-muted">
          {resendIn > 0 ? (
            <span>You can request a new code in {resendIn}s.</span>
          ) : (
            <button
              type="button"
              onClick={() => backToIdentify("Solve the captcha to get a new code.")}
              className="font-semibold text-jamun underline-offset-4 hover:underline"
            >
              Get a new code
            </button>
          )}
        </div>

        {IS_DEV && (
          <p className="rounded-lg border border-dashed border-line px-3 py-2 text-sm text-muted">
            Development build: the code is always <span className="font-semibold tabular-nums text-ink">123456</span>.
          </p>
        )}
      </section>
    );
  }

  return (
    <section aria-labelledby="identify-heading" className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <h1 id="identify-heading" className="text-3xl font-bold tracking-tight">
          Sign in or create an account
        </h1>
        <p className="text-muted">We&apos;ll send a one-time code. No password needed.</p>
      </div>

      {(notice || errors.form) && (
        <p
          role={errors.form ? "alert" : "status"}
          className={`rounded-lg px-4 py-3 text-sm ${errors.form ? "bg-danger-soft text-danger" : "bg-jamun-soft text-ink"}`}
        >
          {errors.form ?? notice}
        </p>
      )}

      <form onSubmit={sendOtp} noValidate className="flex flex-col gap-5">
        <div className="flex flex-col gap-2">
          <label htmlFor="identifier" className="text-sm font-medium">
            Email or mobile number
          </label>
          <div
            className={[
              "flex h-12 items-center rounded-lg border bg-surface focus-within:border-jamun",
              errors.identifier ? "border-danger" : "border-line",
            ].join(" ")}
          >
            {kind === "phone" && !identifier.trim().startsWith("+") && (
              <span className="border-r border-line px-3 text-base text-muted" aria-hidden>
                +91
              </span>
            )}
            <input
              ref={identifierRef}
              id="identifier"
              name="identifier"
              value={identifier}
              onChange={(e) => {
                setIdentifier(e.target.value);
                if (errors.identifier) setErrors((p) => ({ ...p, identifier: undefined }));
              }}
              autoComplete="username"
              inputMode={kind === "phone" ? "tel" : "email"}
              autoCapitalize="none"
              spellCheck={false}
              maxLength={254}
              aria-invalid={!!errors.identifier || undefined}
              aria-describedby={errors.identifier ? "identifier-error" : undefined}
              className="h-full w-full rounded-lg bg-transparent px-4 text-base focus:outline-none"
            />
          </div>
          {errors.identifier && (
            <p id="identifier-error" className="text-sm text-danger">
              {errors.identifier}
            </p>
          )}
        </div>

        <CaptchaField
          challenge={captcha}
          loading={captchaLoading}
          value={captchaAnswer}
          onChange={(v) => {
            setCaptchaAnswer(v);
            if (errors.captcha) setErrors((p) => ({ ...p, captcha: undefined }));
          }}
          onRefresh={loadCaptcha}
          error={errors.captcha}
        />

        <button
          type="submit"
          disabled={busy || captchaLoading}
          className="h-12 rounded-lg bg-jamun font-semibold text-white transition-colors hover:bg-jamun-deep disabled:cursor-not-allowed disabled:opacity-50 dark:text-jamun-deep dark:hover:text-white"
        >
          {busy ? "Sending…" : "Send OTP"}
        </button>
      </form>

      <p className="text-xs leading-5 text-muted">
        By continuing you agree to Lailo&apos;s Terms of Use and Privacy Notice. New here? Your account is created when
        you verify the code.
      </p>
    </section>
  );
}
