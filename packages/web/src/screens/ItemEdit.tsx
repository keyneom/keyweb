import { useState } from "react";
import {
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
        fields: { title: title.trim(), username, password, url, note },
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

      <div className="sticky-actions">
        <button type="button" className="btn pri big" disabled={!canSave} onClick={() => void save()}>
          {saving ? "Saving…" : "Save"}
        </button>
      </div>
    </>
  );
}
