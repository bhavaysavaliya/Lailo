import type { Metadata } from "next";
import { Suspense } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { UserProfile } from "@/lib/api/auth";
import { SignOutButton } from "./sign-out-button";

export const metadata: Metadata = { title: "Your account" };

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

async function getProfile(): Promise<UserProfile> {
  const token = (await cookies()).get("lailo_at")?.value;
  if (!token) redirect("/login?next=/account");
  const res = await fetch(`${BACKEND_URL}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401) redirect("/login?next=/account");
  if (!res.ok) throw new Error(`Profile request failed with ${res.status}`);
  return res.json();
}

const dateFormat = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Kolkata" });

async function Profile() {
  const profile = await getProfile();
  const rows: Array<[string, string]> = [
    ["Email", profile.email ?? "Not added"],
    ["Mobile number", profile.phone ?? "Not added"],
    ["Member since", profile.registeredAt ? dateFormat.format(new Date(profile.registeredAt)) : "—"],
    ["Last sign-in", profile.lastLoginAt ? dateFormat.format(new Date(profile.lastLoginAt)) : "—"],
  ];
  return (
    <dl className="divide-y divide-line rounded-xl border border-line bg-surface">
      {rows.map(([label, value]) => (
        <div key={label} className="flex flex-col gap-1 px-5 py-4 sm:flex-row sm:justify-between">
          <dt className="text-sm text-muted">{label}</dt>
          <dd className="font-medium">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

export default function AccountPage() {
  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-8 px-4 py-12 sm:px-6">
      <header className="flex items-center justify-between">
        <p className="text-2xl font-extrabold tracking-tight text-jamun">lailo</p>
        <SignOutButton />
      </header>
      <h1 className="text-3xl font-bold tracking-tight">Your account</h1>
      <Suspense fallback={<div className="h-56 animate-pulse rounded-xl bg-jamun-soft" aria-busy="true" />}>
        <Profile />
      </Suspense>
    </main>
  );
}
