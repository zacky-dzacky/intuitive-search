"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, useState } from "react";

import { buttonClass } from "./ui";

export function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);

    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ password }),
      });

      if (!response.ok) {
        const body = (await response.json().catch(() => ({}))) as { error?: string };
        setError(body.error ?? "Sign-in failed.");
        return;
      }

      // `next` is validated to a same-site path: an open redirect on a login
      // page is how a phished session ends up somewhere else entirely.
      const next = params.get("next");
      const target = next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
      router.replace(target);
      router.refresh();
    } catch {
      setError("Could not reach the server.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="mt-6 space-y-3">
      <label className="block">
        <span className="mb-1.5 block text-sm font-medium text-text">Admin password</span>
        <input
          className="field"
          type="password"
          value={password}
          autoFocus
          autoComplete="current-password"
          onChange={(event) => setPassword(event.target.value)}
        />
      </label>

      {error ? (
        <p className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger" role="alert">
          {error}
        </p>
      ) : null}

      <button type="submit" disabled={busy || !password} className={buttonClass("primary", "w-full")}>
        {busy ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}
