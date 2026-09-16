import { useCallback, useMemo, useState } from "react";
import { itemField, type ItemRecord, type VaultState } from "@keyweb/vault-core";
import { KeyIcon, PlusIcon, SearchIcon } from "../ui/icons";
import { StatusLine } from "../ui/StatusLine";
import { useLongPress } from "../ui/useLongPress";
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
  onDeleteMany,
  onMoveMany,
  onSync,
}: {
  state: VaultState;
  items: ItemRecord[];
  status: SyncStatus;
  backupConfigured: boolean;
  onOpen: (itemId: string) => void;
  onAdd: () => void;
  onManageKeyrings: () => void;
  onSettings: () => void;
  onDeleteMany: (itemIds: string[]) => Promise<void>;
  onMoveMany: (itemIds: string[], keyringId: string) => Promise<void>;
  onSync: () => void;
}) {
  const [query, setQuery] = useState("");
  const [ring, setRing] = useState<string | null>(null);

  /**
   * Selection mode.
   *
   * Entered by holding a row rather than by a mode switch in the corner,
   * because the thing being selected is what you are already touching. Null
   * means not selecting at all, which is different from selecting nothing:
   * an empty selection still shows the bar, so clearing the last row does not
   * throw you out of the mode mid-task.
   */
  const [selected, setSelected] = useState<Set<string> | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [moving, setMoving] = useState(false);
  const [busy, setBusy] = useState(false);

  const selecting = selected !== null;

  const exitSelection = useCallback(() => {
    setSelected(null);
    setConfirming(false);
    setMoving(false);
  }, []);

  const toggle = useCallback((itemId: string) => {
    setSelected((current) => {
      const next = new Set(current ?? []);
      if (next.has(itemId)) next.delete(itemId);
      else next.add(itemId);
      return next;
    });
  }, []);

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
      {selecting ? (
        <header className="topbar">
          <button type="button" className="iconbtn" onClick={exitSelection}>
            Done
          </button>
          <span className="spacer" />
          <b>{selected.size} selected</b>
          <span className="spacer" />
          <button
            type="button"
            className="iconbtn"
            onClick={() =>
              setSelected(
                selected.size === shown.length
                  ? new Set()
                  : new Set(shown.map((item) => item.id)),
              )
            }
          >
            {selected.size === shown.length ? "Clear" : "Select all"}
          </button>
        </header>
      ) : (
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
      )}

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

      <StatusLine
        status={status}
        backupConfigured={backupConfigured}
        onSync={onSync}
        syncing={status.syncing}
      />

      {shown.length === 0 ? (
        <p className="empty">
          {items.length === 0
            ? "No passwords saved yet. Add your first one below."
            : "Nothing matches that search."}
        </p>
      ) : (
        <div className="list">
          {shown.map((item) => (
            <ItemRow
              key={item.id}
              item={item}
              state={state}
              selecting={selecting}
              selected={selected?.has(item.id) ?? false}
              onOpen={() => onOpen(item.id)}
              onToggle={() => toggle(item.id)}
              onStartSelecting={() => setSelected(new Set([item.id]))}
            />
          ))}
        </div>
      )}

      <div className="sticky-actions">
        {selecting ? (
          <SelectionActions
            count={selected.size}
            busy={busy}
            confirming={confirming}
            moving={moving}
            keyrings={rings}
            onAskDelete={() => setConfirming(true)}
            onCancel={() => {
              setConfirming(false);
              setMoving(false);
            }}
            onDelete={async () => {
              setBusy(true);
              try {
                await onDeleteMany([...selected]);
                exitSelection();
              } finally {
                setBusy(false);
              }
            }}
            onAskMove={() => setMoving(true)}
            onMove={async (keyringId) => {
              setBusy(true);
              try {
                await onMoveMany([...selected], keyringId);
                exitSelection();
              } finally {
                setBusy(false);
              }
            }}
          />
        ) : (
          <button type="button" className="btn pri big" onClick={onAdd}>
            <PlusIcon />
            Add a password
          </button>
        )}
      </div>
    </>
  );
}

/**
 * One password in the list, which behaves differently while selecting.
 *
 * Its own component so the long-press hook gets one instance per row; a single
 * shared hook would carry one row's press timer across all of them.
 */
function ItemRow({
  item,
  state,
  selecting,
  selected,
  onOpen,
  onToggle,
  onStartSelecting,
}: {
  item: ItemRecord;
  state: VaultState;
  selecting: boolean;
  selected: boolean;
  onOpen: () => void;
  onToggle: () => void;
  onStartSelecting: () => void;
}) {
  const title = itemField(item, "title") ?? "Untitled";
  const ringRecord = state.keyrings[item.keyring.value];
  const username = itemField(item, "username");
  const { handlers, swallowClick } = useLongPress(onStartSelecting);

  return (
    <button
      type="button"
      className="row"
      aria-pressed={selecting ? selected : undefined}
      {...handlers}
      onClick={(event) => {
        // A click that is really the tail of a long press opens nothing.
        if (swallowClick(event)) return;
        if (selecting) onToggle();
        else onOpen();
      }}
    >
      <span className="avatar" data-selected={selecting && selected ? "" : undefined}>
        {selecting ? (selected ? "✓" : "") : initials(title)}
      </span>
      <span className="rowtext">
        <b>{title}</b>
        <span>
          {ringRecord ? ringRecord.name.value : "No keyring"}
          {username ? ` · ${username}` : ""}
        </span>
      </span>
    </button>
  );
}

/**
 * What can be done to the selection.
 *
 * Delete asks first, and says the number rather than "these": a count is the
 * one fact that makes the size of the mistake visible before it is made.
 */
function SelectionActions({
  count,
  busy,
  confirming,
  moving,
  keyrings,
  onAskDelete,
  onCancel,
  onDelete,
  onAskMove,
  onMove,
}: {
  count: number;
  busy: boolean;
  confirming: boolean;
  moving: boolean;
  keyrings: { id: string; name: { value: string } }[];
  onAskDelete: () => void;
  onCancel: () => void;
  onDelete: () => Promise<void>;
  onAskMove: () => void;
  onMove: (keyringId: string) => Promise<void>;
}) {
  if (count === 0) {
    return <p className="hint">Choose some passwords, or hold another to select it.</p>;
  }

  if (confirming) {
    return (
      <>
        <p className="status" data-tone="attn">
          <span>
            <b>
              Delete {count} password{count === 1 ? "" : "s"}?
            </b>
            <em>This cannot be undone on this device.</em>
          </span>
        </p>
        <button
          type="button"
          className="btn danger big"
          disabled={busy}
          onClick={() => void onDelete()}
        >
          {busy ? "Deleting…" : `Yes, delete ${count}`}
        </button>
        <button type="button" className="btn sec" disabled={busy} onClick={onCancel}>
          Keep them
        </button>
      </>
    );
  }

  if (moving) {
    return (
      <>
        <p className="hint">
          Move {count} password{count === 1 ? "" : "s"} to:
        </p>
        <div className="list">
          {keyrings.map((ring) => (
            <button
              key={ring.id}
              type="button"
              className="row"
              disabled={busy}
              onClick={() => void onMove(ring.id)}
            >
              <span className="rowtext">
                <b>{ring.name.value}</b>
              </span>
            </button>
          ))}
        </div>
        <button type="button" className="btn sec" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </>
    );
  }

  return (
    <div className="bulk-actions">
      <button type="button" className="btn sec" disabled={busy} onClick={onAskMove}>
        Move to…
      </button>
      <button type="button" className="btn danger" disabled={busy} onClick={onAskDelete}>
        Delete
      </button>
    </div>
  );
}
