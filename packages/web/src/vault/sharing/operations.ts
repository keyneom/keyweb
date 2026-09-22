import type {
  SharedBackupParticipantV1,
  SharingInvitationV1,
  SharingPublicKeyResponseV1,
  SharingRole,
} from "@keyneom/sync-kit/sharing";
import { sharingKeyFingerprint } from "@keyneom/sync-kit/sharing/web-crypto";
import type { SharingDatasetFileV1 } from "@keyneom/sync-kit/sharing";
import { datasetOf, VAULT_DOCUMENT, type VaultState, type VaultSync } from "@keyweb/vault-core";
import type { SharingController } from "./controller";
import { buildJoinLink, buildOwnershipLink, buildResponseLink } from "./links";
import type { SharingIdentityLike } from "./identity";

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

/**
 * What somebody else may do with a keyring you shared.
 *
 * `owner` is absent because it is not something you grant: exactly one person
 * holds it, and it moves by an ownership transfer rather than by a dropdown.
 *
 * `admin` is here now and was not before. Leaving it out meant a shared
 * keyring had exactly one person who could invite anyone else — so a couple
 * sharing their household passwords had a household that only one of them
 * could add anybody to, and losing that person's account meant nobody could
 * ever add anyone again. That is a worse failure than the one the omission was
 * avoiding, which was "a second person who can invite people is a bigger
 * decision than a checkbox". It is a bigger decision, so it gets a sentence
 * saying what it means rather than being hidden.
 */
export type ShareRole = Exclude<SharingRole, "owner">;

/** What each role is called and what it actually lets somebody do. */
export const SHARE_ROLES: { value: ShareRole; label: string; detail: string }[] = [
  {
    value: "viewer",
    label: "Can look",
    detail: "They see everything on this keyring. They cannot change it.",
  },
  {
    value: "writer",
    label: "Can change",
    detail: "They see everything and can add, edit and delete passwords on it.",
  },
  {
    value: "admin",
    label: "Can change and invite",
    detail:
      "Everything above, and they can invite other people and take their access away again. " +
      "Give this to someone you would trust to run the keyring if you could not.",
  },
];

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
/**
 * Datasets someone other than this person holds the key to.
 *
 * Needed because every keyring has a file now, so "has a file" stopped meaning
 * "is shared". Added when an invitation is accepted, removed when sharing is
 * stopped, and a keyring somebody else owns is shared by definition.
 */
const SHARED_KEY = "sharing:shared";

/** A dataset this device asked to join, before it can read it. */
type JoinedDataset = { datasetId: string; label: string; role: ShareRole };

/**
 * A share that is readable but cannot be bound under the id in the file.
 *
 * The file's keyring id is already a different keyring in this vault. Binding
 * it would move this vault's private passwords into that file. It stays
 * pending until the person adds it under a new id.
 */
export type BlockedJoin = {
  datasetId: string;
  label: string;
  remoteKeyringId: string;
  remoteName: string;
  localName: string;
};

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

/**
 * The invitation an exchange id belongs to, whichever keyring's row holds it.
 *
 * Outstanding invitations are stored per keyring, because a person looking at
 * one keyring wants to see what is outstanding on *it* — but an exchange is
 * one conversation with one person, and any row of it carries the invitation
 * a reply has to be matched against.
 */
function inviteFor(
  pending: Record<string, PendingInvite>,
  exchangeId: string,
): PendingInvite | undefined {
  return Object.values(pending).find((invite) => invite.invitation.exchangeId === exchangeId);
}

export class KeywebSharing {
  #blocked: BlockedJoin[] = [];

  /** Shares waiting because their keyring id is already used here. */
  blockedJoins(): BlockedJoin[] {
    return this.#blocked;
  }
  readonly #sync: VaultSync;
  readonly #controller: SharingController;
  readonly #identity: SharingIdentityLike;
  readonly #store: ShareStore;

