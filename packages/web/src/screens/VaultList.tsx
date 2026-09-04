import { useMemo, useState } from "react";
import { itemField, type ItemRecord, type VaultState } from "@keyweb/vault-core";
import { KeyIcon, PlusIcon, SearchIcon } from "../ui/icons";
import { StatusLine } from "../ui/StatusLine";
import type { SyncStatus } from "@keyweb/vault-core";

const RING_COLORS = ["#2b7a6b", "#7a5aa8", "#a9632f", "#3f6da8", "#8a4f6b", "#5b6672"];

export function ringColor(state: VaultState, keyringId: string): string {
  const ids = Object.keys(state.keyrings).sort();
  const index = ids.indexOf(keyringId);
  return RING_COLORS[index < 0 ? RING_COLORS.length - 1 : index % RING_COLORS.length]!;
}

function initials(title: string): string {
  const clean = title.trim();
  if (clean.length === 0) return "?";
  return clean.slice(0, 2).toUpperCase();
}

export function VaultList({
  state,
  items,
  status,
  backupConfigured,
  onOpen,
  onAdd,
  onManageKeyrings,
  onSettings,
}: {
  state: VaultState;
  items: ItemRecord[];
  status: SyncStatus;
  backupConfigured: boolean;
  onOpen: (itemId: string) => void;
  onAdd: () => void;
  onManageKeyrings: () => void;
  onSettings: () => void;
}) {
  const [query, setQuery] = useState("");
  const [ring, setRing] = useState<string | null>(null);

  const rings = useMemo(
    () =>
      Object.values(state.keyrings)
        .filter((r) => !r.deleted.value)
        .sort((a, b) => a.name.value.localeCompare(b.name.value)),
    [state.keyrings],
  );

  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return items
      .filter((item) => (ring === null ? true : item.keyring.value === ring))
      .filter((item) => {
        if (needle.length === 0) return true;
        const haystack = [
          itemField(item, "title") ?? "",
          itemField(item, "username") ?? "",
          itemField(item, "url") ?? "",
        ]
          .join(" ")
          .toLowerCase();
        return haystack.includes(needle);
      })
      .sort((a, b) =>
        (itemField(a, "title") ?? "").localeCompare(itemField(b, "title") ?? ""),
      );
  }, [items, query, ring]);

  return (
    <>
      <header className="topbar">
        <span className="brand">
          <KeyIcon />
          Keyweb
        </span>
        <span className="spacer" />
        <button type="button" className="iconbtn" onClick={onManageKeyrings}>
          Keyrings
        </button>
        <button type="button" className="iconbtn" onClick={onSettings}>
          Settings
        </button>
      </header>

      <label className="search">
        <SearchIcon />
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search for a website or app"
          aria-label="Search for a website or app"
        />
      </label>

      <div className="rings">
        <button
          type="button"
          className="ring"
          aria-pressed={ring === null}
          onClick={() => setRing(null)}
        >
          <i style={{ background: "#5b6672" }} />
          All {items.length}
        </button>
        {rings.map((r) => {
          const count = items.filter((item) => item.keyring.value === r.id).length;
          return (
            <button
              key={r.id}
              type="button"
              className="ring"
              aria-pressed={ring === r.id}
              onClick={() => setRing(r.id === ring ? null : r.id)}
            >
              <i style={{ background: ringColor(state, r.id) }} />
              {r.name.value} {count}
            </button>
          );
        })}
      </div>

      <StatusLine status={status} backupConfigured={backupConfigured} />

      {shown.length === 0 ? (
        <p className="empty">
          {items.length === 0
            ? "No passwords saved yet. Add your first one below."
            : "Nothing matches that search."}
        </p>
      ) : (
        <div className="list">
          {shown.map((item) => {
            const title = itemField(item, "title") ?? "Untitled";
            const ringRecord = state.keyrings[item.keyring.value];
            const username = itemField(item, "username");
            return (
              <button key={item.id} type="button" className="row" onClick={() => onOpen(item.id)}>
                <span className="avatar">{initials(title)}</span>
                <span className="rowtext">
                  <b>{title}</b>
                  <span>
                    {ringRecord ? ringRecord.name.value : "No keyring"}
                    {username ? ` · ${username}` : ""}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      )}

      <div className="sticky-actions">
        <button type="button" className="btn pri big" onClick={onAdd}>
          <PlusIcon />
          Add a password
        </button>
      </div>
    </>
  );
}
