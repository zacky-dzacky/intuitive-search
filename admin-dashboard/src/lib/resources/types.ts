/**
 * The resource description language.
 *
 * A `ResourceDef` is the only thing that needs to exist for a database table
 * to become a fully managed section of this dashboard: navigation entry, list
 * page with search/filter/sort/paging, create and edit forms, validation, and
 * REST endpoints all read from it at runtime. There is no per-resource page,
 * controller, or SQL anywhere in this project.
 *
 * Adding a capability is therefore: append a `ResourceDef` to the registry.
 * The only thing that ever needs new code is a new *field type* — the same
 * shape as the search service itself, where the only thing needing new code is
 * a new resolver kind.
 */

export type FieldType =
  /** Single-line string. */
  | "text"
  /** Multi-line string. */
  | "textarea"
  /** Identifier-ish string: trimmed, pattern-checked, never re-editable. */
  | "slug"
  /** Numeric column. */
  | "number"
  /** Boolean column, rendered as a switch. */
  | "boolean"
  /** Fixed set of string values, rendered as a select. */
  | "enum"
  /** Postgres `text[]`, rendered as a token/chip editor. */
  | "tags"
  /** The `features.slots` JSONB contract, rendered as a structured editor. */
  | "slots"
  /** Any other JSONB column, rendered as a validated JSON textarea. */
  | "json"
  /** `timestamptz`, displayed and never written by a form. */
  | "timestamp";

export interface FieldDef {
  /** Column name. Also the form field name. */
  name: string;
  label: string;
  type: FieldType;
  /** Shown under the input. Say *why* the field matters, not what it is. */
  help?: string;
  required?: boolean;
  /** Settable at create time, frozen afterwards. Primary keys, mostly. */
  immutable?: boolean;
  /** Never written — generated columns, `updated_at`, and friends. */
  readOnly?: boolean;
  /** Kept out of the form entirely (still available to list columns). */
  hiddenInForm?: boolean;
  defaultValue?: unknown;
  placeholder?: string;
  /** `enum` only. */
  options?: readonly string[];
  /**
   * `enum` only — offer whatever values already exist in this column, in
   * addition to `options`. Lets a free-form taxonomy (categories) stay
   * consistent without hardcoding it.
   */
  optionsFromColumn?: boolean;
  /** `number` only. */
  min?: number;
  max?: number;
  integer?: boolean;
  /** `text` / `textarea` / `slug` / `tags`. */
  maxLength?: number;
  /** `slug` / `text`. */
  pattern?: string;
  patternHint?: string;
  /** Offer this column as a filter on the list page. */
  filterable?: boolean;
  /** Layout hint for the form grid. */
  span?: "full" | "half";
}

export interface ComputedColumn {
  /** SQL expression evaluated against the resource's table. */
  sql: string;
  label: string;
  /**
   * `flag` renders a two-state badge — the natural shape for "is this row in
   * a state someone needs to act on?".
   */
  kind?: "flag" | "value";
  trueLabel?: string;
  falseLabel?: string;
  /** Which state deserves attention. Default: true is the warning. */
  trueTone?: "ok" | "warn" | "danger" | "neutral";
  falseTone?: "ok" | "warn" | "danger" | "neutral";
}

export interface ResourceInvariant {
  /** Field to attach the message to, when it belongs to one. */
  field?: string;
  message: string;
  /** Return false to reject the row. */
  check: (row: Record<string, unknown>) => boolean;
}

export interface ResourceDef {
  /** URL segment and API path: `/resources/<name>`. */
  name: string;
  /** Postgres table. */
  table: string;
  /** Primary key column. Must also appear in `fields`. */
  primaryKey: string;
  label: string;
  labelPlural: string;
  /** Emoji shown in the sidebar. */
  icon: string;
  /** One line under the page title. */
  description?: string;
  /** Column used as the human-readable name of a row. */
  titleField: string;
  fields: FieldDef[];
  /** Field names shown as table columns, in order. */
  listColumns: string[];
  /** Columns the list search box matches with `ILIKE`. */
  searchColumns: string[];
  defaultSort: { column: string; direction: "asc" | "desc" };
  /**
   * Derived display columns, keyed by result alias. Raw SQL from config — for
   * state that belongs in the database rather than in the browser, like a
   * feature's slot count.
   *
   * Aliases may appear in `listColumns` and be sorted on, exactly like real
   * columns.
   */
  computedColumns?: Record<string, ComputedColumn>;
  capabilities?: {
    create?: boolean;
    update?: boolean;
    delete?: boolean;
  };
  /**
   * Cross-field rules, checked against the full row after a write is merged.
   * Config, not code: mirror a database CHECK constraint here and the user
   * gets a sentence instead of a Postgres error.
   */
  invariants?: ResourceInvariant[];
  /** Guidance rendered above the form. */
  formNote?: string;
  /** Column set to `now()` on every update, when the table has one. */
  touchColumn?: string;
  /**
   * This table feeds the search index, so the form offers to have the
   * backend rebuild it right after saving rather than on its next refresh.
   * Only `features` does today; it is a flag rather than a hardcoded
   * resource name so a second indexed table costs nothing.
   */
  reindexOnSave?: boolean;
}

export function fieldByName(resource: ResourceDef, name: string): FieldDef | undefined {
  return resource.fields.find((field) => field.name === name);
}

/** Fields a form may submit: not read-only, not hidden, not generated. */
export function writableFields(resource: ResourceDef, mode: "create" | "update"): FieldDef[] {
  return resource.fields.filter((field) => {
    if (field.readOnly || field.hiddenInForm) return false;
    if (mode === "update" && field.immutable) return false;
    return true;
  });
}

/** Every column the list/detail queries need to select. */
export function selectableColumns(resource: ResourceDef): string[] {
  return resource.fields.map((field) => field.name);
}

export function can(resource: ResourceDef, action: "create" | "update" | "delete"): boolean {
  return resource.capabilities?.[action] !== false;
}