  constructor(options: {
    sync: VaultSync;
    controller: SharingController;
    identity: SharingIdentityLike;
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
    return this.shareKeyrings({ ...input, keyringIds: [input.keyringId] });
  }

  /**
   * Invite somebody to several keyrings at once, on one link.
   *
   * One invitation carrying several grants, which is what the format has
   * always described — `requestedGrants` and `files` are both lists, and the
   * joining side already loops over them. Sharing three keyrings meant sending
   * three links, each with its own exchange to accept and its own reply to
   * paste back, for what is one decision about one person.
   */
  async shareKeyrings(input: {
    keyringIds: string[];
    email: string;
    role: ShareRole;
  }): Promise<{ link: string; exchangeId: string }> {
    if (input.keyringIds.length === 0) throw new Error("Pick a keyring to share.");

    // Before anything else and before any browser hand-off.
    await this.#identity.getOrCreate();

    const state = await this.#sync.state();
    const labels = input.keyringIds.map((keyringId) => {
      const keyring = state.keyrings[keyringId];
      if (!keyring || keyring.deleted.value) throw new Error("That keyring doesn't exist.");
      return keyring.name.value;
    });

    const grants: { datasetId: string; role: ShareRole }[] = [];
    for (const keyringId of input.keyringIds) {
      grants.push({ datasetId: await this.#ensureDataset(keyringId), role: input.role });
    }

    const invited = await this.#controller.inviteParticipantForLink({
      emailAddress: input.email,
      requestedGrants: grants,
    });

    const createdAt = new Date().toISOString();
    for (const [index, keyringId] of input.keyringIds.entries()) {
      await this.#rememberInvite({
        invitation: invited.invitation,
        email: input.email,
        keyringId,
        label: labels[index]!,
        createdAt,
      });
    }

