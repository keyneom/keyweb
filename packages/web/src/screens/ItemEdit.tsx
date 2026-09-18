import { useState } from "react";
import {
  fieldLabel,
  folderPath,
  storedFieldName,
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
import { Disclosure } from "../ui/Disclosure";
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
  /*
   * The second-factor seed was readable on the detail screen and editable
   * nowhere: it could arrive from a KeePass file and then never be added,
   * corrected or removed by hand. It sits behind the disclosure because most
   * logins do not have one, not because it is difficult.
   */
  const [otp, setOtp] = useState(item ? (itemField(item, "otp") ?? "") : "");
  /*
   * The folder, relative to the keyring.
   *
   * Stored and shown without the keyring's own name at the front, even though
   * an import writes it that way — `folderPath` strips it either way, and
   * putting "Leslie / " in front of every folder on the Leslie keyring is
   * noise somebody would have to delete to type anything.
   */
  const [folder, setFolder] = useState(item ? folderPath(item, state).join(" / ") : "");
  const [extras, setExtras] = useState<EditableField[]>(() => editableFields(item));
  const [saving, setSaving] = useState(false);
  /**
   * Where a generated password is about to go: the password, or a custom field
   * by its position in the list.
   */
  const [generating, setGenerating] = useState<"password" | number | null>(null);

  if (generating !== null) {
    return (
      <Generator
        initial={lastRules}
        saved={savedRules}
        onUse={(made, rules) => {
          if (generating === "password") {
            setPassword(made);
          } else {
            setExtras((rows) =>
              rows.map((row, i) =>
                // Hidden as well as filled. A value nobody has ever read is a
                // secret by construction, and leaving it in plain text on the
                // detail screen because the row happened to say "Shown" would
                // be a leak created by the act of generating it.
                i === generating ? { ...row, value: made, secret: true } : row,
              ),
            );
          }
          onRulesUsed(rules);
          setGenerating(null);
        }}
        onSaveRules={onSaveRules}
        onClose={() => setGenerating(null)}
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
          otp: otp.trim(),
          folder: folder
            .split("/")
            .map((part) => part.trim())
            .filter((part) => part !== "")
            .join(" / "),
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
          <button type="button" className="iconbtn" onClick={() => setGenerating("password")}>
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

      <Disclosure
        label="Anything else this login needs"
        hint="Security questions, a backup PIN, a second-factor code"
        // Open for an item that already has some. Hiding a field somebody can
        // see today, on the grounds that it is advanced, is how these fields
        // went missing in the first place.
        initiallyOpen={extras.length > 0 || otp !== "" || folder !== ""}
      >
      <label className="field">
        <span>Folder</span>
        <div className="box">
          <input
            value={folder}
            onChange={(event) => setFolder(event.target.value)}
            placeholder="Banks"
            autoComplete="off"
          />
        </div>
        <span className="hint">
          A folder is just a way of finding things later. Use a slash for a folder inside a
          folder, like &ldquo;Banks / Cards&rdquo;. Leave it blank to keep this at the top of the
          keyring.
        </span>
      </label>

      <label className="field">
        <span>Second-factor code</span>
        <div className="box">
          <input
            value={otp}
            onChange={(e) => setOtp(e.target.value)}
            placeholder="Paste the setup code the site gave you"
            autoComplete="off"
          />
        </div>
        <span className="hint">
          The long code a site shows you next to a QR square. Keyweb turns it into the six
          digits that change every thirty seconds.
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
            {/*
              The same generator the password has. A security answer should be
              a random string rather than the name of a dog three other sites
              already know, and a backup PIN is a password wearing a different
              name — there was no reason beyond oversight for this to be the
              one place in the app where Keyweb would not make one for you.
            */}
            <button
              type="button"
              className="iconbtn"
              aria-label={`Make a value for ${field.name || "this field"}`}
              onClick={() => setGenerating(index)}
            >
              Make one
            </button>
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
      </Disclosure>

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
      name: fieldLabel(name),
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
 * Where a field is stored. The same rule the KeePass import uses, in one place
 * rather than two: a field called "Answer" typed here and a field called
 * "Answer" imported from a file must land on the same key, or they are two
 * fields the person believes are one.
 */
function storedName(field: EditableField): string {
  return storedFieldName(field.name.trim(), field.secret);
}
