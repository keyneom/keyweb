import type {
  SharedBackupParticipantV1,
  SharingInvitationV1,
  SharingPublicKeyResponseV1,
  SharingRole,
} from "@keyneom/sync-kit/sharing";
import { sharingKeyFingerprint } from "@keyneom/sync-kit/sharing/web-crypto";
import type { SharingDatasetFileV1 } from "@keyneom/sync-kit/sharing";
import { datasetOf, type VaultState, type VaultSync } from "@keyweb/vault-core";
import type { SharingController } from "./controller";
import { buildJoinLink, buildResponseLink } from "./links";
import type { SharingIdentity } from "./identity";

/**
 * Sharing a keyring, joining one, and deciding who stays.
 *
 * Every operation here begins by unlocking the sharing identity, and that
 * order is deliberate rather than incidental. A passkey prompt cannot appear
 * twice at once, and the flows below can each take a trip through Google's
 * Picker in the middle — so any prompt that arrives *after* the browser
 * hand-off arrives when the person has stopped paying attention, or after the
 * page has been thrown away and reloaded. Confirming who you are first, once,
 * while the tap that started it is still on screen, is what stops the rest of
 * the flow needing anything from the person at all.
 */

export type ShareRole = Exclude<SharingRole, "owner" | "admin">;

/** A keyring shared with someone, as a person needs to see it. */
export type Member = {
  keyId: string;
  /** Six characters the two of them can read to each other to check. */
  fingerprint: string;
  role: SharingRole;
  /** Known only on the device that did the inviting. */
  email: string | null;
  /** True for the person looking at the screen. */
  you: boolean;
};

/** An invitation waiting for its answer. */
export type PendingInvite = {
  invitation: SharingInvitationV1;
  email: string;
  keyringId: string;
  label: string;
  createdAt: string;
};

/**
 * Where the owner keeps an invitation between sending it and accepting the
 * reply.
 *
 * The owner has to hold the invitation because the accept step verifies the
 * reply *against* it — that is what ties the answer to the question and stops
 * a response from one share being replayed against another. There is no server
 * to park it on, so it stays on the device that sent it, sealed under the vault
 * key like everything else in this store.
 */
export interface ShareStore {
  readMeta(key: string): Promise<unknown | undefined>;
  writeMeta(key: string, value: unknown): Promise<void>;
}

const PENDING_KEY = "sharing:pending-invites";
const JOINED_KEY = "sharing:joined-datasets";
const MEMBER_EMAILS_KEY = "sharing:member-emails";
const ROLES_KEY = "sharing:roles";

/** A dataset this device asked to join, before it can read it. */
type JoinedDataset = { datasetId: string; label: string; role: ShareRole };

/**
 * A keyring this device may read but not write.
 *
 * Kept because the alternative is worse than a stale answer. Without it,
 * someone shared a keyring as a viewer can type a new password into it, see
 * "Saved", and never learn that it went nowhere — the write is refused at the
 * Drive file, the operation sits in the outbox forever, and the status line
 * says there is an unsaved change with nothing they can do about it.
 *
 * Refreshed whenever the real answer is fetched, and wrong only in the safe
 * direction in between: a promotion takes effect on the next look at who has
 * access, and until then the person is told to ask rather than told they
 * succeeded.
 */
export type ReadOnlyKeyrings = ReadonlySet<string>;

export class KeywebSharing {
  readonly #sync: VaultSync;
  readonly #controller: SharingController;
  readonly #identity: SharingIdentity;
  readonly #store: ShareStore;

  constructor(options: {
    sync: VaultSync;
    controller: SharingController;
    identity: SharingIdentity;
    store: ShareStore;
  }) {
    this.#sync = options.sync;
    this.#controller = options.controller;
    this.#identity = options.identity;
    this.#store = options.store;
  }

  /** Six characters naming this device's owner, for reading aloud. */
  async myFingerprint(): Promise<string> {
    const identity = await this.#identity.getOrCreate();
    return sharingKeyFingerprint(identity.publicKey.keyId);
  }

