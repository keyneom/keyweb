import { useEffect, useRef, useState } from "react";
import { CheckIcon } from "./ui/icons";
import { ItemDetail } from "./screens/ItemDetail";
import { ItemEdit } from "./screens/ItemEdit";
import { Grant } from "./screens/Grant";
import { Import } from "./screens/Import";
import { Keyrings } from "./screens/Keyrings";
import { Settings } from "./screens/Settings";
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
  | { name: "import" };

export function App() {
  const vault = useVault();
  const display = useDisplaySettings();
  const generator = useGeneratorRules();
  const [route, setRoute] = useState<Route>({ name: "list" });
  const [toast, setToast] = useState<string | null>(null);

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
      new URLSearchParams(window.location.search).get("grant") === "import",
  );

  // Consumed from the address bar so a refresh does not reopen the handoff.
  useEffect(() => {
    if (granting) window.history.replaceState({}, "", window.location.pathname);
  }, [granting]);

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
        <Grant onContinue={() => setGranting(false)} />
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

      {route.name === "detail" && current && (
        <ItemDetail
          item={current}
          state={vault.state}
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
          onBack={() => setRoute({ name: "list" })}
          onSave={async (input) => {
            await vault.saveItem(input);
            setToast("Saved on this device.");
            setRoute({ name: "list" });
          }}
        />
      )}

      {route.name === "keyrings" && (
        <Keyrings
          state={vault.state}
          items={vault.items}
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
