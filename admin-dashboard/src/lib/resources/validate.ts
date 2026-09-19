import { SLOT_RESOLVERS, SLOT_TYPES } from "./registry";
import { type FieldDef, type ResourceDef, writableFields } from "./types";

/**
 * Validation and coercion, driven entirely by field type.
 *
 * Both the API and the forms use this, so a rule is written once. A new field
 * type needs a case here and a widget in `components/fields` — that is the
 * complete cost of extending the language.
 */

export interface ValidationError {
  field: string;
  message: string;
}

export interface ValidationResult {
  ok: boolean;
  /** Column -> value, ready to hand to the SQL builder. */
  values: Record<string, unknown>;
  errors: ValidationError[];
}

export interface SlotDefinition {
  name: string;
  type: (typeof SLOT_TYPES)[number];
  description: string;
  required: boolean;
  resolver: (typeof SLOT_RESOLVERS)[number];
  enum?: string[];
}

const SLOT_NAME_PATTERN = /^[a-z][a-z0-9_]*$/;

function isBlank(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === "string" && value.trim() === "");
}

/** Splits a pasted string into tags; also accepts an array as-is. */
export function coerceTags(input: unknown): string[] {
  const raw = Array.isArray(input)
    ? input.map((item) => String(item))
    : typeof input === "string"
      ? input.split(/[\n,]/)
      : [];
  const seen = new Set<string>();
  const tags: string[] = [];
  for (const item of raw) {
    const trimmed = item.trim();
    if (!trimmed) continue;
    const key = trimmed.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    tags.push(trimmed);
  }
  return tags;
}

export function coerceBoolean(input: unknown): boolean | null {
  if (typeof input === "boolean") return input;
  if (typeof input === "number") return input !== 0;
  if (typeof input === "string") {
    const value = input.trim().toLowerCase();
    if (["true", "on", "yes", "1"].includes(value)) return true;
    if (["false", "off", "no", "0", ""].includes(value)) return false;
  }
  return null;
}

/** Validates one slot object, returning messages rather than throwing. */
function validateSlot(raw: unknown, index: number, errors: ValidationError[]): SlotDefinition | null {
  const where = `slots[${index}]`;
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    errors.push({ field: "slots", message: `${where} must be an object.` });
    return null;
  }
  const input = raw as Record<string, unknown>;
  const name = String(input.name ?? "").trim();
  if (!SLOT_NAME_PATTERN.test(name)) {
    errors.push({
      field: "slots",
      message: `${where}: name "${name}" must be lowercase letters, digits and underscores, starting with a letter — it becomes a JSON schema key.`,
    });
    return null;
  }

  const type = String(input.type ?? "string") as SlotDefinition["type"];
  if (!SLOT_TYPES.includes(type)) {
    errors.push({ field: "slots", message: `${where}: type must be one of ${SLOT_TYPES.join(", ")}.` });
    return null;
  }

  const resolver = String(input.resolver ?? "none") as SlotDefinition["resolver"];
  if (!SLOT_RESOLVERS.includes(resolver)) {
    errors.push({
      field: "slots",
      message: `${where}: resolver must be one of ${SLOT_RESOLVERS.join(", ")}. A new resolver kind is the one thing that needs backend code.`,
    });
    return null;
  }

  const description = String(input.description ?? "").trim();
  if (!description) {
    errors.push({
      field: "slots",
      message: `${where}: a description is required — it is the text injected into the shared extraction prompt, so an empty one silently degrades extraction.`,
    });
    return null;
  }

  const slot: SlotDefinition = {
    name,
    type,
    description,
    required: coerceBoolean(input.required) ?? false,
    resolver,
  };

  if (input.enum !== undefined && input.enum !== null && !(Array.isArray(input.enum) && input.enum.length === 0)) {
    const values = coerceTags(input.enum);
    if (!values.length) {
      errors.push({ field: "slots", message: `${where}: enum must list at least one value, or be left empty.` });
      return null;
    }
    if (type !== "string") {
      errors.push({ field: "slots", message: `${where}: enum is only meaningful for a string slot.` });
      return null;
    }
    slot.enum = values;
  }

  return slot;
}

export function validateSlots(input: unknown, errors: ValidationError[]): SlotDefinition[] {
  let raw = input;
  if (typeof raw === "string") {
    if (raw.trim() === "") return [];
    try {
      raw = JSON.parse(raw);
    } catch {
      errors.push({ field: "slots", message: "Slots must be valid JSON." });
      return [];
    }
  }
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw)) {
    errors.push({ field: "slots", message: "Slots must be a JSON array." });
    return [];
  }

  const slots: SlotDefinition[] = [];
  const names = new Set<string>();
  raw.forEach((item, index) => {
    const slot = validateSlot(item, index, errors);
    if (!slot) return;
    if (names.has(slot.name)) {
      errors.push({ field: "slots", message: `Duplicate slot name "${slot.name}".` });
      return;
    }
    names.add(slot.name);
    slots.push(slot);
  });
  return slots;
}