  /**
   * Start sharing a keyring with somebody, and produce the link to send them.
   *
   * Three things have to happen in this order, and the order is the recovery
   * story. The keyring moves into its own document first, because until it has
   * one there is nothing to share and nothing to lose. The Drive file is made
   * second, so a failure leaves a private keyring that simply is not shared
   * yet. The invitation is last, because it names the file.
   *
   * Re-running after a failure is safe at every point: a bound keyring is not
   * re-bound, an existing dataset is adopted rather than replaced, and a second
   * invitation to the same person is just a second link.
   */
  async shareKeyring(input: {
    keyringId: string;
    email: string;
    role: ShareRole;
  }): Promise<{ link: string; exchangeId: string }> {
    // Before anything else and before any browser hand-off.
    await this.#identity.getOrCreate();

    const state = await this.#sync.state();
    const keyring = state.keyrings[input.keyringId];
    if (!keyring || keyring.deleted.value) throw new Error("That keyring doesn't exist.");
    const label = keyring.name.value;

    const datasetId = await this.#ensureDataset(input.keyringId);

    const invited = await this.#controller.inviteParticipantForLink({
      emailAddress: input.email,
      requestedGrants: [{ datasetId, role: input.role }],
    });

    await this.#rememberInvite({
      invitation: invited.invitation,
      email: input.email,
      keyringId: input.keyringId,
      label,
      createdAt: new Date().toISOString(),
    });

    return {
      link: buildJoinLink({
        invitation: invited.invitation,
        files: invited.files,
        ownerEmail: null,
        label,
      }),
      exchangeId: invited.invitation.exchangeId,
    };
  }

  /**
   * A keyring's own document and Drive file, made if they are not there yet.
   *
   * `adoptDataset` first rather than `createDataset` first, because a second
   * invitation to an already-shared keyring must reach the same file. Creating
   * unconditionally would make a second one and quietly split the keyring in
   * two, with the first person's copy frozen at whatever it held.
   */
  async #ensureDataset(keyringId: string): Promise<string> {
    const state = await this.#sync.state();
    const existing = datasetOf(state.keyrings[keyringId]);
    if (existing) {
      await this.#ensurePublished(existing);
      return existing;
    }

    // A fresh id every time, never reused. Re-binding to a retired dataset
    // would resurrect whatever it still held, deleted passwords included.
    const datasetId = `keyweb-${crypto.randomUUID()}`;
    await this.#sync.bindKeyring(keyringId, datasetId);
    await this.#ensurePublished(datasetId);
    // We made it, so we own it. Recorded here so the keyring is never treated
    // as read-only in the window before anybody asks Drive who has access.
    await this.#rememberRole(datasetId, "owner");
    return datasetId;
  }

  async #ensurePublished(datasetId: string): Promise<void> {
    try {
      await this.#controller.adoptDataset(datasetId, { requireOwned: true });
      return;
    } catch (cause) {
      if (!isMissing(cause)) throw cause;
    }
    await this.#controller.createDataset(datasetId, await this.#sync.documentState(datasetId));
  }

  // ---- The recipient's half ----

  /**
   * Answer an invitation, and produce the reply link to send back.
   *
   * `grantAccess` is the one step that cannot be automated: `drive.file` is a
   * per-file grant and only the person can make it, through Google's own
   * Picker. Everything on either side of it happens without them.
   */
  async joinFromLink(input: {
    invitation: SharingInvitationV1;
    files: SharingDatasetFileV1[];
    label: string | null;
    grantAccess(files: SharingDatasetFileV1[]): Promise<void>;
  }): Promise<{ link: string }> {
    await this.#identity.getOrCreate();
    await input.grantAccess(input.files);

    const response = await this.#controller.submitKeyResponseFromInvitation(
      input.invitation,
      input.files,
    );

    // Remembered so the keyring can be picked up the moment the owner
    // accepts, without the person having to come back and do anything.
    const joined = await this.#joined();
    for (const file of input.files) {
      if (joined.some((entry) => entry.datasetId === file.datasetId)) continue;
      joined.push({
        datasetId: file.datasetId,
        label: input.label ?? "Shared keyring",
        role: file.role as ShareRole,
      });
    }
    await this.#store.writeMeta(JOINED_KEY, joined);

    return { link: buildResponseLink({ response }) };
  }

  /**
   * The owner's last step: let the person in.
   *
   * Verified against the invitation this device sent, which is why the
   * invitation was kept. An answer that does not match one is refused rather
   * than trusted — the links travel over ordinary chat, and the one attack
   * that channel allows is substituting a different key.
   */
  async acceptResponse(response: SharingPublicKeyResponseV1): Promise<{
    label: string;
    email: string;
    keyId: string;
    fingerprint: string;
  }> {
    await this.#identity.getOrCreate();

    const pending = await this.#pending();
    const invite = pending[response.exchangeId];
    if (!invite) {
      throw new Error(
        "That reply doesn't match an invitation from this device. Ask them to use the newest link you sent.",
      );
    }

    const results = await this.#controller.acceptKeyResponseFromPayload({
      invitation: invite.invitation,
      response,
      recipientEmailAddress: invite.email,
    });
    const failed = results.find((result) => result.status === "failed");
    if (failed) {
      throw failed.error instanceof Error
        ? failed.error
        : new Error("Keyweb couldn't finish giving them access.");
    }

    await this.#rememberMemberEmail(response.keyId, invite.email);
    delete pending[response.exchangeId];
    await this.#store.writeMeta(PENDING_KEY, pending);

    return {
      label: invite.label,
      email: invite.email,
      keyId: response.keyId,
      fingerprint: sharingKeyFingerprint(response.keyId),
    };
  }

  /**
   * Pick up any keyring that has become readable since last time.
   *
   * Joining cannot create the keyring, because until the owner accepts there
   * is nothing to read and nothing to name it. So the keyring arrives here
   * instead, on an ordinary sync, and the person sees it appear rather than
   * having to go back to a link.
   *
   * The keyring's id and name come from the shared document itself, never from
   * the link — so what appears is what the owner actually shared, whatever the
   * link claimed.
   */
  async adoptJoinedKeyrings(): Promise<string[]> {
    const joined = await this.#joined();
    if (joined.length === 0) return [];

    const adopted: string[] = [];
    const remaining: JoinedDataset[] = [];
    for (const entry of joined) {
      let value: VaultState;
      try {
        value = (await this.#controller.adoptDataset(entry.datasetId)).value;
      } catch {
        // Not readable yet — the owner has not accepted, or Drive is away.
        // Kept, because giving up would mean the share never lands.
        remaining.push(entry);
        continue;
      }
      const keyring = Object.values(value.keyrings).find((ring) => !ring.deleted.value);
      if (!keyring) {
        remaining.push(entry);
        continue;
      }
      // The contents land first, then the keyring, then the binding that makes
      // the two one thing. Binding an empty document and filling it afterwards
      // would show the person an empty keyring and invite them to wonder what
      // they were actually sent.
      await this.#sync.adoptDocument(entry.datasetId, value);
      await this.#sync.putKeyring({ keyringId: keyring.id, name: keyring.name.value });
      await this.#sync.bindKeyring(keyring.id, entry.datasetId);
      // From the invitation, which is signed. The authoritative answer is in
      // the envelope and arrives the first time anybody looks at who has
      // access; until then this is what was actually granted.
      await this.#rememberRole(entry.datasetId, entry.role);
      adopted.push(keyring.name.value);
    }
    await this.#store.writeMeta(JOINED_KEY, remaining);
    return adopted;
  }

  // ---- Who has access ----

  /** Everyone who can read a shared keyring, newest information from Drive. */
  async members(datasetId: string): Promise<Member[]> {
    const identity = await this.#identity.getOrCreate();
    const emails = await this.#memberEmails();
    const { participants } = await this.#controller.getDatasetParticipants(datasetId);
    const members = participants.map((participant: SharedBackupParticipantV1) => ({
      keyId: participant.keyId,
      fingerprint: sharingKeyFingerprint(participant.keyId),
      role: participant.role,
      email: emails[participant.keyId] ?? null,
      you: participant.keyId === identity.publicKey.keyId,
    }));
    // The authoritative answer just arrived, so record what it says about us.
    const mine = members.find((member) => member.you)?.role;
    if (mine) await this.#rememberRole(datasetId, mine);
    return members;
  }

  /**
   * The keyrings this device may read but not write.
   *
   * Answered from what was last learned rather than by asking Drive, because
   * it is consulted every time a screen renders an edit button.
   */
  async readOnlyKeyrings(state: VaultState): Promise<ReadOnlyKeyrings> {
    const roles = await this.#roles();
    const readOnly = new Set<string>();
    for (const keyring of Object.values(state.keyrings)) {
      if (keyring.deleted.value) continue;
      const datasetId = datasetOf(keyring);
      if (!datasetId) continue;
      const role = roles[datasetId];
      if (role && role !== "owner" && role !== "admin" && role !== "writer") {
        readOnly.add(keyring.id);
      }
    }
    return readOnly;
  }

  /** Change what somebody may do. Owners cannot be demoted by design. */
  async setRole(input: { datasetId: string; keyId: string; role: ShareRole }): Promise<void> {
    await this.#identity.getOrCreate();
    const emails = await this.#memberEmails();
    await this.#controller.setDatasetRole({
      datasetId: input.datasetId,
      keyId: input.keyId,
      role: input.role,
      emailAddress: emails[input.keyId] ?? "",
    });
  }

  /**
   * Take somebody's access away.
   *
   * Two things happen: the keyring is re-encrypted for everyone *except* them,
   * so nothing written from now on is readable by them, and their Drive
   * permission is removed, so they cannot fetch the file at all.
   *
   * What it cannot do is un-read what they already read. Anything they saw, or
   * any copy of the file they kept, is theirs — so revoking is the moment to
   * change the passwords that mattered, and the app says so rather than
   * implying otherwise.
   */
  async revoke(input: { datasetId: string; keyId: string }): Promise<void> {
    await this.#identity.getOrCreate();
    const emails = await this.#memberEmails();
    await this.#controller.revokeDatasetKey({
      datasetId: input.datasetId,
      keyId: input.keyId,
      ...(emails[input.keyId] ? { emailAddress: emails[input.keyId]! } : {}),
    });
  }

  /** Invitations sent from this device that nobody has answered yet. */
  async pendingInvites(keyringId?: string): Promise<PendingInvite[]> {
    const pending = Object.values(await this.#pending());
    return keyringId ? pending.filter((invite) => invite.keyringId === keyringId) : pending;
  }

  /** Give up on an invitation. The link stops being accepted from then on. */
  async cancelInvite(exchangeId: string): Promise<void> {
    const pending = await this.#pending();
    delete pending[exchangeId];
    await this.#store.writeMeta(PENDING_KEY, pending);
  }

  // ---- Local bookkeeping ----

  async #pending(): Promise<Record<string, PendingInvite>> {
    const stored = await this.#store.readMeta(PENDING_KEY);
    return (stored as Record<string, PendingInvite> | undefined) ?? {};
  }

  async #rememberInvite(invite: PendingInvite): Promise<void> {
    const pending = await this.#pending();
    pending[invite.invitation.exchangeId] = invite;
    await this.#store.writeMeta(PENDING_KEY, pending);
  }

  async #joined(): Promise<JoinedDataset[]> {
    const stored = await this.#store.readMeta(JOINED_KEY);
    return (stored as JoinedDataset[] | undefined) ?? [];
  }

  async #memberEmails(): Promise<Record<string, string>> {
    const stored = await this.#store.readMeta(MEMBER_EMAILS_KEY);
    return (stored as Record<string, string> | undefined) ?? {};
  }

  async #roles(): Promise<Record<string, SharingRole>> {
    const stored = await this.#store.readMeta(ROLES_KEY);
    return (stored as Record<string, SharingRole> | undefined) ?? {};
  }

  async #rememberRole(datasetId: string, role: SharingRole): Promise<void> {
    const roles = await this.#roles();
    if (roles[datasetId] === role) return;
    roles[datasetId] = role;
    await this.#store.writeMeta(ROLES_KEY, roles);
  }

  async #rememberMemberEmail(keyId: string, email: string): Promise<void> {
    const emails = await this.#memberEmails();
    emails[keyId] = email;
    await this.#store.writeMeta(MEMBER_EMAILS_KEY, emails);
  }
}

function isMissing(cause: unknown): boolean {
  return (cause as { code?: unknown } | null)?.code === "not-found";
}
