import { useCallback, useMemo, useState } from "react";
import {
  browseFolders,
  keyringLabel,
  itemsWithoutKeyring,
  ITEM_SORTS,
  itemField,
  sortItems,
  type ItemRecord,
  type ItemSort,
  type VaultState,
} from "@keyweb/vault-core";
import { AlertIcon, KeyIcon, PlusIcon, SearchIcon } from "../ui/icons";
import { StatusLine } from "../ui/StatusLine";
import { SortPicker } from "../ui/SortPicker";
import { useRememberedSort } from "../vault/useRememberedSort";
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
  const [sort, setSort] = useRememberedSort<ItemSort>("item-sort", "name-az");
  /** Where in the folder tree the list is looking. */
  const [folder, setFolder] = useState<string[]>([]);
  /**
   * Browsing the tree, or looking at every password at once.
   *
   * Two genuinely different questions — "what is in here" and "where is this
   * one thing" — and a list that only answers the first makes the second take
   * a walk through folders somebody did not build. Both keep the keyring chips
   * and the ordering, so a filter is a filter either way.
   */
  const [flat, setFlat] = useRememberedSort<"folders" | "flat">("browse-mode", "folders");

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

  /*
   * Passwords with no keyring, and the offer to fix it.
   *
   * These used to be filtered out of every list on both platforms, so a
   * password saved against a keyring that had been deleted — or one that had
   * simply not arrived on this device yet — was in the vault, in the backup
   * and on the phone, and on no screen anywhere. Now they are in the list like
   * anything else, and this says out loud that they want a home, because a row
   * that reads "Not in a keyring" with no way to act on it is only half an
   * answer.
   */
  const homeless = useMemo(() => itemsWithoutKeyring(state), [state]);

  const matching = useMemo(() => {
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
      });
  }, [items, query, ring]);

  // Filtering decides what is in the list; ordering decides where in it to
  // look. Two steps, so "select all" cannot mean something different from
  // what is on screen.
  const shown = useMemo(() => sortItems(matching, sort), [matching, sort]);

  /*
   * Searching looks everywhere, on purpose.
   *
   * Somebody who types a name is asking "where is this", and answering only
   * from the folder they happen to be standing in is how a search reports that
   * a password they can see in the list does not exist.
   */
  const searching = query.trim() !== "";
  const view = useMemo(
    () =>
      searching || flat === "flat"
        ? { folders: [], items: shown }
        : browseFolders(shown, state, folder, ring),
    [searching, flat, shown, state, folder, ring],
  );

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
                // What is on screen, not everything the filters let through.
                // Inside a folder those differ, and "select all" meaning "also
                // the ones you cannot see" is how somebody deletes a keyring by
                // mistake.
                selected.size === view.items.length
                  ? new Set()
                  : new Set(view.items.map((item) => item.id)),
              )
            }
          >
            {selected.size === view.items.length ? "Clear" : "Select all"}
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
          onClick={() => {
            setRing(null);
            setFolder([]);
          }}
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
              onClick={() => {
                setRing(r.id === ring ? null : r.id);
                // The path means nothing in another keyring, and keeping it
                // would land somebody in a folder that is empty because it is
                // somebody else's.
                setFolder([]);
              }}
            >
              <i style={{ background: ringColor(state, r.id) }} />
              {r.name.value} {count}
            </button>
          );
        })}
      </div>

      {homeless.length > 0 && !selecting && (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>
              {homeless.length} password{homeless.length === 1 ? " is" : "s are"} not in a
              keyring.
            </b>
            <em>
              {homeless.length === 1 ? "It is" : "They are"} safe and backed up — the keyring{" "}
              {homeless.length === 1 ? "it was" : "they were"} saved to is gone. Put{" "}
              {homeless.length === 1 ? "it" : "them"} somewhere you will find{" "}
              {homeless.length === 1 ? "it" : "them"} again.
            </em>
            <button
              type="button"
              className="btn sec"
              onClick={() => {
                setSelected(new Set(homeless.map((item) => item.id)));
                setMoving(true);
              }}
            >
              Put {homeless.length === 1 ? "it" : "them"} in a keyring
            </button>
          </span>
        </p>
      )}

      {/*
        Under the filters rather than beside the search box: filtering narrows
        what is in the list and ordering decides where in it to look, and
        reading them in that order matches doing them in it.
      */}
      <div className="browserow">
        <SortPicker label="Order" value={sort} options={ITEM_SORTS} onChange={setSort} />
        {/*
          Two buttons rather than one whose label is its state: "In folders" on
          a single toggle could mean "you are" or "make it so", and there is no
          way to tell from looking.
        */}
        <div className="rings" role="group" aria-label="How to browse">
          <button
            type="button"
            className="ring"
            aria-pressed={flat === "folders"}
            onClick={() => setFlat("folders")}
          >
            In folders
          </button>
          <button
            type="button"
            className="ring"
            aria-pressed={flat === "flat"}
            onClick={() => {
              setFlat("flat");
              setFolder([]);
            }}
          >
            Everything
          </button>
        </div>
      </div>

      <StatusLine
        status={status}
        backupConfigured={backupConfigured}
        onSync={onSync}
        syncing={status.syncing}
      />

      {/*
        Where you are, and the way back up.

        A breadcrumb rather than a back button, because the levels above are
        each one click away — going from "Leslie / Banks / Cards" to the top
        should not be three gestures and a guess about how deep you were.
      */}
      {!searching && flat === "folders" && folder.length > 0 && (
        <nav className="crumbs" aria-label="Folders">
          <button type="button" className="linkish" onClick={() => setFolder([])}>
            All
          </button>
          {folder.map((name, index) => (
            <span key={folder.slice(0, index + 1).join("/")}>
              <span aria-hidden="true"> / </span>
              <button
                type="button"
                className="linkish"
                onClick={() => setFolder(folder.slice(0, index + 1))}
              >
                {name}
              </button>
            </span>
          ))}
        </nav>
      )}

      {view.folders.length === 0 && view.items.length === 0 ? (
        <p className="empty">
          {items.length === 0
            ? "No passwords saved yet. Add your first one below."
            : searching
              ? "Nothing matches that search."
              : folder.length > 0
                ? "This folder is empty."
                : "Nothing here."}
        </p>
      ) : (
        <div className="list">
          {/* Folders first, because a folder is a place and the things at this
              level are its contents. */}
          {view.folders.map((child) => {
            // A keyring is not a folder somebody made, so it keeps its own
            // colour and its own word rather than wearing a folder icon that
            // implies it could be renamed or nested.
            const ringId =
              child.kind === "keyring"
                ? Object.values(state.keyrings).find(
                    (r) => !r.deleted.value && r.name.value === child.name,
                  )?.id
                : undefined;
            return (
              <button
                key={child.path.join("/")}
                type="button"
                className="row"
                onClick={() => setFolder(child.path)}
              >
                {ringId ? (
                  <i
                    className="avatar"
                    aria-hidden="true"
                    style={{ background: ringColor(state, ringId) }}
                  />
                ) : (
                  <i className="avatar" aria-hidden="true">
                    &#128193;
                  </i>
                )}
                <span style={{ flex: 1, minWidth: 0, textAlign: "left" }}>
                  <span style={{ display: "block" }}>{child.name}</span>
                  <span className="hint">
                    {child.kind === "keyring" ? "Keyring · " : ""}
                    {child.count} password{child.count === 1 ? "" : "s"}
                  </span>
                </span>
              </button>
            );
          })}
          {view.items.map((item) => (
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
  // Named through `keyringLabel`, so a keyring that was deleted reads the
  // same as one that was never there rather than sending somebody looking for
  // a keyring that is gone.
  const ringName = keyringLabel(state, item.keyring.value);
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
          {ringName}
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
