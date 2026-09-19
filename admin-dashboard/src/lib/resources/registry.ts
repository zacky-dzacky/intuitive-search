
import type { ResourceDef } from "./types";

/**
 * ============================================================================
 * THE REGISTRY — this file is the dashboard.
 * ============================================================================
 *
 * Every managed section below is data, not code. Each entry produces:
 *
 *   · a sidebar entry
 *   · a list page with search, filters, sorting and paging
 *   · create / edit forms with per-type inputs and validation
 *   · `GET|POST /api/admin/<name>` and `GET|PATCH|DELETE /api/admin/<name>/<id>`
 *
 * To manage a new table tomorrow — synonyms, feature flags, an experiment
 * table — append a `ResourceDef` here. No page, route, query, or component is
 * involved. See README §"Adding a managed resource".
 *
 * This mirrors how the search service itself works: a feature is one row, not
 * one code path. The dashboard would be lying about that property if managing
 * it required a code path of its own.
 */

/** Stage-3 resolver strategies. The one list the backend also enumerates. */
export const SLOT_RESOLVERS = [
  "payee",
  "account",
  "currency",
  "amount",
  "date",
  "period",
  "phone",
  "none",
] as const;

/** Slot types the extraction JSON schema supports. */
export const SLOT_TYPES = ["string", "number", "boolean"] as const;

const FEATURE_CATEGORIES = [
  "accounts",
  "cards",
  "general",
  "insurance",
  "investments",
  "loans",
  "payments",
  "security",
  "settings",
  "support",
] as const;

const features: ResourceDef = {
  name: "features",
  table: "features",
  primaryKey: "feature_id",
  label: "Feature",
  labelPlural: "Features",
  icon: "🧭",
  titleField: "display_name",
  description:
    "The search registry. One row is one findable app feature — its vocabulary, its route, and the parameters it will accept.",
  formNote:
    "This row alone drives all four pipeline stages. Keywords and aliases feed full-text and trigram matching; the description widens vector recall; has_params + slots decide whether the LLM gate can ever open. After editing any text field, re-embed the feature so vector search sees the change.",
  fields: [
    {
      name: "feature_id",
      label: "Feature ID",
      type: "slug",
      required: true,
      immutable: true,
      maxLength: 64,
      pattern: "^[a-z][a-z0-9_]*$",
      patternHint: "lowercase letters, digits and underscores; must start with a letter",
      placeholder: "request_cheque_book",
      help: "Stable identifier returned by the API as matched_feature. It cannot be changed later — clients route on it.",
      span: "half",
    },
    {
      name: "display_name",
      label: "Display name",
      type: "text",
      required: true,
      maxLength: 120,
      placeholder: "Request Cheque Book",
      help: "Shown to the user, and the highest-signal part of the text that gets embedded.",
      span: "half",
    },
    {
      name: "description",
      label: "Description",
      type: "textarea",
      maxLength: 500,
      placeholder: "Order a new cheque book for a current account.",
      help: "One sentence in the user's language. This is what gives vector search reach over phrasings nobody thought to list.",
    },
    {
      name: "category",
      label: "Category",
      type: "enum",
      required: true,
      options: FEATURE_CATEGORIES,
      optionsFromColumn: true,
      defaultValue: "general",
      filterable: true,
      span: "half",
    },
    {
      name: "route",
      label: "Route",
      type: "text",
      required: true,
      maxLength: 200,
      pattern: "^/",
      patternHint: "must start with /",
      placeholder: "/accounts/cheque-book",
      help: "What the client opens on a navigate action, and pre-fills on prefill_form.",
      span: "half",
    },
    {
      name: "keywords",
      label: "Keywords",
      type: "tags",
      maxLength: 60,
      placeholder: "cheque, check book, order cheques",
      help: "Natural-language words users type. Feeds full-text search and the containment channel — multi-word terms are worth more than single words.",
    },
    {
      name: "aliases",
      label: "Aliases",
      type: "tags",
      maxLength: 40,
      placeholder: "chq, cheque",
      help: "Abbreviations and shorthand ('trf', 'e-stmt', 'cc'). These are what trigram matching rescues.",
    },
    {
      name: "has_params",
      label: "Accepts parameters",
      type: "boolean",
      defaultValue: false,
      filterable: true,
      help: "The first of four gates on the LLM. Off means the pipeline can never reach Stage 2 for this feature.",
      span: "half",
    },
    {
      name: "enabled",
      label: "Enabled",
      type: "boolean",
      defaultValue: true,
      filterable: true,
      help: "Off removes the feature from search results without deleting its definition.",
      span: "half",
    },
    {
      name: "slots",
      label: "Slots",
      type: "slots",
      defaultValue: [],
      help: "The parameters this feature accepts. Each slot's description is injected into the shared extraction prompt; its type and enum become the JSON schema; its resolver picks the Stage-3 strategy.",
    },
    { name: "updated_at", label: "Updated", type: "timestamp", readOnly: true },
  ],
  listColumns: [
    "feature_id",
    "display_name",
    "category",
    "has_params",
    "slot_count",
    "enabled",
  ],
  searchColumns: ["feature_id", "display_name", "description"],
  defaultSort: { column: "updated_at", direction: "desc" },
  touchColumn: "updated_at",
  reindexOnSave: true,
  computedColumns: {
    slot_count: { sql: "jsonb_array_length(slots)", label: "Slots" },
  },
  invariants: [
    {
      field: "slots",
      // Mirrors the `features_params_have_slots` CHECK constraint.
      message:
        "A feature that accepts parameters needs at least one slot — otherwise Stage 2 has no schema to extract into.",
      check: (row) =>
        row.has_params !== true || (Array.isArray(row.slots) && row.slots.length > 0),
    },
  ],
};

