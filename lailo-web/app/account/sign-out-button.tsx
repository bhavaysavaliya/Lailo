"use client";

import { useState } from "react";
import { authApi } from "@/lib/api/auth";

/**
 * Signs out: revokes the session via the API, then does a full page load to `/login` so no
 * client state (kept alive by Cache Components' Activity) survives for the next user.
 */
export function SignOutButton() {
  const [pending, setPending] = useState(false);

  return (
    <button
      type="button"
      disabled={pending}
      onClick={async () => {
        setPending(true);
        try {
          await authApi.logout();
        } finally {
          // Full page load, not router.replace: with Cache Components, Next keeps visited pages alive in React
          // <Activity>, so a client-side navigation would show the previous user's form state and cached UI.
          window.location.replace("/login");
        }
      }}
      className="rounded-lg border border-line px-4 py-2 text-sm font-semibold hover:bg-jamun-soft disabled:opacity-50"
    >
      {pending ? "Signing out…" : "Sign out"}
    </button>
  );
}
