import { useCallback, useEffect, useRef, useState } from "react";
import { AlertIcon, CheckIcon } from "./ui/icons";
import { ItemDetail } from "./screens/ItemDetail";
import { FileViewer } from "./screens/FileViewer";
import { ItemEdit } from "./screens/ItemEdit";
import { Grant } from "./screens/Grant";
import { AcceptShare } from "./screens/AcceptShare";
import { JoinShare } from "./screens/JoinShare";
import { ShareKeyring } from "./screens/ShareKeyring";
import { ShareMany } from "./screens/ShareMany";
import {
  parseJoinLink,
  parseOwnershipLink,
  parseResponseLink,
  stripShareLinkParams,
  type KeywebJoinLink,
} from "./vault/sharing/links";
import { decodeSharingDatasetFilesV1 } from "@keyneom/sync-kit/sharing";
import { datasetOf, itemField, liveKeyrings } from "@keyweb/vault-core";
import type { SharingPublicKeyResponseV1 } from "@keyneom/sync-kit/sharing";
import { Import } from "./screens/Import";
import { Keyrings } from "./screens/Keyrings";
import { Settings } from "./screens/Settings";
import { ScanCodes } from "./screens/ScanCodes";
import { RecoverySheet } from "./screens/RecoverySheet";
import { Unlock } from "./screens/Unlock";
import { VaultList } from "./screens/VaultList";
import { useDisplaySettings } from "./vault/useDisplaySettings";
import {
  describeLockAfter,
  readLockAfter,
  storeLockAfter,
  watchIdle,
  type LockAfter,
} from "./vault/idleLock";
import { useGeneratorRules } from "./vault/useGeneratorRules";
import { useVault } from "./vault/useVault";

type Route =
  | { name: "list" }
  | { name: "detail"; itemId: string }
  | { name: "edit"; itemId: string | null }
  | { name: "keyrings" }
  | { name: "settings" }
  | { name: "import" }
  | { name: "scan" }
  | { name: "share"; keyringId: string };

