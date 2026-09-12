import { useState } from "react";
import { itemField, type ItemField, type ItemRecord, type VaultState } from "@keyweb/vault-core";
import { BackIcon } from "../ui/icons";

/**
 * Readable and strong.
 *
 * The alphabet leaves out every character that has to be guessed at when it is
 * read off a screen and written down — and case counts as much as shape. `l`
 * and `1`, `O` and `0` are the famous pairs, but `c/C`, `k/K`, `s/S`, `u/U`,
 * `v/V`, `w/W`, `x/X` and `z/Z` are worse: the two differ only in size, so an
 * isolated glyph carries no cue at all. The upper-case twin of each is dropped,
 * which makes an ambiguous scrawl resolvable — if it looks like a C, it is a c.
 *
 * The cost is 93 bits down to 89 at the default length, which is not a
 * meaningful trade against a password someone gives up on transcribing.
 */
export function generatePassword(groups = 4, size = 4): string {
  const alphabet = "abcdefghijkmnopqrstuvwxyz23456789ADEFGHJLMNPQRTY";
  const bytes = new Uint32Array(groups * size);
  crypto.getRandomValues(bytes);
  const chars = Array.from(bytes, (n) => alphabet[n % alphabet.length]!);
  const out: string[] = [];
  for (let i = 0; i < groups; i += 1) out.push(chars.slice(i * size, (i + 1) * size).join(""));
  return out.join("-");
}

export function ItemEdit({
  item,
  state,
  defaultKeyringId,
  onBack,
  onSave,
}: {
  item: ItemRecord | null;
  state: VaultState;
  defaultKeyringId: string;
  onBack: () => void;
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

  const rings = Object.values(state.keyrings).filter((r) => !r.deleted.value);
  const canSave = title.trim().length > 0 && password.length > 0 && !saving;

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
          <button
            type="button"
            className="iconbtn"
            onClick={() => setPassword(generatePassword())}
          >
            Suggest one
          </button>
        </div>
        <span className="hint">A suggested password is easy to read aloud over the phone.</span>
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
              </option>
            ))}
          </select>
        </div>
        <span className="hint">
          Everyone on a keyring can see everything on it. Share a keyring, never one password.
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
