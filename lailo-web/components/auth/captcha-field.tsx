"use client";

import type { CaptchaChallenge } from "@/lib/api/auth";

/** Props for {@link CaptchaField}. */
type Props = {
  challenge: CaptchaChallenge | null;
  loading: boolean;
  value: string;
  onChange: (value: string) => void;
  onRefresh: () => void;
  error?: string;
};

/** Captcha image with a "New image" button and the answer input (uppercased as the user types). */
export function CaptchaField({ challenge, loading, value, onChange, onRefresh, error }: Props) {
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor="captcha" className="text-sm font-medium">
        Type the characters you see
      </label>
      <div className="flex items-center gap-3">
        <div className="relative h-14 w-[180px] shrink-0 overflow-hidden rounded-lg border border-line bg-white">
          {challenge?.image && !loading ? (
            // eslint-disable-next-line @next/next/no-img-element -- server-generated data URI, nothing to optimise
            <img src={challenge.image} alt="Captcha: distorted letters and numbers" className="h-full w-full" />
          ) : (
            <div className="h-full w-full animate-pulse bg-jamun-soft" aria-hidden />
          )}
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={loading}
          className="rounded-lg px-3 py-2 text-sm font-semibold text-jamun hover:bg-jamun-soft disabled:opacity-50"
        >
          New image
        </button>
      </div>
      <input
        id="captcha"
        name="captcha"
        value={value}
        onChange={(e) => onChange(e.target.value.toUpperCase())}
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        maxLength={8}
        aria-invalid={!!error || undefined}
        aria-describedby={error ? "captcha-error" : undefined}
        className={[
          "h-12 w-full rounded-lg border bg-surface px-4 text-base tracking-[0.2em] uppercase",
          "focus:border-jamun focus:outline-none",
          error ? "border-danger" : "border-line",
        ].join(" ")}
      />
      {error && (
        <p id="captcha-error" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
