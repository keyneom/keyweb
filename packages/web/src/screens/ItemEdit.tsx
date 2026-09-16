import { useState } from "react";
import {
  isSecretField,
  itemField,
  PRESETS,
  type ItemField,
  type ItemRecord,
  type PasswordRules,
  type SavedRules,
  type VaultState,
} from "@keyweb/vault-core";
import { Generator } from "./Generator";
import { BackIcon } from "../ui/icons";


export function ItemEdit({
  item,
  state,
  defaultKeyringId,
  savedRules,
  lastRules,
  onBack,
  onSave,
  onSaveRules,
  onRulesUsed,
  readOnlyKeyrings,
}: {
  item: ItemRecord | null;
  state: VaultState;
  defaultKeyringId: string;
  /**
   * Keyrings somebody shared as a reader.
   *
   * Saving into one would be accepted here and refused at the Drive file, so
   * it would look exactly like saving and never arrive — and the change would
   * sit in the outbox with the status line reporting it forever. Better to say
   * so before the typing than after it.
   */
  readOnlyKeyrings: ReadonlySet<string>;
  savedRules: SavedRules[];
  lastRules: PasswordRules;
  onBack: () => void;
  onSaveRules: (name: string, rules: PasswordRules) => void;
  onRulesUsed: (rules: PasswordRules) => void;
  onSave: (input: {
    itemId?: string;
    keyringId: string;
    fields: Partial<Record<ItemField, string>>;
  }) => Promise<void>;
}) {
  const [title, setTitle] = useState(item ? (itemField(item, "title") ?? "") : "");
  const [username, setUsername] = useState(item ? (itemField(item, "username") ?? "") : "");
  const [password, setPassword] = useState(item ? (itemField(item, "password") ?? "") : "");
  const [url, setUrl] = useState(item ? (itemField(item, "url") ?? "") : "");
  const [note, setNote] = useState(item ? (itemField(item, "note") ?? "") : "");
  const [keyringId, setKeyringId] = useState(item ? item.keyring.value : defaultKeyringId);
  /**
   * Fields this person added themselves, or that came in from another app.
   *
   * Held as a list rather than an object so a row keeps its identity while its
   * name is being typed — keying on the name would make every keystroke
   * destroy and rebuild the input, and the cursor with it.
   */
  const [extras, setExtras] = useState<EditableField[]>(() => editableFields(item));
  const [saving, setSaving] = useState(false);
  const [generating, setGenerating] = useState(false);

  if (generating) {
    return (
      <Generator
        initial={lastRules}
        saved={savedRules}
        onUse={(made, rules) => {
          setPassword(made);
          onRulesUsed(rules);
          setGenerating(false);
        }}
        onSaveRules={onSaveRules}
        onClose={() => setGenerating(false)}
      />
    );
  }

  const rings = Object.values(state.keyrings).filter((r) => !r.deleted.value);
  // Kept in the list rather than hidden, so an item that is already in one
  // still shows where it lives — but choosing it says why it cannot be saved.
  const readOnly = readOnlyKeyrings.has(keyringId);
  const canSave = title.trim().length > 0 && password.length > 0 && !saving && !readOnly;

  async function save() {
    if (!canSave) return;
    setSaving(true);
    try {
      await onSave({
        ...(item ? { itemId: item.id } : {}),
        keyringId,
        fields: {
          title: title.trim(),
          username,
          password,
          url,
          note,
          ...customFields(item, extras),
        },
      });
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <header className="topbar">
        <button type="button" className="iconbtn" onClick={onBack}>
          <BackIcon />
          Back
        </button>
      </header>

      <h1 className="screen-title">{item ? "Edit password" : "Add a password"}</h1>
      <p className="screen-sub">Only the name and the password are required.</p>

      <label className="field">
        <span>What is it for?</span>
        <div className="box">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Chase Bank"
            autoFocus
          />
        </div>
      </label>

      <label className="field">
        <span>Username or email</span>
        <div className="box">
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="you@example.com"
            autoComplete="off"
          />
        </div>
      </label>

      <label className="field">
        <span>Password</span>
        <div className="box">
          <input
            className="mono"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="off"
          />
          <button type="button" className="iconbtn" onClick={() => setGenerating(true)}>
            Make one
          </button>
        </div>
        <span className="hint">
          Keyweb can make one to whatever rules the site demands.
        </span>
      </label>

      <label className="field">
        <span>Website</span>
        <div className="box">
          <input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="chase.com"
            autoComplete="off"
          />
        </div>
      </label>

      <label className="field">
        <span>Note</span>
        <div className="box">
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="Anything you want to remember"
          />
        </div>
      </label>

      <label className="field">
        <span>Which keyring?</span>
        <div className="box">
          <select
            value={keyringId}
            onChange={(e) => setKeyringId(e.target.value)}
            style={{ flex: 1, border: 0, background: "none", minHeight: "var(--tap)" }}
          >
            {rings.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name.value}
                {readOnlyKeyrings.has(r.id) ? " — you can only look" : ""}
              </option>
            ))}
          </select>
        </div>
        <span className={readOnly ? "hint warn" : "hint"}>
          {readOnly
            ? "This keyring was shared with you to look at. Ask the person who shared it if you need to change something, or choose a keyring of your own."
            : "Everyone on a keyring can see everything on it. Share a keyring, never one password."}
        </span>
      </label>

      <h2 className="import-heading">Anything else</h2>
      <p className="hint" style={{ marginTop: "-0.4rem" }}>
        Security questions, a backup PIN, an account number — whatever this login needs that a
        username and password don't cover.
      </p>

      {extras.map((field, index) => (
        <label className="field" key={field.key}>
          <span>
            <input
              className="linkish"
              value={field.name}
              placeholder="What is it called?"
              aria-label="Field name"
              onChange={(e) =>
                setExtras((rows) =>
                  rows.map((row, i) => (i === index ? { ...row, name: e.target.value } : row)),
                )
              }
            />
          </span>
          <div className="box">
            <input
              type={field.secret ? "password" : "text"}
              value={field.value}
              placeholder="What is it?"
              aria-label={field.name || "Field value"}
              onChange={(e) =>
                setExtras((rows) =>
                  rows.map((row, i) => (i === index ? { ...row, value: e.target.value } : row)),
                )
              }
            />
            <button
              type="button"
              className="iconbtn"
              aria-pressed={field.secret}
              onClick={() =>
                setExtras((rows) =>
                  rows.map((row, i) => (i === index ? { ...row, secret: !row.secret } : row)),
                )
              }
            >
              {field.secret ? "Hidden" : "Shown"}
            </button>
            <button
              type="button"
              className="iconbtn"
              aria-label={`Remove ${field.name || "this field"}`}
              onClick={() => setExtras((rows) => rows.filter((_, i) => i !== index))}
            >
              Remove
            </button>
          </div>
        </label>
      ))}

      <button
        type="button"
        className="btn sec"
        onClick={() =>
          setExtras((rows) => [
            ...rows,
            { key: `new-${rows.length}-${Date.now()}`, name: "", value: "", secret: false },
          ])
        }
      >
        Add another field
      </button>

      <div className="sticky-actions">
        <button type="button" className="btn pri big" disabled={!canSave} onClick={() => void save()}>
          {saving ? "Saving…" : "Save"}
        </button>
      </div>
    </>
  );
}

