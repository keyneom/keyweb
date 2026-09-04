import { useState } from "react";
import type { ItemRecord, VaultState } from "@keyweb/vault-core";
import { BackIcon, PlusIcon } from "../ui/icons";
import { ringColor } from "./VaultList";

export function Keyrings({
  state,
  items,
  onBack,
  onAdd,
}: {
  state: VaultState;
  items: ItemRecord[];
  onBack: () => void;
  onAdd: (name: string) => Promise<void>;
}) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const rings = Object.values(state.keyrings).filter((r) => !r.deleted.value);

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

      <div className="list">
        {rings.map((r) => {
          const count = items.filter((item) => item.keyring.value === r.id).length;
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
                  {count} password{count === 1 ? "" : "s"} · only you
                </span>
              </span>
            </div>
          );
        })}
      </div>

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