export function App() {
  const vault = useVault();
  const display = useDisplaySettings();
  const generator = useGeneratorRules();
  const [route, setRoute] = useState<Route>({ name: "list" });
  /** Keyrings picked to share together, while that is being arranged. */
  const [sharingMany, setSharingMany] = useState<string[] | null>(null);
  const [toast, setToast] = useState<{ text: string; tone: "safe" | "risk" } | null>(null);
  const notify = useCallback((text: string, tone: "safe" | "risk" = "safe") => {
    setToast({ text, tone });
  }, []);
  /**
   * A file being looked at, by the id of the item holding its bytes.
   *
   * Held rather than routed, so closing the viewer returns to the password
   * exactly as it was — including anything half-typed on the way there.
   */
  const [viewing, setViewing] = useState<string | null>(null);

  /**
   * Arriving from the phone to hand a Drive file over.
   *
   * Android has no native picker for `drive.file`, so it opens this page with
   * `?grant=import`. This is deliberately checked *before* the vault, and read
   * once into state rather than from the URL each render.
   *
   * Before the vault: the errand is to record a grant at Google against a Cloud
   * project and an account, which no vault is involved in. Putting the unlock
   * gate in front of it asked someone who has only ever used the phone to
   * create a second, empty vault — and to write down a recovery phrase for it
   * that looks exactly like the one that matters and protects nothing.
   *
   * Once into state: `dismiss` clears it so "use Keyweb in this browser" can
   * fall through to the normal app, and so a reload cannot reopen the handoff.
   */
  const [granting, setGranting] = useState(
    () =>
      typeof window !== "undefined" &&
      ["import", "share"].includes(
        new URLSearchParams(window.location.search).get("grant") ?? "",
      ),
  );

  /**
   * The shared keyring files the phone is asking to be handed over.
   *
   * The phone does the joining — it holds the vault — and needs this browser
   * only for the Picker, which is the one grant Google will accept from
   * nobody else. Named by file id, so the page cannot be talked into granting
   * something the phone never asked for.
   */
  const [grantFiles] = useState(() => {
    if (typeof window === "undefined") return undefined;
    const params = new URLSearchParams(window.location.search);
    if (params.get("grant") !== "share") return undefined;
    const encoded = params.get("sk-files");
    if (!encoded) return undefined;
    try {
      return decodeSharingDatasetFilesV1(encoded);
    } catch {
      return undefined;
    }
  });

  /**
   * A share link this page was opened with.
   *
   * Read once into state and then wiped from the address bar. Left there, a
   * reload would re-run the flow — and a share link sitting in the history of
   * a shared computer outlives the moment it was useful.
   *
   * Unlike the Drive grant handoff above, both of these need a vault: joining
   * puts a keyring into one, and accepting needs the invitation this device
   * sent. So they are held until the vault is open rather than shown in front
   * of it.
   */
  const [joining, setJoining] = useState<KeywebJoinLink | null>(() =>
    typeof window === "undefined" ? null : parseJoinLink(window.location.search),
  );
  const [accepting, setAccepting] = useState<SharingPublicKeyResponseV1 | null>(() =>
    typeof window === "undefined"
      ? null
      : (parseResponseLink(window.location.search)?.response ?? null),
  );

  /**
   * A keyring being handed over, from a link somebody sent.
   *
   * Applied as soon as the vault is open rather than shown as a screen to
   * confirm. There is nothing to decide: the person receiving it is already a
   * member, the artifact is signed by the current owner, and refusing it would
   * leave the keyring owned by somebody who has already decided to stop owning
   * it. The toast says what happened.
   */
  const [takingOver, setTakingOver] = useState<unknown | null>(() =>
    typeof window === "undefined" ? null : parseOwnershipLink(window.location.search),
  );

  useEffect(() => {
    if (takingOver === null || vault.phase !== "ready" || !vault.sharing) return;
    const payload = takingOver;
    setTakingOver(null);
    void vault.sharing
      .acceptOwnership(payload)
      .then(() => notify("That keyring is yours now. You can invite and remove people on it."))
      .catch((cause: unknown) =>
        notify(
          cause instanceof Error ? cause.message : "Keyweb couldn't take that keyring on.",
          "risk",
        ),
      );
  }, [takingOver, vault.phase, vault.sharing]);

  // Consumed from the address bar so a refresh does not reopen the handoff.
  useEffect(() => {
    if (granting) window.history.replaceState({}, "", window.location.pathname);
  }, [granting]);

  useEffect(() => {
    if (!joining && !accepting && takingOver === null) return;
    window.history.replaceState({}, "", stripShareLinkParams(new URL(window.location.href)));
    // Once: the parameters are already in state, and re-running would only
    // rewrite an address bar that no longer has them.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Lock after a while unused.
   *
   * Watched only while the vault is open, and restarted whenever the setting
   * changes. What was on screen goes with it: coming back to the lock screen
   * and unlocking should land on the list, not on the password somebody left
   * showing.
   */
  const [lockAfter, setLockAfterState] = useState<LockAfter>(readLockAfter);
  const setLockAfter = useCallback((value: LockAfter) => {
    setLockAfterState(value);
    storeLockAfter(value);
  }, []);
  /** Why the lock screen is showing, when Keyweb locked itself. */
  const [lockedIdle, setLockedIdle] = useState<LockAfter | null>(null);
  const { phase, lock } = vault;
  useEffect(() => {
    if (phase !== "ready") return;
    setLockedIdle(null);
    return watchIdle({
      timeoutMs: Number(lockAfter) * 60_000,
      onIdle: () => {
        lock();
        setRoute({ name: "list" });
        setViewing(null);
        setLockedIdle(lockAfter);
      },
    });
  }, [phase, lock, lockAfter]);

  useEffect(() => {
    if (toast === null) return;
    const timer = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(timer);
  }, [toast]);

  /**
   * Ask for the passkey without being asked to ask.
   *
   * Opening Keyweb is already the request, and the only way past the lock
   * screen is this prompt, so making someone press a button first is asking
   * twice. Fired once, guarded by a ref rather than by the phase: the phase
   * returns to "locked" after a dismissal, and keying on it would summon the
   * prompt again the instant it was dismissed.
   *
   * Not on first run, where the prompt creates the key rather than checking
   * one, and the screen explaining that should be read before a browser dialog
   * covers it. Not on the grant handoff either — that path needs no vault.
   */
  const promptedOnEntry = useRef(false);
  useEffect(() => {
    if (granting || promptedOnEntry.current) return;
    if (vault.phase !== "locked" || vault.firstRun || vault.codeOnly) return;
    promptedOnEntry.current = true;
    void vault.unlock({ quiet: true });
  }, [granting, vault]);

  if (granting) {
    return (
      <main className="app">
        <Grant
          onContinue={() => setGranting(false)}
          {...(grantFiles ? { sharedFiles: grantFiles } : {})}
        />
      </main>
    );
  }

  if (vault.phase === "checking") {
    return (
      <main className="app">
        <p className="empty">Opening your vault…</p>
      </main>
    );
  }

  if (vault.phase !== "ready") {
    return (
      <main className="app">
        <Unlock
          phase={vault.phase}
          firstRun={vault.firstRun}
          codeOnly={vault.codeOnly}
          error={vault.error}
          notice={
            lockedIdle
              ? `Keyweb locked itself after ${describeLockAfter(lockedIdle)} without being used.`
              : null
          }
          backupConfigured={vault.backupConfigured}
          onUnlock={() => void vault.unlock()}
          onRestore={() => void vault.restore()}
          onRestoreWithCode={(code) => void vault.restoreWithCode(code)}
          onRestoreFromFile={(text, code) => void vault.restoreFromFile(text, code)}
          contents={vault.accountContents}
          files={vault.backupFiles}
          onChooseFile={(fileId) => void vault.chooseBackupFile(fileId)}
        />
      </main>
    );
  }

  // A newly minted recovery code takes precedence over everything: it exists
  // in readable form exactly once, so it must not be possible to navigate past
  // it by accident.
  if (vault.newRecoveryCode) {
    return (
      <main className="app">
        <RecoverySheet code={vault.newRecoveryCode} onDone={vault.dismissRecoveryCode} />
      </main>
    );
  }

  // The two link landings, ahead of the ordinary screens: somebody who opened
  // a share link came here to finish it, not to browse their passwords.
  if (joining && vault.sharing) {
    return (
      <main className="app">
        <JoinShare
          invite={joining}
          sharing={vault.sharing}
          onToast={notify}
          onDone={() => setJoining(null)}
        />
      </main>
    );
  }

  if (accepting && vault.sharing) {
    return (
      <main className="app">
        <AcceptShare
          response={accepting}
          sharing={vault.sharing}
          onDone={() => setAccepting(null)}
        />
      </main>
    );
  }

  /*
   * A keyring that is really there, not merely the first key in the map.
   *
   * `Object.keys(...)[0]` could be a keyring deleted on another device, and
   * `?? "personal"` invented an id for a keyring this vault might never have
   * had. Either one became the default on the add screen — where the dropdown
   * silently shows its first option when its value matches none of them, so
   * the keyring somebody read off the screen was not the one their password
   * was saved to. Empty is honest when there is nothing; the save path makes a
   * keyring rather than writing to a name nobody chose.
   */
  const firstKeyring = liveKeyrings(vault.state)[0]?.id ?? "";
  const current =
    route.name === "detail" || (route.name === "edit" && route.itemId)
      ? (vault.state.items[route.name === "detail" ? route.itemId : route.itemId!] ?? null)
      : null;

  return (
    <main className="app">
      {route.name === "list" && (
        <VaultList
          state={vault.state}
          items={vault.items}
          status={vault.status}
          backupConfigured={vault.backupConfigured}
          recoveryNeedsCode={vault.recoveryNeedsCode}
          onAdoptCode={vault.adoptRecoveryCode}
          onOpen={(itemId) => setRoute({ name: "detail", itemId })}
          onAdd={() => setRoute({ name: "edit", itemId: null })}
          onManageKeyrings={() => setRoute({ name: "keyrings" })}
          onSettings={() => setRoute({ name: "settings" })}
          onDeleteMany={async (itemIds) => {
            await vault.deleteItems(itemIds);
            notify(
              `${itemIds.length} password${itemIds.length === 1 ? "" : "s"} deleted.`,
            );
          }}
          onSync={() => void vault.syncNow()}
          onMoveMany={async (itemIds, keyringId) => {
            await vault.moveItems(itemIds, keyringId);
            const name = vault.state.keyrings[keyringId]?.name.value ?? "that keyring";
            notify(
              `${itemIds.length} password${itemIds.length === 1 ? "" : "s"} moved to ${name}.`,
            );
          }}
          blockedJoins={vault.blockedJoins}
          onAdoptBlocked={async (datasetId) => {
            try {
              await vault.adoptBlockedJoin(datasetId);
              notify("That keyring was added on its own, separate from yours.");
            } catch (cause) {
              notify(
                cause instanceof Error ? cause.message : "Keyweb couldn't add that keyring.",
                "risk",
              );
            }
          }}
        />
      )}

      {route.name === "detail" && current && viewing && vault.state.items[viewing] && (
        <FileViewer
          name={itemField(vault.state.items[viewing]!, "name") ?? "File"}
          type={itemField(vault.state.items[viewing]!, "type") ?? ""}
          data={itemField(vault.state.items[viewing]!, "secret:data") ?? ""}
          bytes={Number(itemField(vault.state.items[viewing]!, "size") ?? "0")}
          onClose={() => setViewing(null)}
        />
      )}

      {route.name === "detail" && current && !viewing && (
        <ItemDetail
          item={current}
          state={vault.state}
          onOpenFile={setViewing}
          onAttach={(file) => vault.attachFile(current.id, file)}
          onRemoveFile={async (blobId) => {
            await vault.removeAttachment(current.id, blobId);
            notify("That file was removed from your vault.");
          }}
          onRestore={(field, value) => {
            void vault
              .saveItem({
                itemId: current.id,
                keyringId: current.keyring.value,
                fields: { [field]: value },
              })
              .then(() => notify("Put back. The value it replaced is in the list too."));
          }}
          onBack={() => setRoute({ name: "list" })}
          onEdit={() => setRoute({ name: "edit", itemId: current.id })}
          onDelete={() => {
            void vault.deleteItem(current.id).then(() => {
              notify(`${current.fields.title?.value ?? "That password"} was deleted.`);
              setRoute({ name: "list" });
            });
          }}
          onCopied={notify}
        />
      )}

      {route.name === "edit" && (
        <ItemEdit
          item={current}
          state={vault.state}
          defaultKeyringId={firstKeyring}
          savedRules={generator.savedRules}
          lastRules={generator.lastRules}
          onSaveRules={generator.saveRules}
          onRulesUsed={generator.rememberLastRules}
          readOnlyKeyrings={vault.readOnlyKeyrings}
          onBack={() => setRoute({ name: "list" })}
          onSave={async (input) => {
            /*
             * The toast now reports what happened rather than what was
             * attempted. It used to say "Saved on this device" whether or not
             * anything had been — a locked vault returned quietly and a
             * password aimed at a missing keyring disappeared — and the screen
             * went back to a list that did not contain it.
             */
            try {
              const saved = await vault.saveItem(input);
              notify(
                saved.keyringId === input.keyringId
                  ? "Saved on this device."
                  : `Saved in ${saved.keyringName}, because the keyring you chose is no longer there.`,
              );
              /*
               * To the password, not back to the list.
               *
               * The list is filtered and folded: it can be scoped to one
               * keyring, sitting inside a folder, or showing folders at the
               * top rather than passwords. Returning to it after a save meant
               * the thing somebody had just written was routinely not on the
               * screen they were returned to, which is indistinguishable from
               * it not having been saved. Showing the saved password answers
               * the only question they have at that moment.
               */
              setRoute({ name: "detail", itemId: saved.itemId });
            } catch (cause) {
              notify(
                cause instanceof Error ? cause.message : "That password was not saved.",
                "risk",
              );
            }
          }}
        />
      )}

      {route.name === "share" &&
        vault.sharing &&
        vault.state.keyrings[route.keyringId] && (
          <ShareKeyring
            keyring={vault.state.keyrings[route.keyringId]!}
            datasetId={datasetOf(vault.state.keyrings[route.keyringId])}
            sharing={vault.sharing}
            onToast={notify}
            onBack={() => setRoute({ name: "keyrings" })}
          />
        )}

      {/*
        Over the keyrings rather than instead of them: what is behind the sheet
        is the list of what is being shared, which is what somebody wants in
        view while they decide who to send it to.
      */}
      {sharingMany !== null && vault.sharing && (
        <ShareMany
          names={sharingMany.map(
            (id) => vault.state.keyrings[id]?.name.value ?? "A keyring",
          )}
          onInvite={async (email, role) => {
            const sharing = vault.sharing!;
            const { link } = await sharing.shareKeyrings({
              keyringIds: sharingMany,
              email,
              role,
            });
            return link;
          }}
          onCopy={async (link) => {
            await navigator.clipboard.writeText(link);
            notify("The link is on your clipboard. Send it to them.");
          }}
          onClose={() => setSharingMany(null)}
        />
      )}

      {route.name === "keyrings" && (
        <Keyrings
          state={vault.state}
          items={vault.items}
          canShare={vault.sharing !== null}
          onShare={(keyringId) => setRoute({ name: "share", keyringId })}
          onShareMany={setSharingMany}
          sharedKeyrings={vault.sharedKeyrings}
          onBack={() => setRoute({ name: "list" })}
          onAdd={async (name) => {
            await vault.addKeyring(name);
            notify(`The ${name} keyring is ready.`);
          }}
          onDelete={async (keyringId) => {
            const name = vault.state.keyrings[keyringId]?.name.value ?? "That keyring";
            await vault.deleteKeyring(keyringId);
            notify(`${name} was deleted.`);
          }}
        />
      )}

      {route.name === "settings" && (
        <Settings
          textSize={display.textSize}
          appearance={display.appearance}
          onTextSize={display.setTextSize}
          onAppearance={display.setAppearance}
          lockAfter={lockAfter}
          onLockAfter={setLockAfter}
          onBack={() => setRoute({ name: "list" })}
          onImport={() => setRoute({ name: "import" })}
          onScanCodes={() => setRoute({ name: "scan" })}
          onLock={() => {
            vault.lock();
            setRoute({ name: "list" });
          }}
          describeBackupFile={vault.describeBackupFile}
          backupFiles={vault.backupFiles}
          onRefreshBackupFiles={vault.refreshBackupFiles}
          onChooseBackupFile={vault.chooseBackupFile}
          onDeleteBackupFile={vault.deleteBackupFile}
          onSaveBackup={vault.saveBackupFile}
          state={vault.state}
          sharing={vault.sharing}
          recoveryKeys={vault.backupConfigured && vault.sharing ? vault.recoveryKeys : null}
          onTurnOnRecoveryKeys={vault.turnOnRecoveryKeys}
          onMakeRecoveryCode={vault.makeRecoveryCode}
          onAdoptRecoveryCode={vault.adoptRecoveryCode}
        />
      )}

      {route.name === "scan" && (
        <ScanCodes
          state={vault.state}
          onBack={() => setRoute({ name: "settings" })}
          onAdd={vault.addScannedCodes}
        />
      )}

      {route.name === "import" && (
        <Import
          keyrings={Object.values(vault.state.keyrings)
            .filter((ring) => !ring.deleted.value)
            .map((ring) => ({ id: ring.id, name: ring.name.value }))}
          onBack={() => setRoute({ name: "list" })}
          onImport={(preview, ungrouped) => vault.importKeePass(preview, ungrouped)}
        />
      )}

      {toast && (
        <p className="status toast" data-tone={toast.tone} role="status">
          {toast.tone === "risk" ? <AlertIcon /> : <CheckIcon />}
          <span>
            <b>{toast.text}</b>
          </span>
        </p>
      )}
    </main>
  );
}