/** A custom field while it is being edited, with a stable identity. */
type EditableField = { key: string; name: string; value: string; secret: boolean };

/**
 * The fields Keyweb lays out itself, which are not editable as custom ones.
 *
 * `folder` and `tags` are structure the import maintains, and `kind` picks the
 * template; offering them here as free text would let somebody move an item by
 * typing in a box labelled "what is it called?".
 */
const RESERVED = new Set(["title", "username", "password", "url", "note", "otp", "folder", "tags", "kind"]);

function editableFields(item: ItemRecord | null): EditableField[] {
  if (!item) return [];
  return Object.entries(item.fields)
    .filter(([name]) => !RESERVED.has(name) && !name.startsWith("file:"))
    .map(([name, value]) => ({
      key: name,
      // `secret:` and `custom:` are how the field is *stored*; neither is part
      // of what it is called, and showing them would invite somebody to delete
      // the prefix and wonder why the field stopped being hidden.
      name: name.replace(/^secret:/, "").replace(/^custom:/, ""),
      value: value.value,
      secret: isSecretField(name),
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * The custom fields to write, including the ones being removed.
 *
 * A removed field is written as empty rather than left out. A CRDT has no way
 * to say "this field is gone" except by writing a later value, and leaving it
 * out would mean the old value stays and quietly comes back.
 */
function customFields(
  item: ItemRecord | null,
  extras: EditableField[],
): Record<string, string> {
  const fields: Record<string, string> = {};
  for (const before of editableFields(item)) fields[storedName(before)] = "";
  for (const field of extras) {
    const name = field.name.trim();
    if (!name) continue;
    fields[storedName(field)] = field.value;
  }
  return fields;
}

/**
 * Where a field is stored: hidden ones under `secret:`, which is what makes
 * them masked, and anything colliding with a name Keyweb uses under `custom:`
 * so it cannot act like the real one.
 */
function storedName(field: EditableField): string {
  const name = field.name.trim();
  const safe = RESERVED.has(name.toLowerCase()) ? `custom:${name}` : name;
  return field.secret ? `secret:${safe}` : safe;
}
