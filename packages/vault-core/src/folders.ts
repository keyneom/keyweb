import { itemField, type ItemRecord, type VaultState } from "./model.js";

/**
 * The folders inside a keyring.
 *
 * ## Why this is a view rather than a structure
 *
 * An item's `folder` field has always held the full nested path it came in
 * with — "Leslie / Banks" stays distinct from "Mika / Banks", and the import
 * has never collapsed them. What was missing is that nothing ever *read* it:
 * the path was preserved, synced, backed up and shown nowhere, so an organised
 * KeePass database arrived looking like one flat list of two hundred things.
 *
 * Keeping it as a field rather than promoting folders to records of their own
 * is deliberate. A folder with no identity cannot be renamed out from under an
 * item on another device, cannot be deleted while something is still in it,
 * and cannot disagree with the item about where the item is. Moving an item
 * between folders stays one ordinary per-field write that merges like every
 * other one. An empty folder simply does not exist, which is the right answer
 * for something that is only ever a way of finding things.
 *
 * ## Keyrings are not folders
 *
 * A keyring is a unit of sharing; a folder is a way of finding things. The
 * import makes the top-level group a keyring *and* leaves it at the head of
 * the path, so the first segment is dropped when it repeats the keyring's own
 * name — otherwise every vault opens onto a folder that contains everything
 * and is named after the thing you just opened.
 */

/** How a nested path is written into the one `folder` field. */
export const FOLDER_SEPARATOR = " / ";

/** This item's folder path, relative to the keyring it is on. */
export function folderPath(item: ItemRecord, state: VaultState): string[] {
  const raw = itemField(item, "folder") ?? "";
  const segments = raw
    .split(FOLDER_SEPARATOR)
    .map((part) => part.trim())
    .filter((part) => part !== "");
  const keyring = state.keyrings[item.keyring.value]?.name.value;
  if (keyring !== undefined && segments[0] === keyring) return segments.slice(1);
  return segments;
}

export type FolderChild = {
  name: string;
  /** The full path to this folder, for descending into it. */
  path: string[];
  /** Everything beneath it, not only what sits directly inside. */
  count: number;
};

/**
 * What to show at one place in the tree.
 *
 * `folders` counts recursively, because a folder showing "0" that opens onto
 * three subfolders full of passwords is a folder nobody opens.
 */
export function browseFolders(
  items: ItemRecord[],
  state: VaultState,
  at: string[],
): { folders: FolderChild[]; items: ItemRecord[] } {
  const here: ItemRecord[] = [];
  const counts = new Map<string, number>();

  for (const item of items) {
    const path = folderPath(item, state);
    if (!startsWith(path, at)) continue;
    if (path.length === at.length) {
      here.push(item);
      continue;
    }
    const next = path[at.length]!;
    counts.set(next, (counts.get(next) ?? 0) + 1);
  }

  const folders = [...counts.entries()]
    .map(([name, count]) => ({ name, path: [...at, name], count }))
    .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));

  return { folders, items: here };
}

/** Every folder that exists anywhere in these items, for a picker. */
export function allFolders(items: ItemRecord[], state: VaultState): string[][] {
  const seen = new Map<string, string[]>();
  for (const item of items) {
    const path = folderPath(item, state);
    for (let depth = 1; depth <= path.length; depth += 1) {
      const slice = path.slice(0, depth);
      seen.set(slice.join(FOLDER_SEPARATOR), slice);
    }
  }
  return [...seen.values()].sort((a, b) =>
    a.join(FOLDER_SEPARATOR).toLowerCase().localeCompare(b.join(FOLDER_SEPARATOR).toLowerCase()),
  );
}

function startsWith(path: string[], prefix: string[]): boolean {
  if (path.length < prefix.length) return false;
  return prefix.every((part, index) => path[index] === part);
}
