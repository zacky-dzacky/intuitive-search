import { Suspense } from "react";

import { LoginForm } from "@/components/LoginForm";

export const dynamic = "force-dynamic";

export default function LoginPage() {
  return (
    <main className="flex min-h-screen items-center justify-center px-4">
      <div className="card w-full max-w-sm px-6 py-7">
        <h1 className="text-lg font-semibold tracking-tight text-text">Intuitive Search admin</h1>
        <p className="mt-1 text-sm text-text-muted">
          Manage the feature registry, the search index and search diagnostics.
        </p>
        <Suspense>
          <LoginForm />
        </Suspense>
      </div>
    </main>
  );
}
