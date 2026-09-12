import { useEffect, useState } from "react";
import { CheckIcon } from "./ui/icons";
import { ItemDetail } from "./screens/ItemDetail";
import { ItemEdit } from "./screens/ItemEdit";
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

  useEffect(() => {
    if (toast === null) return;
    const timer = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(timer);
  }, [toast]);

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
          onBack={() => setRoute({ name: "list" })}
          onImport={(preview) => vault.importKeePass(preview)}
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