const payees: ResourceDef = {
  name: "payees",
  table: "payees",
  primaryKey: "payee_id",
  label: "Payee",
  labelPlural: "Payees",
  icon: "👤",
  titleField: "full_name",
  description:
    "Demo beneficiary data. Stage 3 resolves a spoken name — 'mom' — against these rows, scoped to the user.",
  formNote:
    "Resolution matches on nickname and full name with pg_trgm, so a nickname the user actually says is worth more than a formal one.",
  fields: [
    {
      name: "payee_id",
      label: "Payee ID",
      type: "slug",
      required: true,
      immutable: true,
      maxLength: 64,
      pattern: "^[a-zA-Z0-9_-]+$",
      patternHint: "letters, digits, underscore and hyphen",
      placeholder: "pay_123",
      span: "half",
    },
    {
      name: "user_id",
      label: "User ID",
      type: "text",
      required: true,
      maxLength: 64,
      filterable: true,
      placeholder: "user_1",
      help: "Owning customer. Every Stage-3 lookup is scoped by this — it is what stops search enumerating someone else's payees.",
      span: "half",
    },
    {
      name: "nickname",
      label: "Nickname",
      type: "text",
      maxLength: 80,
      placeholder: "Mom",
      span: "half",
    },
    {
      name: "full_name",
      label: "Full name",
      type: "text",
      required: true,
      maxLength: 120,
      placeholder: "Jane Doe",
      span: "half",
    },
    {
      name: "account_number",
      label: "Account number",
      type: "text",
      required: true,
      maxLength: 40,
      span: "half",
    },
    { name: "bank_code", label: "Bank code", type: "text", defaultValue: "LOCAL", maxLength: 20, span: "half" },
    { name: "bank_name", label: "Bank name", type: "text", defaultValue: "Local Bank", maxLength: 80, span: "half" },
    {
      name: "currency",
      label: "Currency",
      type: "text",
      defaultValue: "USD",
      maxLength: 3,
      pattern: "^[A-Za-z]{3}$",
      patternHint: "ISO-4217, three letters",
      span: "half",
    },
    { name: "last_used_at", label: "Last used", type: "timestamp", readOnly: true },
  ],
  listColumns: ["payee_id", "nickname", "full_name", "account_number", "bank_name", "user_id"],
  searchColumns: ["payee_id", "nickname", "full_name", "account_number"],
  defaultSort: { column: "payee_id", direction: "asc" },
};