function validateField(field: FieldDef, input: unknown, errors: ValidationError[]): unknown {
  const push = (message: string) => errors.push({ field: field.name, message });

  switch (field.type) {
    case "text":
    case "textarea":
    case "slug": {
      const value = typeof input === "string" ? input.trim() : input == null ? "" : String(input).trim();
      if (field.required && !value) {
        push(`${field.label} is required.`);
        return undefined;
      }
      if (field.maxLength && value.length > field.maxLength) {
        push(`${field.label} must be at most ${field.maxLength} characters.`);
        return undefined;
      }
      if (value && field.pattern && !new RegExp(field.pattern).test(value)) {
        push(`${field.label} is invalid — ${field.patternHint ?? `must match ${field.pattern}`}.`);
        return undefined;
      }
      return value;
    }

    case "number": {
      if (isBlank(input)) {
        if (field.required) {
          push(`${field.label} is required.`);
          return undefined;
        }
        return field.defaultValue ?? null;
      }
      const value = Number(input);
      if (!Number.isFinite(value)) {
        push(`${field.label} must be a number.`);
        return undefined;
      }
      if (field.integer && !Number.isInteger(value)) {
        push(`${field.label} must be a whole number.`);
        return undefined;
      }
      if (field.min !== undefined && value < field.min) {
        push(`${field.label} must be at least ${field.min}.`);
        return undefined;
      }
      if (field.max !== undefined && value > field.max) {
        push(`${field.label} must be at most ${field.max}.`);
        return undefined;
      }
      return value;
    }

    case "boolean": {
      const value = coerceBoolean(input);
      if (value === null) {
        push(`${field.label} must be true or false.`);
        return undefined;
      }
      return value;
    }

    case "enum": {
      const value = typeof input === "string" ? input.trim() : input == null ? "" : String(input).trim();
      if (!value) {
        if (field.required) {
          push(`${field.label} is required.`);
          return undefined;
        }
        return null;
      }
      // `optionsFromColumn` means the taxonomy is open — the select offers the
      // known values, but a genuinely new one is allowed through.
      if (field.options && !field.optionsFromColumn && !field.options.includes(value)) {
        push(`${field.label} must be one of ${field.options.join(", ")}.`);
        return undefined;
      }
      if (field.maxLength && value.length > field.maxLength) {
        push(`${field.label} must be at most ${field.maxLength} characters.`);
        return undefined;
      }
      return value;
    }

    case "tags": {
      const tags = coerceTags(input);
      if (field.required && tags.length === 0) {
        push(`${field.label} needs at least one entry.`);
        return undefined;
      }
      const tooLong = field.maxLength && tags.find((tag) => tag.length > field.maxLength!);
      if (tooLong) {
        push(`"${tooLong}" is longer than ${field.maxLength} characters.`);
        return undefined;
      }
      return tags;
    }

    case "slots":
      return validateSlots(input, errors);

    case "json": {
      if (isBlank(input)) return field.defaultValue ?? null;
      if (typeof input === "string") {
        try {
          return JSON.parse(input);
        } catch {
          push(`${field.label} must be valid JSON.`);
          return undefined;
        }
      }
      return input;
    }

    case "timestamp":
      // Never written from a form.
      return undefined;

    default: {
      const exhaustive: never = field.type;
      throw new Error(`Unhandled field type: ${String(exhaustive)}`);
    }
  }
}

/**
 * Validates a submitted record.
 *
 * `create` fills in defaults for anything omitted; `update` touches only the
 * keys actually present, so a PATCH stays a PATCH.
 */
export function validateRecord(
  resource: ResourceDef,
  input: Record<string, unknown>,
  mode: "create" | "update",
): ValidationResult {
  const errors: ValidationError[] = [];
  const values: Record<string, unknown> = {};

  for (const field of writableFields(resource, mode)) {
    const present = Object.prototype.hasOwnProperty.call(input, field.name);
    if (mode === "update" && !present) continue;

    const raw = present ? input[field.name] : field.defaultValue;
    const value = validateField(field, raw, errors);
    if (value !== undefined) values[field.name] = value;
  }

  const unknownKeys = Object.keys(input).filter(
    (key) => !resource.fields.some((field) => field.name === key),
  );
  if (unknownKeys.length) {
    errors.push({
      field: unknownKeys[0],
      message: `Unknown field(s): ${unknownKeys.join(", ")}.`,
    });
  }

  return { ok: errors.length === 0, values, errors };
}

/** Checks the registry's cross-field rules against the post-write row. */
export function checkInvariants(
  resource: ResourceDef,
  row: Record<string, unknown>,
): ValidationError[] {
  return (resource.invariants ?? [])
    .filter((invariant) => !invariant.check(row))
    .map((invariant) => ({ field: invariant.field ?? "_", message: invariant.message }));
}
