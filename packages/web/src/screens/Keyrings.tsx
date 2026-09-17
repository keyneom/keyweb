import { useState } from "react";
import {
  datasetOf,
  KEYRING_SORTS,
  sortKeyrings,
  type ItemRecord,
  type KeyringSort,
  type VaultState,
} from "@keyweb/vault-core";
import { SortPicker } from "../ui/SortPicker";
import { useRememberedSort } from "../vault/useRememberedSort";
import { BackIcon, PlusIcon } from "../ui/icons";
import { ringColor } from "./VaultList";

export function Keyrings({
  state,
  items,
  onBack,
  onAdd,
  onDelete,
  canShare,
  onShare,
}: {
  state: VaultState;
  items: ItemRecord[];
  onBack: () => void;
  onAdd: (name: string) => Promise<void>;
  onDelete: (keyringId: string) => Promise<void>;
  /** False when this build has no Google account and so no way to share. */
  canShare: boolean;
  onShare: (keyringId: string) => void;
}) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  /** The keyring being deleted, held until the count has been acknowledged. */
  const [confirming, setConfirming] = useState<string | null>(null);
  const [sort, setSort] = useRememberedSort<KeyringSort>("keyring-sort", "name-az");
  const counts: Record<string, number> = {};
  for (const item of items) counts[item.keyring.value] = (counts[item.keyring.value] ?? 0) + 1;
  const rings = sortKeyrings(
    Object.values(state.keyrings).filter((r) => !r.deleted.value),
    counts,
    sort,
  );

  async function add() {
    const clean = name.trim();
    if (clean.length === 0 || busy) return;
    setBusy(true);
    try {
      await onAdd(clean);
      setName("");
    } finally {
      setBusy(false);
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

      <h1 className="screen-title">Keyrings</h1>
      <p className="screen-sub">
        A keyring is a group of passwords you can share as one. You share a keyring — never a
        single password.
      </p>

      {rings.length > 1 && (
        <SortPicker label="Order" value={sort} options={KEYRING_SORTS} onChange={setSort} />
      )}

      <div className="list">
        {rings.map((r) => {
          const count = counts[r.id] ?? 0;
          const last = rings.length === 1;
          // A keyring in its own file is one that is shared, or is being got
          // ready to be. Whether *you* shared it or somebody shared it with
          // you is not something this list can know without asking Drive and
          // your passkey, so it says the part it is sure of and the sharing
          // screen says the rest.
          const shared = datasetOf(r) !== null;
          return (
            <div key={r.id} className="row" style={{ cursor: "default" }}>
              <span
                className="avatar"
                style={{ background: ringColor(state, r.id), color: "#fff" }}
                aria-hidden="true"
              >
                ●
              </span>
              <span className="rowtext">
                <b>{r.name.value}</b>
                <span>
                  {count} password{count === 1 ? "" : "s"} · {shared ? "shared" : "only you"}
                </span>
              </span>
              {canShare && (
                <button
                  type="button"
                  className="iconbtn"
                  disabled={busy}
                  onClick={() => onShare(r.id)}
                  aria-label={
                    shared
                      ? `Who can see the ${r.name.value} keyring`
                      : `Share the ${r.name.value} keyring`
                  }
                >
                  {shared ? "Sharing" : "Share"}
                </button>
              )}
              {/* Never the last one: every password lives in a keyring, so a
                  vault with none has nowhere to put the next one. */}
              {!last && (
                <button
                  type="button"
                  className="iconbtn"
                  disabled={busy}
                  onClick={() => setConfirming(r.id)}
                  aria-label={`Delete the ${r.name.value} keyring`}
                >
                  Delete
                </button>
              )}
            </div>
          );
        })}
      </div>

      {confirming !== null && (
        <div className="confirm">
          {(() => {
            const ring = rings.find((r) => r.id === confirming);
            const count = items.filter((item) => item.keyring.value === confirming).length;
            if (!ring) return null;
            return (
              <>
                <p className="status" data-tone="attn">
                  <span>
                    <b>
                      Delete {ring.name.value}
                      {count > 0 ? ` and its ${count} password${count === 1 ? "" : "s"}` : ""}?
                    </b>
                    <em>
                      {count > 0
                        ? "The passwords in it are deleted too. This cannot be undone on this device."
                        : "This keyring is empty. This cannot be undone on this device."}
                    </em>
                  </span>
                </p>
                <button
                  type="button"
                  className="btn danger big"
                  disabled={busy}
                  onClick={async () => {
                    setBusy(true);
                    try {
                      await onDelete(ring.id);
                      setConfirming(null);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  {busy
                    ? "Deleting…"
                    : count > 0
                      ? `Yes, delete ${count === 1 ? "it" : "them"}`
                      : "Yes, delete it"}
                </button>
                <button
                  type="button"
                  className="btn sec"
                  disabled={busy}
                  onClick={() => setConfirming(null)}
                >
                  Keep it
                </button>
              </>
            );
          })()}
        </div>
      )}

      <label className="field" style={{ marginTop: "1.25rem" }}>
        <span>Make a new keyring</span>
        <div className="box">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Household"
            onKeyDown={(e) => {
              if (e.key === "Enter") void add();
            }}
          />
        </div>
        <span className="hint warn">
          This name is not encrypted. Keep it general — no names of people or websites.
        </span>
      </label>

      <button
        type="button"
        className="btn pri big"
        disabled={name.trim().length === 0 || busy}
        onClick={() => void add()}
      >
        <PlusIcon />
        Make this keyring
      </button>
    </>
  );
}
