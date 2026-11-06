"use client";

import { useEffect, useRef, type ClipboardEvent, type KeyboardEvent } from "react";

type Props = {
  length?: number;
  value: string;
  onChange: (value: string) => void;
  onComplete?: (value: string) => void;
  disabled?: boolean;
  invalid?: boolean;
  confirmed?: boolean;
  describedBy?: string;
};

/**
 * Six single-digit cells that behave like one field: typing advances, Backspace steps back,
 * paste and SMS autofill (autocomplete="one-time-code") fill every cell at once.
 */
export function OtpInput({ length = 6, value, onChange, onComplete, disabled, invalid, confirmed, describedBy }: Props) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);
  // Latest value, ahead of React re-render: fast typing fires the next keystroke before props update.
  const latest = useRef(value);
  useEffect(() => {
    latest.current = value;
  }, [value]);
  const digits = Array.from({ length }, (_, i) => value[i] ?? "");

  const set = (next: string) => {
    latest.current = next;
    onChange(next);
  };

  const commit = (next: string) => {
    const clean = next.replace(/\D/g, "").slice(0, length);
    set(clean);
    if (clean.length === length) onComplete?.(clean);
    return clean;
  };

  const focus = (i: number) => refs.current[Math.max(0, Math.min(length - 1, i))]?.focus();

  const handleInput = (i: number, raw: string) => {
    const typed = raw.replace(/\D/g, "");
    if (!typed) return;
    const value = latest.current;
    // value is contiguous: cell i replaces value[i]; multi-char input = paste or SMS autofill
    const clean = commit(value.slice(0, i) + typed + (typed.length > 1 ? "" : value.slice(i + 1)));
    focus(Math.min(i + typed.length, clean.length));
  };

  const handleKeyDown = (i: number, e: KeyboardEvent<HTMLInputElement>) => {
    const value = latest.current;
    if (e.key === "Backspace") {
      e.preventDefault();
      if (value.length > i) {
        set(value.slice(0, i) + value.slice(i + 1));
      } else if (i > 0) {
        set(value.slice(0, i - 1));
        focus(i - 1);
      }
    } else if (e.key === "ArrowLeft") {
      focus(i - 1);
    } else if (e.key === "ArrowRight") {
      focus(Math.min(i + 1, value.length));
    }
  };

  const handlePaste = (e: ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const clean = commit(e.clipboardData.getData("text"));
    focus(clean.length);
  };

  return (
    <div role="group" aria-label="One-time code" className="flex gap-2 sm:gap-3">
      {digits.map((d, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el;
          }}
          value={d}
          onChange={(e) => handleInput(i, e.target.value)}
          onKeyDown={(e) => handleKeyDown(i, e)}
          onPaste={handlePaste}
          onFocus={(e) => {
            // never leave a gap: focusing past the typed digits jumps to the next empty cell
            if (i > latest.current.length) focus(latest.current.length);
            else e.target.select();
          }}
          inputMode="numeric"
          autoComplete={i === 0 ? "one-time-code" : "off"}
          pattern="[0-9]*"
          maxLength={i === 0 ? length : 1}
          disabled={disabled}
          aria-label={`Digit ${i + 1} of ${length}`}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          style={{ animationDelay: confirmed ? `${i * 45}ms` : undefined }}
          className={[
            "h-14 w-11 rounded-xl border-2 bg-surface text-center text-2xl font-semibold tabular-nums sm:h-16 sm:w-13",
            "transition-colors focus:border-jamun focus:outline-none disabled:opacity-60",
            invalid ? "border-danger" : d ? "border-ink/40" : "border-line",
            confirmed ? "otp-confirmed" : "",
          ].join(" ")}
        />
      ))}
    </div>
  );
}