const accounts: ResourceDef = {
  name: "accounts",
  table: "accounts",
  primaryKey: "account_id",
  label: "Account",
  labelPlural: "Accounts",
  icon: "🏦",
  titleField: "label",
  description:
    "Demo customer accounts. The `account` resolver matches slot text like 'from my savings' against these labels.",
  fields: [
    {
      name: "account_id",
      label: "Account ID",
      type: "slug",
      required: true,
      immutable: true,
      maxLength: 64,
      pattern: "^[a-zA-Z0-9_-]+$",
      patternHint: "letters, digits, underscore and hyphen",
      placeholder: "acc_123",
      span: "half",
    },
    {
      name: "user_id",
      label: "User ID",
      type: "text",
      required: true,
      maxLength: 64,
      filterable: true,
      placeholder: "user_1",
      span: "half",
    },
    {
      name: "label",
      label: "Label",
      type: "text",
      required: true,
      maxLength: 80,
      placeholder: "Everyday Savings",
      help: "What the customer calls this account. The resolver matches against exactly this string.",
      span: "half",
    },
    { name: "account_number", label: "Account number", type: "text", required: true, maxLength: 40, span: "half" },
    {
      name: "account_type",
      label: "Type",
      type: "enum",
      options: ["savings", "current", "credit", "deposit", "loan"],
      optionsFromColumn: true,
      defaultValue: "savings",
      filterable: true,
      span: "half",
    },
    {
      name: "currency",
      label: "Currency",
      type: "text",
      defaultValue: "USD",
      maxLength: 3,
      pattern: "^[A-Za-z]{3}$",
      patternHint: "ISO-4217, three letters",
      span: "half",
    },
    {
      name: "balance_minor",
      label: "Balance (minor units)",
      type: "number",
      integer: true,
      defaultValue: 0,
      help: "Cents, not dollars. Demo data only — nothing in this system moves money.",
      span: "half",
    },
  ],
  listColumns: ["account_id", "label", "account_type", "currency", "balance_minor", "user_id"],
  searchColumns: ["account_id", "label", "account_number"],
  defaultSort: { column: "account_id", direction: "asc" },
};

/**
 * A read-only resource: same config, capabilities switched off. The generic
 * API refuses writes and the UI drops the buttons — no separate code path.
 */
const searchAudit: ResourceDef = {
  name: "search-audit",
  table: "search_audit",
  primaryKey: "id",
  label: "Search event",
  labelPlural: "Search log",
  icon: "📜",
  titleField: "query",
  description:
    "What search proposed, one row per request. Never an execution log — nothing in this system executes a transaction.",
  capabilities: { create: false, update: false, delete: false },
  fields: [
    { name: "id", label: "ID", type: "number", readOnly: true, immutable: true },
    { name: "created_at", label: "When", type: "timestamp", readOnly: true },
    { name: "query", label: "Query", type: "text", readOnly: true },
    { name: "matched_feature", label: "Matched feature", type: "text", readOnly: true, filterable: true },
    { name: "confidence", label: "Confidence", type: "number", readOnly: true },
    { name: "action", label: "Action", type: "enum", options: ["navigate", "prefill_form", "suggest", "none"], readOnly: true, filterable: true },
    { name: "llm_invoked", label: "LLM", type: "boolean", readOnly: true, filterable: true },
    { name: "latency_ms", label: "Latency (ms)", type: "number", readOnly: true },
    { name: "user_id", label: "User", type: "text", readOnly: true, filterable: true },
  ],
  listColumns: ["created_at", "query", "matched_feature", "confidence", "action", "llm_invoked", "latency_ms"],
  searchColumns: ["query", "matched_feature", "user_id"],
  defaultSort: { column: "created_at", direction: "desc" },
};

/** The dashboard's own change log — also read-only, also just config. */
const adminAudit: ResourceDef = {
  name: "changes",
  table: "admin_audit",
  primaryKey: "id",
  label: "Change",
  labelPlural: "Change log",
  icon: "🗃️",
  titleField: "resource",
  description:
    "Every write this dashboard made. Registry edits go live without a deploy, so this stands in for the deploy log.",
  capabilities: { create: false, update: false, delete: false },
  fields: [
    { name: "id", label: "ID", type: "number", readOnly: true, immutable: true },
    { name: "at", label: "When", type: "timestamp", readOnly: true },
    { name: "actor", label: "Actor", type: "text", readOnly: true },
    {
      name: "action",
      label: "Action",
      type: "enum",
      options: ["create", "update", "delete", "reembed"],
      readOnly: true,
      filterable: true,
    },
    { name: "resource", label: "Resource", type: "text", readOnly: true, filterable: true },
    { name: "record_id", label: "Record", type: "text", readOnly: true },
    { name: "changes", label: "Changes", type: "json", readOnly: true },
  ],
  listColumns: ["at", "action", "resource", "record_id", "actor"],
  searchColumns: ["resource", "record_id", "action"],
  defaultSort: { column: "at", direction: "desc" },
};

export const RESOURCES: ResourceDef[] = [features, payees, accounts, searchAudit, adminAudit];

export function getResource(name: string): ResourceDef | undefined {
  return RESOURCES.find((resource) => resource.name === name);
}
