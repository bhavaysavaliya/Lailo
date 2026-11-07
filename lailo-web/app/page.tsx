import Link from "next/link";

/** Placeholder home page with links to sign in and to the account page, until the chat screen exists. */
export default function Home() {
  return (
    <main className="flex flex-1 flex-col items-start justify-center gap-6 px-6 py-24 sm:px-16">
      <p className="text-5xl font-extrabold tracking-tight text-jamun">lailo</p>
      <p className="max-w-md text-lg text-muted">The store is being stocked. Sign in to set up your account.</p>
      <div className="flex gap-3">
        <Link href="/login" className="rounded-lg bg-jamun px-5 py-3 font-semibold text-white dark:text-jamun-deep">
          Sign in
        </Link>
        <Link href="/account" className="rounded-lg border border-line px-5 py-3 font-semibold">
          Your account
        </Link>
      </div>
    </main>
  );
}
