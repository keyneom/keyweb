import { useCallback, useEffect, useState } from "react";
import type { KeyringRecord } from "@keyweb/vault-core";
import { AlertIcon, BackIcon, CheckIcon, CopyIcon, ShieldIcon } from "../ui/icons";
import type { Member, PendingInvite, ShareRole, SharingApi } from "../vault/useVault";

/**
 * Who can see one keyring.
 *
 * One screen rather than two, because "share it with Rachel" and "who has this
 * already" are the same question asked at different moments, and a person who
 * has just sent an invitation should see it sitting there waiting rather than
 * having to go and look for it somewhere else.
 *
 * The copy avoids promising more than the cryptography delivers. Sharing hands
 * over passwords, and taking access away later cannot take back what somebody
 * already read — so the screen says that where it matters, at the moment of
 * revoking, rather than in small print nobody reaches.
 */
export function ShareKeyring({
  keyring,
  datasetId,
  sharing,
  onBack,
  onToast,
}: {
  keyring: KeyringRecord;
  /** Null until the keyring has been shared once and has its own file. */
  datasetId: string | null;
  sharing: SharingApi;
  onBack: () => void;
  onToast: (message: string) => void;
}) {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<ShareRole>("viewer");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [members, setMembers] = useState<Member[] | null>(null);
  /**
   * What this person may do with this keyring, once Drive has been asked.
   *
   * Undefined while unknown, which is a real state and not a loading spinner:
   * answering it needs a passkey and a network round trip, so the screen opens
   * in the shape that is safe for either — no promises, no destructive buttons
   * — and fills in when the answer arrives.
   */
  const [mine, setMine] = useState<boolean | null>(null);
  const [pending, setPending] = useState<PendingInvite[]>([]);
  const [revoking, setRevoking] = useState<Member | null>(null);

  const reload = useCallback(async () => {
    setPending(await sharing.pendingInvites(keyring.id));
    if (!datasetId) {
      setMembers(null);
      return;
    }
    try {
      const list = await sharing.members(datasetId);
      setMembers(list);
      setMine(list.find((member) => member.you)?.role === "owner");
    } catch {
      // Not knowing who has access is worth saying nothing about until the
      // person asks for something that needs it. The list is a read of Drive,
      // and Drive is allowed to be away.
      setMembers(null);
      setMine(null);
    }
  }, [datasetId, keyring.id, sharing]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function invite() {
    const address = email.trim();
    if (!address || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await sharing.shareKeyring({ keyringId: keyring.id, email: address, role });
      setLink(result.link);
      setEmail("");
      await reload();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't set that up.");
    } finally {
      setBusy(false);
    }
  }

  async function copy(value: string, what: string) {
    await navigator.clipboard.writeText(value);
    onToast(`${what} copied. Paste it into a message to them.`);
  }

  const others = (members ?? []).filter((member) => !member.you);
  const you = (members ?? []).find((member) => member.you);

  // A keyring somebody else shared. There is nothing here to invite anyone to
  // and nothing to revoke — the only thing this person can decide is whether
  // to keep carrying it.
  if (mine === false) {
    return (
      <>
        <header className="topbar">
          <button type="button" className="iconbtn" onClick={onBack}>
            <BackIcon />
            Back
          </button>
        </header>

        <h1 className="screen-title">{keyring.name.value}</h1>
        <p className="screen-sub">
          Somebody shared this keyring with you.{" "}
          {you?.role === "viewer"
            ? "You can see the passwords in it. You can't change them."
            : "You can see the passwords in it, and add and change them."}
        </p>

        {others.length > 0 && (
          <>
            <h2 className="import-heading">Other people who can see it</h2>
            <div className="list">
              {others.map((member) => (
                <div key={member.keyId} className="row" style={{ cursor: "default" }}>
                  <span className="avatar" aria-hidden="true">
                    {member.role === "owner" ? "★" : "●"}
                  </span>
                  <span className="rowtext">
                    <b>{member.email ?? `Key ${member.fingerprint}`}</b>
                    <span>
                      {member.role === "owner"
                        ? "Shared it with you"
                        : member.role === "viewer"
                          ? "Can look, can't change"
                          : "Can add and change"}
                    </span>
                  </span>
                </div>
              ))}
            </div>
          </>
        )}

        <p className="status" data-tone="calm">
          <ShieldIcon />
          <span>
            <b>These passwords aren't yours to delete.</b>
            <em>
              Removing this keyring takes it off this vault only. The person who shared it keeps
              their copy, and so does everyone else they shared it with.
            </em>
          </span>
        </p>

        <button
          type="button"
          className="btn sec"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await sharing.leave(keyring.id);
              onToast(`${keyring.name.value} was removed from this vault.`);
              onBack();
            } finally {
              setBusy(false);
            }
          }}
        >
          Remove it from my vault
        </button>
      </>
    );
  }

  return (
    <>
      <header className="topbar">
        <button type="button" className="iconbtn" onClick={onBack}>
          <BackIcon />
          Back
        </button>
      </header>

      <h1 className="screen-title">Share {keyring.name.value}</h1>
      <p className="screen-sub">
        Everyone you share this keyring with sees every password in it, and any you add later.
      </p>

      {link !== null && (
        <div className="confirm">
          <p className="status" data-tone="safe">
            <CheckIcon />
            <span>
              <b>Send them this link.</b>
              <em>
                They open it, choose the keyring in Google's file chooser, and send you a reply
                link back. You are not finished until you have opened their reply.
              </em>
            </span>
          </p>
          <button type="button" className="btn pri big" onClick={() => void copy(link, "The link")}>
            <CopyIcon />
            Copy the link
          </button>
          <button type="button" className="btn sec" onClick={() => setLink(null)}>
            Done
          </button>
        </div>
      )}

      {error && (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}

      {others.length > 0 && (
        <>
          <h2 className="import-heading">People who can see it</h2>
          <div className="list">
            {others.map((member) => (
              <div key={member.keyId} className="row" style={{ cursor: "default" }}>
                <span className="avatar" aria-hidden="true">
                  {(member.email ?? "?").slice(0, 1).toUpperCase()}
                </span>
                <span className="rowtext">
                  <b>{member.email ?? "Someone you shared with"}</b>
                  <span>
                    {member.role === "viewer" ? "Can look, can't change" : "Can add and change"} ·
                    key {member.fingerprint}
                  </span>
                </span>
                <button
                  type="button"
                  className="iconbtn"
                  disabled={busy}
                  onClick={() => setRevoking(member)}
                  aria-label={`Stop sharing with ${member.email ?? member.fingerprint}`}
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        </>
      )}

      {pending.length > 0 && (
        <>
          <h2 className="import-heading">Waiting for a reply</h2>
          <div className="list">
            {pending.map((invite) => (
              <div key={invite.invitation.exchangeId} className="row" style={{ cursor: "default" }}>
                <span className="avatar" aria-hidden="true">
                  …
                </span>
                <span className="rowtext">
                  <b>{invite.email}</b>
                  <span>Hasn't sent their reply link back yet</span>
                </span>
                <button
                  type="button"
                  className="iconbtn"
                  disabled={busy}
                  onClick={async () => {
                    await sharing.cancelInvite(invite.invitation.exchangeId);
                    await reload();
                    onToast(`The invitation to ${invite.email} was cancelled.`);
                  }}
                >
                  Cancel
                </button>
              </div>
            ))}
          </div>
        </>
      )}

      {revoking !== null && (
        <div className="confirm">
          <p className="status" data-tone="attn">
            <AlertIcon />
            <span>
              <b>Stop sharing with {revoking.email ?? "them"}?</b>
              <em>
                They stop seeing anything you change from now on. They have already seen the
                passwords in here, so change any that matter.
              </em>
            </span>
          </p>
          <button
            type="button"
            className="btn danger big"
            disabled={busy}
            onClick={async () => {
              if (!datasetId) return;
              setBusy(true);
              try {
                await sharing.revoke({ datasetId, keyId: revoking.keyId });
                setRevoking(null);
                await reload();
                onToast(`${revoking.email ?? "They"} can no longer see ${keyring.name.value}.`);
              } catch (cause) {
                setError(
                  cause instanceof Error ? cause.message : "Keyweb couldn't take that away.",
                );
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? "Taking it away…" : "Yes, stop sharing with them"}
          </button>
          <button
            type="button"
            className="btn sec"
            disabled={busy}
            onClick={() => setRevoking(null)}
          >
            Leave it as it is
          </button>
        </div>
      )}

      <label className="field" style={{ marginTop: "1.25rem" }}>
        <span>Share it with someone</span>
        <div className="box">
          <input
            type="email"
            inputMode="email"
            autoComplete="off"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="their.name@gmail.com"
            onKeyDown={(e) => {
              if (e.key === "Enter") void invite();
            }}
          />
        </div>
        <span className="hint">
          It has to be the Google account they use, because that is how Google lets them at the
          file.
        </span>
      </label>

      <div className="list" style={{ marginBottom: "0.75rem" }}>
        <button
          type="button"
          className="row"
          aria-pressed={role === "viewer"}
          onClick={() => setRole("viewer")}
        >
          <span className="avatar" aria-hidden="true">
            {role === "viewer" ? "●" : "○"}
          </span>
          <span className="rowtext">
            <b>They can look</b>
            <span>They see the passwords. They can't change or add any.</span>
          </span>
        </button>
        <button
          type="button"
          className="row"
          aria-pressed={role === "writer"}
          onClick={() => setRole("writer")}
        >
          <span className="avatar" aria-hidden="true">
            {role === "writer" ? "●" : "○"}
          </span>
          <span className="rowtext">
            <b>They can look and change</b>
            <span>They can add passwords and edit the ones that are here.</span>
          </span>
        </button>
      </div>

      <button
        type="button"
        className="btn pri big"
        disabled={email.trim().length === 0 || busy}
        onClick={() => void invite()}
      >
        {busy ? "Getting it ready…" : "Make a link to send them"}
      </button>

      <p className="status" data-tone="calm">
        <ShieldIcon />
        <span>
          <b>Google never sees these passwords.</b>
          <em>
            The keyring is locked before it leaves your device, and only the people here have a
            key to it. Google stores it and can't read it.
          </em>
        </span>
      </p>

      {datasetId && (
        <button
          type="button"
          className="btn sec"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await sharing.stopSharing(keyring.id);
              onToast(`${keyring.name.value} is private again.`);
              onBack();
            } finally {
              setBusy(false);
            }
          }}
        >
          Stop sharing this keyring with everyone
        </button>
      )}
    </>
  );
}
