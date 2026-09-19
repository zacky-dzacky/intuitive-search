"use client";

import type { FieldDef, FieldType } from "@/lib/resources/types";
import type { SlotDefinition } from "@/lib/resources/validate";
import { formatDateTime } from "@/lib/format";

import { SlotsEditor } from "./SlotsEditor";
import { TagsInput } from "./TagsInput";

/**
 * One widget per field type, and a map from type to widget.
 *
 * This map plus the matching case in `validate.ts` is the entire cost of a new
 * field type — no form, page or endpoint changes.
 */

export interface WidgetProps {
  field: FieldDef;
  value: unknown;
  onChange: (value: unknown) => void;
  /** Values discovered in the column, for open taxonomies. */
  options?: string[];
  disabled?: boolean;
  invalid?: boolean;
}

const asString = (value: unknown) => (value === null || value === undefined ? "" : String(value));

function TextWidget({ field, value, onChange, disabled, invalid }: WidgetProps) {
  return (
    <input
      className={`field ${field.type === "slug" ? "font-mono" : ""} ${invalid ? "border-danger" : ""}`}
      value={asString(value)}
      disabled={disabled}
      placeholder={field.placeholder}
      maxLength={field.maxLength}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

function TextAreaWidget({ field, value, onChange, disabled, invalid }: WidgetProps) {
  return (
    <textarea
      className={`field ${invalid ? "border-danger" : ""}`}
      rows={3}
      value={asString(value)}
      disabled={disabled}
      placeholder={field.placeholder}
      maxLength={field.maxLength}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

function NumberWidget({ field, value, onChange, disabled, invalid }: WidgetProps) {
  return (
    <input
      type="number"
      className={`field tabular-nums ${invalid ? "border-danger" : ""}`}
      value={value === null || value === undefined ? "" : String(value)}
      disabled={disabled}
      min={field.min}
      max={field.max}
      step={field.integer ? 1 : "any"}
      placeholder={field.placeholder}
      onChange={(event) => onChange(event.target.value === "" ? null : Number(event.target.value))}
    />
  );
}

function BooleanWidget({ field, value, onChange, disabled }: WidgetProps) {
  const checked = value === true;
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={field.label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`inline-flex h-6 w-11 shrink-0 items-center rounded-full border transition-colors disabled:opacity-60 ${
        checked ? "border-transparent bg-accent" : "border-border-strong bg-surface-muted"
      }`}
    >
      <span
        className={`h-4.5 w-4.5 rounded-full bg-white shadow transition-transform ${
          checked ? "translate-x-5.5" : "translate-x-1"
        }`}
        style={{ height: "1.1rem", width: "1.1rem" }}
      />
    </button>
  );
}

function EnumWidget({ field, value, onChange, options, disabled, invalid }: WidgetProps) {
  const choices = options ?? (field.options ? [...field.options] : []);

  // An open taxonomy stays open: the known values are offered, a new one is
  // still typable. A closed one is a select, and cannot be anything else.
  if (field.optionsFromColumn) {
    const listId = `options-${field.name}`;
    return (
      <>
        <input
          className={`field ${invalid ? "border-danger" : ""}`}
          list={listId}
          value={asString(value)}
          disabled={disabled}
          placeholder={field.placeholder}
          onChange={(event) => onChange(event.target.value)}
        />
        <datalist id={listId}>
          {choices.map((choice) => (
            <option key={choice} value={choice} />
          ))}
        </datalist>
      </>
    );
  }

  return (
    <select
      className={`field ${invalid ? "border-danger" : ""}`}
      value={asString(value)}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
    >
      {!field.required ? <option value="">—</option> : null}
      {choices.map((choice) => (
        <option key={choice} value={choice}>
          {choice}
        </option>
      ))}
    </select>
  );
}

function TagsWidget({ field, value, onChange, disabled }: WidgetProps) {
  return (
    <TagsInput
      value={Array.isArray(value) ? (value as string[]) : []}
      disabled={disabled}
      placeholder={field.placeholder}
      onChange={onChange}
    />
  );
}

function SlotsWidget({ value, onChange, disabled }: WidgetProps) {
  return (
    <SlotsEditor
      value={Array.isArray(value) ? (value as SlotDefinition[]) : []}
      disabled={disabled}
      onChange={onChange}
    />
  );
}

function JsonWidget({ value, onChange, disabled, invalid }: WidgetProps) {
  const text = typeof value === "string" ? value : JSON.stringify(value ?? null, null, 2);
  let parseError: string | null = null;
  try {
    if (text.trim()) JSON.parse(text);
  } catch (error) {
    parseError = error instanceof Error ? error.message : "invalid JSON";
  }

  return (
    <>
      <textarea
        className={`field font-mono text-xs ${invalid || parseError ? "border-danger" : ""}`}
        rows={8}
        spellCheck={false}
        value={text}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
      {parseError ? <p className="mt-1 text-xs text-danger">{parseError}</p> : null}
    </>
  );
}

function TimestampWidget({ value }: WidgetProps) {
  return (
    <p className="field bg-surface-muted text-text-muted">{formatDateTime(value)}</p>
  );
}

const WIDGETS: Record<FieldType, (props: WidgetProps) => React.ReactNode> = {
  text: TextWidget,
  slug: TextWidget,
  textarea: TextAreaWidget,
  number: NumberWidget,
  boolean: BooleanWidget,
  enum: EnumWidget,
  tags: TagsWidget,
  slots: SlotsWidget,
  json: JsonWidget,
  timestamp: TimestampWidget,
};

export function FieldWidget(props: WidgetProps) {
  const Widget = WIDGETS[props.field.type];
  return <>{Widget(props)}</>;
}
