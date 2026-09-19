"use client";

import { SLOT_RESOLVERS, SLOT_TYPES } from "@/lib/resources/registry";
import type { SlotDefinition } from "@/lib/resources/validate";

import { buttonClass } from "../ui";
import { TagsInput } from "./TagsInput";

/**
 * Structured editor for `features.slots`.
 *
 * Slots are the contract between three stages at once, and the JSONB blob
 * hides that. Each control here is labelled with what it actually drives:
 *
 *   description → the text injected into the shared extraction prompt
 *   type / enum → the JSON schema used for constrained decoding
 *   resolver    → which Stage-3 strategy normalises the value
 *
 * The resolver list is fixed on purpose. It is the one place where adding a
 * value genuinely requires backend code, so offering a free-text box here
 * would invite a registry row that can never resolve.
 */

const RESOLVER_HINTS: Record<string, string> = {
  payee: "matches saved payees by nickname/name, scoped to the user",
  account: "matches the user's own accounts by label",
  currency: "normalises to an ISO-4217 code",
  amount: "parses a number out of '10k', '1,200.50'",
  date: "normalises to an ISO-8601 date",
  period: "normalises a range like 'last month'",
  phone: "normalises a phone number",
  none: "passed through as typed",
};

const emptySlot = (): SlotDefinition => ({
  name: "",
  type: "string",
  description: "",
  required: false,
  resolver: "none",
});

export function SlotsEditor({
  value,
  onChange,
  disabled,
}: {
  value: SlotDefinition[];
  onChange: (next: SlotDefinition[]) => void;
  disabled?: boolean;
}) {
  const slots = Array.isArray(value) ? value : [];

  const update = (index: number, patch: Partial<SlotDefinition>) => {
    onChange(slots.map((slot, i) => (i === index ? { ...slot, ...patch } : slot)));
  };

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= slots.length) return;
    const next = [...slots];
    [next[index], next[target]] = [next[target], next[index]];
    onChange(next);
  };

  return (
    <div className="space-y-3">
      {slots.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border-strong px-4 py-6 text-center text-sm text-text-muted">
          No slots. A feature with no slots can never reach Stage 2 — which is the right
          answer for anything that is purely navigational.
        </p>
      ) : null}

      {slots.map((slot, index) => (
        <div key={index} className="rounded-lg border border-border bg-surface-muted p-3">
          <div className="mb-2.5 flex items-center justify-between gap-2">
            <span className="text-xs font-semibold text-text-faint">Slot {index + 1}</span>
            <div className="flex gap-1">
              <button
                type="button"
                disabled={disabled || index === 0}
                onClick={() => move(index, -1)}
                className={buttonClass("ghost", "px-2 py-0.5")}
                aria-label="Move slot up"
              >
                ↑
              </button>
              <button
                type="button"
                disabled={disabled || index === slots.length - 1}
                onClick={() => move(index, 1)}
                className={buttonClass("ghost", "px-2 py-0.5")}
                aria-label="Move slot down"
              >
                ↓
              </button>
              <button
                type="button"
                disabled={disabled}
                onClick={() => onChange(slots.filter((_, i) => i !== index))}
                className={buttonClass("ghost", "px-2 py-0.5 hover:text-danger")}
                aria-label="Remove slot"
              >
                Remove
              </button>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-xs font-medium text-text-muted">Name</span>
              <input
                className="field font-mono"
                value={slot.name}
                disabled={disabled}
                placeholder="recipient"
                onChange={(event) => update(index, { name: event.target.value })}
              />
            </label>

            <label className="block">
              <span className="mb-1 block text-xs font-medium text-text-muted">Type</span>
              <select
                className="field"
                value={slot.type}
                disabled={disabled}
                onChange={(event) =>
                  update(index, {
                    type: event.target.value as SlotDefinition["type"],
                    // enum only means something for strings.
                    ...(event.target.value === "string" ? {} : { enum: undefined }),
                  })
                }
              >
                {SLOT_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </label>

            <label className="block sm:col-span-2">
              <span className="mb-1 block text-xs font-medium text-text-muted">
                Description — goes into the extraction prompt
              </span>
              <textarea
                className="field"
                rows={2}
                value={slot.description}
                disabled={disabled}
                placeholder="Who the money is going to: a saved payee nickname, a person name, or an account number."
                onChange={(event) => update(index, { description: event.target.value })}
              />
            </label>

            <label className="block">
              <span className="mb-1 block text-xs font-medium text-text-muted">
                Resolver — Stage 3 strategy
              </span>
              <select
                className="field"
                value={slot.resolver}
                disabled={disabled}
                onChange={(event) =>
                  update(index, { resolver: event.target.value as SlotDefinition["resolver"] })
                }
              >
                {SLOT_RESOLVERS.map((resolver) => (
                  <option key={resolver} value={resolver}>
                    {resolver}
                  </option>
                ))}
              </select>
              <span className="mt-1 block text-xs text-text-faint">
                {RESOLVER_HINTS[slot.resolver]}
              </span>
            </label>

            <div className="flex items-end pb-1">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-[var(--accent)]"
                  checked={Boolean(slot.required)}
                  disabled={disabled}
                  onChange={(event) => update(index, { required: event.target.checked })}
                />
                Required
              </label>
            </div>

            {slot.type === "string" ? (
              <label className="block sm:col-span-2">
                <span className="mb-1 block text-xs font-medium text-text-muted">
                  Allowed values (optional) — constrains decoding to this set
                </span>
                <TagsInput
                  value={slot.enum ?? []}
                  disabled={disabled}
                  placeholder="once, weekly, monthly"
                  onChange={(next) => update(index, { enum: next.length ? next : undefined })}
                />
              </label>
            ) : null}
          </div>
        </div>
      ))}

      <button
        type="button"
        disabled={disabled}
        className={buttonClass("secondary")}
        onClick={() => onChange([...slots, emptySlot()])}
      >
        + Add slot
      </button>
    </div>
  );
}
