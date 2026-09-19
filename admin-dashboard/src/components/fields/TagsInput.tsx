"use client";

import { type KeyboardEvent, useState } from "react";

/**
 * Editor for a Postgres `text[]`.
 *
 * Keywords and aliases are the highest-leverage fields in the registry, and
 * they are usually pasted in bulk — so comma, newline and Enter all commit,
 * and a paste of "a, b, c" becomes three entries rather than one.
 */
export function TagsInput({
  value,
  onChange,
  placeholder,
  disabled,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
  disabled?: boolean;
}) {
  const [draft, setDraft] = useState("");

  const commit = (raw: string) => {
    const additions = raw
      .split(/[\n,]/)
      .map((item) => item.trim())
      .filter(Boolean);
    if (!additions.length) return;

    const existing = new Set(value.map((item) => item.toLowerCase()));
    const next = [...value];
    for (const item of additions) {
      if (existing.has(item.toLowerCase())) continue;
      existing.add(item.toLowerCase());
      next.push(item);
    }
    onChange(next);
    setDraft("");
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" || event.key === ",") {
      event.preventDefault();
      commit(draft);
      return;
    }
    // Backspace on an empty box removes the last chip — the expected gesture.
    if (event.key === "Backspace" && !draft && value.length) {
      onChange(value.slice(0, -1));
    }
  };

  return (
    <div
      className={`field flex flex-wrap items-center gap-1.5 ${disabled ? "opacity-60" : ""}`}
      onClick={(event) => {
        const input = event.currentTarget.querySelector("input");
        input?.focus();
      }}
    >
      {value.map((tag, index) => (
        <span
          key={`${tag}-${index}`}
          className="inline-flex items-center gap-1 rounded-md bg-surface-muted px-2 py-0.5 text-xs text-text"
        >
          {tag}
          <button
            type="button"
            disabled={disabled}
            aria-label={`Remove ${tag}`}
            className="text-text-faint transition-colors hover:text-danger"
            onClick={(event) => {
              event.stopPropagation();
              onChange(value.filter((_, i) => i !== index));
            }}
          >
            ×
          </button>
        </span>
      ))}
      <input
        className="min-w-32 flex-1 bg-transparent text-sm outline-none placeholder:text-text-faint"
        value={draft}
        disabled={disabled}
        placeholder={value.length ? "" : placeholder}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={() => commit(draft)}
        onPaste={(event) => {
          const text = event.clipboardData.getData("text");
          if (/[\n,]/.test(text)) {
            event.preventDefault();
            commit(text);
          }
        }}
      />
    </div>
  );
}