    /*
     * The label says what the invitation is about before anything is joined —
     * one name for one keyring, a count for several, because naming one of
     * three would misdescribe the other two. Display only: each keyring takes
     * its real name from its own signed document once it is adopted.
     */
    return {
      link: buildJoinLink({
        invitation: invited.invitation,
        files: invited.files,
        ownerEmail: null,
        label: labels.length === 1 ? labels[0]! : `${labels.length} keyrings`,
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
  /**
   * Give every keyring its own file.
   *
   * One file per keyring, each encrypted once with its own content key and
   * that key wrapped to every participant — which from the start is you, on
   * every device you own, and later whoever you share it with. It is the shape
   * a shared keyring always had; what changes is that a keyring no longer has
   * to be shared to get it.
   *
   * The alternative it replaces kept every unshared keyring in one file sealed
   * twice, once per key, which is why a device holding only one of those keys
   * could write one copy and leave the other to go stale. There is no second
   * copy here to go stale: a device writes the file, and the key is wrapped to
   * everyone who may read it.
   *
   * Idempotent. A keyring that already has a file is left alone, and one that
   * could not be moved this time is simply tried again on the next run.
   */
  async ensureOwnFiles(): Promise<string[]> {
    const state = await this.#sync.state();
    const unbound = Object.values(state.keyrings).filter(
      (ring) => !ring.deleted.value && !datasetOf(ring),
    );
    const made: string[] = [];
    for (const ring of unbound) {
      made.push(await this.#ensureDataset(ring.id));
    }
    return made;
  }

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
   * Look at a reply without letting them in.
   *
   * The fingerprint has to be on screen *before* the wrap, not after. The
   * links travel over ordinary chat, and the one attack that channel allows
   * is substituting a different key. Once `acceptResponse` has run, the
   * content key is already wrapped to whoever presented it.
   */
  async previewResponse(response: SharingPublicKeyResponseV1): Promise<{
    label: string;
    email: string;
    keyId: string;
    fingerprint: string;
  }> {
    const invite = inviteFor(await this.#pending(), response.exchangeId);
    if (!invite) {
      throw new Error(
        "That reply doesn't match an invitation from this device. Ask them to use the newest link you sent.",
      );
    }
    return {
      label: invite.label,
      email: invite.email,
      keyId: response.keyId,
      fingerprint: sharingKeyFingerprint(response.keyId),
    };
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
    const invite = inviteFor(pending, response.exchangeId);
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
    await this.#rememberShared(invite.invitation.requestedGrants.map((grant) => grant.datasetId));
    // The whole invitation is finished, not one keyring's row of it: the
    // reply carries the key for every file the exchange covered.
    for (const [key, entry] of Object.entries(pending)) {
      if (entry.invitation.exchangeId === response.exchangeId) delete pending[key];
    }
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
    const blocked: BlockedJoin[] = [];
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
      // The keyring's id comes from their document. First-run vaults hardcode
      // `"personal"`, so an inviter who names their keyring that relocates
      // every private password into their Drive file the moment we bind.
      // Refuse any id this vault already has, rather than adopting it.
      const vault = await this.#sync.documentState(VAULT_DOCUMENT);
      const existing = vault.keyrings[keyring.id];
      if (existing && datasetOf(existing) !== entry.datasetId) {
        // Kept, and said out loud. Dropping it made the share vanish after
        // the owner had already let this person in.
        remaining.push(entry);
        blocked.push({
          datasetId: entry.datasetId,
          label: entry.label,
          remoteKeyringId: keyring.id,
          remoteName: keyring.name.value,
          localName: existing.name.value,
        });
        continue;
      }
      if (existing && datasetOf(existing) === entry.datasetId) {
        adopted.push(keyring.name.value);
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
    this.#blocked = blocked;
    await this.#store.writeMeta(JOINED_KEY, remaining);
    return adopted;
  }

  /**
   * Add a blocked share under a fresh keyring id.
   *
   * The shared file keeps the id it already has. This vault files the keyring
   * under a new one, so the private keyring that collided stays private and
   * the shared passwords still sync.
   */
  async adoptAsNewKeyring(datasetId: string): Promise<string> {
    const joined = await this.#joined();
    const entry = joined.find((candidate) => candidate.datasetId === datasetId);
    if (!entry) throw new Error("That shared keyring is no longer waiting.");
    const value = (await this.#controller.adoptDataset(entry.datasetId)).value;
    const keyring = Object.values(value.keyrings).find((ring) => !ring.deleted.value);
    if (!keyring) throw new Error("That shared keyring has nothing in it yet.");

    const vault = await this.#sync.documentState(VAULT_DOCUMENT);
    const existing = vault.keyrings[keyring.id];
    const colliding = existing && datasetOf(existing) !== entry.datasetId;
    const localId = colliding ? crypto.randomUUID() : keyring.id;

    await this.#sync.adoptDocument(entry.datasetId, value);
    await this.#sync.putKeyring({ keyringId: localId, name: keyring.name.value });
    await this.#sync.bindKeyring(
      localId,
      entry.datasetId,
      colliding ? keyring.id : undefined,
    );
    await this.#rememberRole(entry.datasetId, entry.role);

    const remaining = joined.filter((candidate) => candidate.datasetId !== datasetId);
    this.#blocked = this.#blocked.filter((candidate) => candidate.datasetId !== datasetId);
    await this.#store.writeMeta(JOINED_KEY, remaining);
    return keyring.name.value;
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
  /**
   * Keyrings someone else can read, rather than keyrings that have a file.
   *
   * Every keyring has a file now, so the old test — "is it bound to a
   * dataset" — would call every keyring shared, which is the one thing a
   * person must never be told about a keyring nobody else can see.
   */
  async sharedKeyrings(state: VaultState): Promise<ReadonlySet<string>> {
    const roles = await this.#roles();
    const withOthers = new Set(await this.#sharedDatasets());
    const shared = new Set<string>();
    for (const keyring of Object.values(state.keyrings)) {
      if (keyring.deleted.value) continue;
      const datasetId = datasetOf(keyring);
      if (!datasetId) continue;
      const role = roles[datasetId];
      // Someone else's keyring is shared with you by definition.
      if ((role && role !== "owner") || withOthers.has(datasetId)) shared.add(keyring.id);
    }
    return shared;
  }

  /** Nobody else holds this keyring's key any more. */
  async forgetShared(datasetId: string | null): Promise<void> {
    if (!datasetId) return;
    const current = await this.#sharedDatasets();
    await this.#store.writeMeta(
      SHARED_KEY,
      current.filter((id) => id !== datasetId),
    );
  }

  async #sharedDatasets(): Promise<string[]> {
    const stored = await this.#store.readMeta(SHARED_KEY);
    return Array.isArray(stored) ? (stored as string[]) : [];
  }

  async #rememberShared(datasetIds: string[]): Promise<void> {
    const current = new Set(await this.#sharedDatasets());
    for (const id of datasetIds) current.add(id);
    await this.#store.writeMeta(SHARED_KEY, [...current]);
  }

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

  /**
   * Hand a keyring over to somebody who is already on it.
   *
   * Only an existing member can be made the owner, and that is the protocol's
   * rule rather than a simplification: the new owner has to already hold a key
   * on the dataset, or there would be nothing to re-sign the head with.
   *
   * Returned as a link because there is no Keyweb server to leave a proposal
   * on. One link, not two — the recipient can accept *and* finalise without
   * anything coming back, so the person handing it over is finished when they
   * have sent it.
   *
   * The outgoing owner is left as an admin rather than dropped. Somebody
   * handing over a household keyring almost never means "and remove me from
   * it", and if they do, the new owner can now do that themselves — which is
   * the point of there being a new owner.
   */
  async proposeOwnership(input: {
    datasetId: string;
    keyId: string;
    email: string;
  }): Promise<string> {
    await this.#identity.getOrCreate();
    const transfer = await this.#controller.prepareOwnershipTransfer({
      datasetIds: [input.datasetId],
      toKeyId: input.keyId,
      recipientEmailAddress: input.email,
      previousOwnerRole: "admin",
    });
    return buildOwnershipLink({ transfer });
  }

  /**
   * Take a keyring over, from a link somebody sent.
   *
   * Accepting and finalising are one call here because they are one decision
   * for the person: a half-finished transfer, accepted but never published, is
   * a keyring with two people believing different things about who owns it.
   *
   * sync-kit recognises datasets it has already transferred by transfer id, so
   * a retry after a failure halfway through is safe rather than a second
   * transfer.
   */
  async acceptOwnership(payload: unknown): Promise<void> {
    await this.#identity.getOrCreate();
    const accepted = await this.#controller.acceptOwnershipTransferProposal(payload);
    const results = await this.#controller.finalizeOwnershipTransfer(accepted);
    const failed = results.filter((result) => result.status === "failed");
    if (failed.length > 0) {
      throw new Error(
        "Keyweb couldn't finish taking over that keyring. Nothing has changed — ask for the link again.",
      );
    }
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
    for (const [key, invite] of Object.entries(pending)) {
      // Every keyring the invitation covered, not just the row that was
      // tapped: one link, one exchange, one thing to give up on.
      if (invite.invitation.exchangeId === exchangeId) delete pending[key];
    }
    await this.#store.writeMeta(PENDING_KEY, pending);
  }

  // ---- Local bookkeeping ----

  async #pending(): Promise<Record<string, PendingInvite>> {
    const stored = await this.#store.readMeta(PENDING_KEY);
    return (stored as Record<string, PendingInvite> | undefined) ?? {};
  }

  /**
   * Keyed by the exchange *and* the keyring it is about.
   *
   * One invitation can cover several keyrings, and they all share its exchange
   * id — so keying on that alone meant each keyring's record overwrote the
   * last, and only the final one had anything outstanding to show or cancel.
   * Cancelling still works on the exchange, because that is the thing the
   * other person holds a link to: giving up on it gives up on all of it.
   */
  async #rememberInvite(invite: PendingInvite): Promise<void> {
    const pending = await this.#pending();
    pending[`${invite.invitation.exchangeId}:${invite.keyringId}`] = invite;
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
