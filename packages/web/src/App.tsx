import { useEffect, useRef, useState } from "react";
import { CheckIcon } from "./ui/icons";
import { ItemDetail } from "./screens/ItemDetail";
import { FileViewer } from "./screens/FileViewer";
import { ItemEdit } from "./screens/ItemEdit";
import { Grant } from "./screens/Grant";
import { AcceptShare } from "./screens/AcceptShare";
import { JoinShare } from "./screens/JoinShare";
import { ShareKeyring } from "./screens/ShareKeyring";
import {
  parseJoinLink,
  parseOwnershipLink,
  parseResponseLink,
  stripShareLinkParams,
  type KeywebJoinLink,
} from "./vault/sharing/links";
import { decodeSharingDatasetFilesV1 } from "@keyneom/sync-kit/sharing";
import { datasetOf, itemField } from "@keyweb/vault-core";
import type { SharingPublicKeyResponseV1 } from "@keyneom/sync-kit/sharing";
import { Import } from "./screens/Import";
import { Keyrings } from "./screens/Keyrings";
import { Settings } from "./screens/Settings";
import { ScanCodes } from "./screens/ScanCodes";
import { RecoverySheet } from "./screens/RecoverySheet";
import { Unlock } from "./screens/Unlock";
import { VaultList } from "./screens/VaultList";
import { useDisplaySettings } from "./vault/useDisplaySettings";
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
  const [toast, setToast] = useState<string | null>(null);
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
      .then(() => setToast("That keyring is yours now. You can invite and remove people on it."))
      .catch((cause: unknown) =>
        setToast(cause instanceof Error ? cause.message : "Keyweb couldn't take that keyring on."),
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
    if (vault.phase !== "locked" || vault.firstRun) return;
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
          error={vault.error}
          backupConfigured={vault.backupConfigured}
          onUnlock={() => void vault.unlock()}
          onRestore={() => void vault.restore()}
          onRestoreWithCode={(code) => void vault.restoreWithCode(code)}
          contents={vault.accountContents}
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
          onToast={setToast}
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

  const firstKeyring = Object.keys(vault.state.keyrings)[0] ?? "personal";
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
          onOpen={(itemId) => setRoute({ name: "detail", itemId })}
          onAdd={() => setRoute({ name: "edit", itemId: null })}
          onManageKeyrings={() => setRoute({ name: "keyrings" })}
          onSettings={() => setRoute({ name: "settings" })}
          onDeleteMany={async (itemIds) => {
            await vault.deleteItems(itemIds);
            setToast(
              `${itemIds.length} password${itemIds.length === 1 ? "" : "s"} deleted.`,
            );
          }}
          onSync={() => void vault.syncNow()}
          onMoveMany={async (itemIds, keyringId) => {
            await vault.moveItems(itemIds, keyringId);
            const name = vault.state.keyrings[keyringId]?.name.value ?? "that keyring";
            setToast(
              `${itemIds.length} password${itemIds.length === 1 ? "" : "s"} moved to ${name}.`,
            );
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
            setToast("That file was removed from your vault.");
          }}
          onRestore={(field, value) => {
            void vault
              .saveItem({
                itemId: current.id,
                keyringId: current.keyring.value,
                fields: { [field]: value },
              })
              .then(() => setToast("Put back. The value it replaced is in the list too."));
          }}
          onBack={() => setRoute({ name: "list" })}
          onEdit={() => setRoute({ name: "edit", itemId: current.id })}
          onDelete={() => {
            void vault.deleteItem(current.id).then(() => {
              setToast(`${current.fields.title?.value ?? "That password"} was deleted.`);
              setRoute({ name: "list" });
            });
          }}
          onCopied={setToast}
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
            await vault.saveItem(input);
            setToast("Saved on this device.");
            setRoute({ name: "list" });
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
            onToast={setToast}
            onBack={() => setRoute({ name: "keyrings" })}
          />
        )}

      {route.name === "keyrings" && (
        <Keyrings
          state={vault.state}
          items={vault.items}
          canShare={vault.sharing !== null}
          onShare={(keyringId) => setRoute({ name: "share", keyringId })}
          onBack={() => setRoute({ name: "list" })}
          onAdd={async (name) => {
            await vault.addKeyring(name);
            setToast(`The ${name} keyring is ready.`);
          }}
          onDelete={async (keyringId) => {
            const name = vault.state.keyrings[keyringId]?.name.value ?? "That keyring";
            await vault.deleteKeyring(keyringId);
            setToast(`${name} was deleted.`);
          }}
        />
      )}

      {route.name === "settings" && (
        <Settings
          textSize={display.textSize}
          appearance={display.appearance}
          onTextSize={display.setTextSize}
          onAppearance={display.setAppearance}
          onBack={() => setRoute({ name: "list" })}
          onImport={() => setRoute({ name: "import" })}
          onScanCodes={() => setRoute({ name: "scan" })}
          onLock={() => {
            vault.lock();
            setRoute({ name: "list" });
          }}
          state={vault.state}
          sharing={vault.sharing}
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
        <p className="status toast" data-tone="safe" role="status">
          <CheckIcon />
          <span>
            <b>{toast}</b>
          </span>
        </p>
      )}
    </main>
  );
}
