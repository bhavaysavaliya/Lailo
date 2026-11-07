import type { Metadata } from "next";
import { Suspense } from "react";
import { AuthFlow } from "./auth-flow";

export const metadata: Metadata = { title: "Sign in" };

/**
 * Sign-in / sign-up page: brand panel plus the {@link AuthFlow} form.
 * The form reads search params, so it renders inside a Suspense boundary.
 */
export default function LoginPage() {
  return (
    <main className="grid flex-1 grid-rows-[auto_1fr] lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)] lg:grid-rows-1">
      <aside className="relative overflow-hidden bg-jamun-deep px-6 py-6 text-white lg:px-14 lg:py-14">
        <p className="text-2xl font-extrabold tracking-tight lg:text-3xl">lailo</p>
        <div className="hidden lg:mt-24 lg:block lg:max-w-sm">
          <p className="text-3xl font-semibold leading-tight">One code gets you in.</p>
          <p className="mt-4 text-lg leading-relaxed text-white/75">
            Use the email or mobile number you shop with. Track orders, save addresses and check out faster.
          </p>
        </div>
        <p
          aria-hidden
          className="pointer-events-none absolute -bottom-[0.32em] -left-[0.05em] hidden select-none text-[13rem] leading-none font-extrabold tracking-tighter text-white/[0.07] lg:block xl:text-[16rem]"
        >
          lailo
        </p>
      </aside>

      <div className="flex justify-center px-4 py-10 sm:px-6 lg:items-center lg:py-14">
        <div className="w-full max-w-[400px]">
          <Suspense fallback={<div className="h-96" aria-busy="true" />}>
            <AuthFlow />
          </Suspense>
        </div>
      </div>
    </main>
  );
}
