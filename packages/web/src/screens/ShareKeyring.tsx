import { useCallback, useEffect, useState } from "react";
import type { KeyringRecord } from "@keyweb/vault-core";
import { AlertIcon, BackIcon, CheckIcon, CopyIcon, ShieldIcon } from "../ui/icons";
import type { Member, PendingInvite, ShareRole, SharingApi } from "../vault/useVault";
import { SHARE_ROLES } from "../vault/sharing/operations";
import type { SharingRole } from "@keyneom/sync-kit/sharing";

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
  onToast: (message: string, tone?: "safe" | "risk") => void;
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
  /** The role on this keyring, kept apart from "may I manage it". */
  const [myRole, setMyRole] = useState<SharingRole | null>(null);
  /** A handover link, once one has been made, so it can be copied out. */
  const [handover, setHandover] = useState<string | null>(null);
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
      /*
       * An admin can invite and revoke too, which is the whole point of the
       * role. Checking only for "owner" here would have made the screen offer
       * an admin nothing but a list to look at.
       */
      const yours = list.find((member) => member.you)?.role;
      setMyRole(yours ?? null);
      setMine(yours === "owner" || yours === "admin");
    } catch {
      // Not knowing who has access is worth saying nothing about until the
      // person asks for something that needs it. The list is a read of Drive,
      // and Drive is allowed to be away.
      setMembers(null);
      setMine(null);
      setMyRole(null);
    }
  }, [datasetId, keyring.id, sharing]);

  useEffect(() => {
    void reload();
  }, [reload]);

  /**
   * Hand the keyring to somebody already on it.
   *
   * Nothing changes at this point except that a link exists. The transfer
   * happens when they open it, which is deliberate: there is no moment where
   * the keyring belongs to nobody, and an owner who changes their mind before
   * sending the link has changed nothing.
   */
  async function handOver(member: Member) {
    if (!datasetId || busy) return;
    setBusy(true);
    setError(null);
    try {
      setHandover(
        await sharing.proposeOwnership({
          datasetId,
          keyId: member.keyId,
          email: member.email ?? "",
        }),
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't prepare that handover.");
    } finally {
      setBusy(false);
    }
  }

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
                      {describeRole(member.role)}
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
                    {describeRole(member.role)} ·
                    key {member.fingerprint}
                  </span>
                </span>
                {/*
                  Only the owner, and only for somebody who is not already one.
                  Handing a keyring over is the one thing an admin cannot do:
                  there is exactly one owner, and it moves by a decision rather
                  than by a permission somebody else granted.
                */}
                {myRole === "owner" && member.role !== "owner" && (
                  <button
                    type="button"
                    className="iconbtn"
                    disabled={busy}
                    onClick={() => void handOver(member)}
                    aria-label={`Make ${member.email ?? member.fingerprint} the owner`}
                  >
                    Make owner
                  </button>
                )}
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
          {handover && (
            <p className="status" data-tone="attention">
              <span>
                <b>Send them this link.</b>
                <em>
                  Nothing changes until they open it. Until then you are still the owner, so
                  there is no moment where the keyring belongs to nobody.
                </em>
                <input readOnly value={handover} onFocus={(e) => e.currentTarget.select()} />
              </span>
            </p>
          )}
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

      {/*
        Driven off the list of roles rather than written out, so a role cannot
        exist in the type and be missing from the screen — which is exactly how
        "admin" spent its life in the protocol without ever being offered.
      */}
      <div className="list" style={{ marginBottom: "0.75rem" }}>
        {SHARE_ROLES.map((choice) => (
          <button
            key={choice.value}
            type="button"
            className="row"
            aria-pressed={role === choice.value}
            onClick={() => setRole(choice.value)}
          >
            <span className="avatar" aria-hidden="true">
              {role === choice.value ? "●" : "○"}
            </span>
            <span className="rowtext">
              <b>{choice.label}</b>
              <span>{choice.detail}</span>
            </span>
          </button>
        ))}
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
            } catch (cause) {
              onToast(
                cause instanceof Error
                  ? cause.message
                  : "Keyweb couldn't stop sharing that keyring.",
                "risk",
              );
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

/**
 * What a role means, in one line, wherever a role is shown.
 *
 * One function rather than a conditional at each call site: the two places
 * that described roles had already drifted into saying different things about
 * the same role, which is how somebody ends up believing a "writer" can invite
 * people because one screen implied it.
 */
function describeRole(role: SharingRole): string {
  switch (role) {
    case "owner":
      return "Shared it with you";
    case "admin":
      return "Can change, and invite others";
    case "writer":
      return "Can add and change";
    default:
      return "Can look, can't change";
  }
}
