package app.keyweb.sharing

import app.keyweb.data.MetaRow
import app.keyweb.data.VaultDao
import app.keyweb.vault.VAULT_DOCUMENT
import app.keyweb.vault.VaultState
import app.keyweb.vault.VaultSync
import app.keyweb.vault.datasetOf
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.SharedBackupAdditionalKeyV1
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharedBackupOwnershipTransferV1
import com.keyneom.synckit.sharing.SharingDatasetFileV1
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingInvitationV1
import com.keyneom.synckit.sharing.SharingPublicKeyResponseV1
import com.keyneom.synckit.sharing.SharingRole
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Sharing a keyring, joining one, and deciding who stays.
 *
 * A port of `packages/web/src/vault/sharing/operations.ts`, operation for
 * operation, because the two halves of a share happen on different devices and
 * frequently on different platforms.
 *
 * Every operation begins by unlocking the sharing identity, and that order is
 * deliberate. The flows below each take a trip out to a browser in the middle —
 * Android has no native `drive.file` chooser — so any prompt that arrives
 * *after* the hand-off arrives when the person has stopped paying attention, or
 * after the process has been killed and restarted. Confirming who you are
 * first, once, while the tap that started it is still on screen, is what lets
 * the rest of the flow finish without asking for anything.
 */

private val json = Json { ignoreUnknownKeys = true }

private const val PENDING_KEY = "sharing:pending-invites"
private const val JOINED_KEY = "sharing:joined-datasets"
private const val MEMBER_EMAILS_KEY = "sharing:member-emails"
private const val ROLES_KEY = "sharing:roles"

/** The recovery key this device attaches, once made. Same name as the web's. */
private const val RECOVERY_KEY_META = "participant-recovery-key"

/**
 * Datasets someone other than this person holds the key to.
 *
 * Needed because every keyring has a file now, so "has a file" stopped meaning
 * "is shared". Added when an invitation is accepted, removed when sharing is
 * stopped; a keyring somebody else owns is shared by definition.
 */
private const val SHARED_KEY = "sharing:shared"

private val SharedDatasetIds = ListSerializer(String.serializer())

/**
 * Named once rather than spelled out at each call site: the type arguments
 * cannot be inferred from a `json.encodeToString` alone, and getting one wrong
 * would write a shape the reader silently fails to decode.
 */
private val PendingInvites = MapSerializer(String.serializer(), PendingInvite.serializer())
private val MemberEmails = MapSerializer(String.serializer(), String.serializer())
private val JoinedDatasets = ListSerializer(JoinedDataset.serializer())
private val Roles = MapSerializer(String.serializer(), SharingRole.serializer())

/** A keyring shared with someone, as a person needs to see it. */
data class Member(
    val keyId: String,
    /** Six characters the two of them can read to each other to check. */
    val fingerprint: String,
    val role: SharingRole,
    /** Known only on the device that did the inviting. */
    val email: String?,
    /** True for the person looking at the screen. */
    val you: Boolean,
)

/** An invitation waiting for its answer. */
@Serializable
data class PendingInvite(
    val invitation: SharingInvitationV1,
    val email: String,
    val keyringId: String,
    val label: String,
    val createdAt: String,
)

/**
 * A share that is readable but cannot be bound under the id in the file.
 *
 * The file's keyring id is already a different keyring in this vault. It stays
 * pending until the person adds it under a new id.
 */
data class BlockedJoin(
    val datasetId: String,
    val label: String,
    val remoteKeyringId: String,
    val remoteName: String,
    val localName: String,
)

/** A dataset this device asked to join, before it can read it. */
@Serializable
private data class JoinedDataset(
    val datasetId: String,
    val label: String,
    val role: SharingRole = SharingRole.VIEWER,
)

class KeywebSharing(
    private val sync: VaultSync,
    private val controller: SharedBackupController<VaultState>,
    private val identity: KeywebSharingIdentity,
    private val dao: VaultDao,
) {
    private var blocked: List<BlockedJoin> = emptyList()

    /** Shares waiting because their keyring id is already used here. */
    fun blockedJoins(): List<BlockedJoin> = blocked

    /** Six characters naming this device's owner, for reading aloud. */
    suspend fun myFingerprint(): String =
        KeywebSharingIdentity.fingerprint(identity.getOrCreate().publicKey.keyId)

    /**
     * Start sharing a keyring, and produce the link to send.
     *
     * Three things happen in this order, and the order is the recovery story.
     * The keyring moves into its own document first, because until it has one
     * there is nothing to share and nothing to lose. The Drive file is made
     * second, so a failure leaves a private keyring that simply is not shared
     * yet. The invitation is last, because it names the file.
     *
     * Re-running after a failure is safe at every point: a bound keyring is not
     * re-bound, an existing dataset is adopted rather than replaced, and a
     * second invitation to the same person is just a second link.
     */
    suspend fun shareKeyring(
        keyringId: String,
        email: String,
        role: SharingRole,
    ): String = shareKeyrings(listOf(keyringId), email, role)

    /**
     * Invite somebody to several keyrings at once, on one link.
     *
     * One invitation carrying several grants, which is what the format has
     * always described — `requestedGrants` and `files` are lists, and the
     * joining side already loops over them. Sharing three keyrings meant
     * sending three links, each with its own exchange to accept and its own
     * reply to paste back, for what is one decision about one person.
     *
     * Each keyring keeps its own pending invite, so the screen for any one of
     * them shows the invitation that is outstanding on it and can cancel it
     * without touching the others.
     */
    suspend fun shareKeyrings(
        keyringIds: List<String>,
        email: String,
        role: SharingRole,
    ): String {
        require(keyringIds.isNotEmpty()) { "Pick a keyring to share." }

        // Before anything else and before any browser hand-off.
        identity.getOrCreate()

        val state = sync.state()
        val labels = keyringIds.map { keyringId ->
            val keyring = state.keyrings[keyringId]
            require(keyring != null && !keyring.deleted.value) { "That keyring doesn't exist." }
            keyring.name.value
        }

        val grants = keyringIds.map { SharingDatasetGrantV1(ensureDataset(it), role) }
        val invited = controller.inviteParticipantForLink(
            emailAddress = email,
            requestedGrants = grants,
        )

        val now = java.time.Instant.now().toString()
        keyringIds.forEachIndexed { index, keyringId ->
            rememberInvite(
                PendingInvite(
                    invitation = invited.invitation,
                    email = email,
                    keyringId = keyringId,
                    label = labels[index],
                    createdAt = now,
                ),
            )
        }

        /*
         * The label is what the invitation says it is *about*, before anything
         * is joined — one name when there is one keyring, a count when there
         * are several, because naming one of three would misdescribe the other
         * two. It is display only: each keyring takes its real name from its
         * own document once it is adopted, which is signed and cannot be
         * renamed by a link.
         */
        val label = if (labels.size == 1) {
            labels.first()
        } else {
            "${labels.size} keyrings"
        }
        return ShareLinks.buildJoin(invited.invitation, invited.files, label)
    }

    /**
     * A keyring's own document and Drive file, made if they are not there yet.
     *
     * Adopt before create, because a second invitation to an already-shared
     * keyring must reach the same file. Creating unconditionally would make a
     * second one and quietly split the keyring in two, with the first person's
     * copy frozen at whatever it held.
     */
    /**
     * Give every keyring its own file.
     *
     * One file per keyring, encrypted once with its own content key, that key
     * wrapped to every participant — which from the start is you, on every
     * device you own, and later whoever you share it with. The shape a shared
     * keyring always had, for every keyring.
     *
     * What it replaces kept every unshared keyring in one file sealed twice,
     * once per key, so a device holding only one key could refresh one copy
     * and leave the other stale. There is no second copy here to go stale.
     *
     * Idempotent: a keyring that already has a file is left alone, and one
     * that could not be moved is simply tried again next time.
     */
    suspend fun ensureOwnFiles(): List<String> {
        val unbound = sync.state().keyrings.values
            .filter { !it.deleted.value && datasetOf(it) == null }
        return unbound.map { ensureDataset(it.id) }
    }

    private suspend fun ensureDataset(keyringId: String): String {
        val existing = datasetOf(sync.state().keyrings[keyringId])
        if (existing != null) {
            ensurePublished(existing)
            return existing
        }
        // A fresh id every time, never reused. Re-binding to a retired dataset
        // would resurrect whatever it still held, deleted passwords included.
        val datasetId = "keyweb-${UUID.randomUUID()}"
        sync.bindKeyring(keyringId, datasetId)
        ensurePublished(datasetId)
        // We made it, so we own it. Recorded here so the keyring is never
        // treated as read-only in the window before anybody asks Drive.
        rememberRole(datasetId, SharingRole.OWNER)
        return datasetId
    }

    private suspend fun ensurePublished(datasetId: String) {
        try {
            controller.adoptDataset(datasetId, requireOwned = true)
            return
        } catch (cause: Exception) {
            if (!isMissing(cause)) throw cause
        }
        controller.createDataset(datasetId, sync.documentState(datasetId))
    }

    // ---- The recipient's half ----

    /**
     * Answer an invitation, and produce the reply link to send back.
     *
     * [grantAccess] is the one step that cannot be automated: `drive.file` is a
     * per-file grant and only the person can make it, in a browser. Everything
     * on either side of it happens without them.
     */
    suspend fun joinFromLink(
        invitation: SharingInvitationV1,
        files: List<SharingDatasetFileV1>,
        label: String?,
        grantAccess: suspend (List<SharingDatasetFileV1>) -> Unit,
    ): String {
        identity.getOrCreate()
        grantAccess(files)

        val response = controller.submitKeyResponseFromInvitation(invitation, files)

        // Remembered so the keyring can be picked up the moment the owner
        // accepts, without the person having to come back and do anything.
        val joined = joined().toMutableList()
        for (file in files) {
            if (joined.any { it.datasetId == file.datasetId }) continue
            joined += JoinedDataset(file.datasetId, label ?: "Shared keyring", file.role)
        }
        writeMeta(JOINED_KEY, json.encodeToString(JoinedDatasets, joined))

        return ShareLinks.buildResponse(response)
    }

    /**
     * Look at a reply without letting them in.
     *
     * The fingerprint has to be on screen *before* the wrap, not after. The
     * links travel over ordinary chat, and the one attack that channel allows
     * is substituting a different key. Once [acceptResponse] has run, the
     * content key is already wrapped to whoever presented it.
     */
    suspend fun previewResponse(response: SharingPublicKeyResponseV1): AcceptedShare {
        val invite = pending().inviteFor(response.exchangeId)
            ?: error(
                "That reply doesn't match an invitation from this device. " +
                    "Ask them to use the newest link you sent.",
            )
        return AcceptedShare(
            label = invite.label,
            email = invite.email,
            keyId = response.keyId,
            fingerprint = KeywebSharingIdentity.fingerprint(response.keyId),
        )
    }

    /**
     * The owner's last step: let the person in.
     *
     * Verified against the invitation this device sent, which is why the
     * invitation was kept. An answer that does not match one is refused rather
     * than trusted — the links travel over ordinary chat, and the one attack
     * that channel allows is substituting a different key.
     */
    suspend fun acceptResponse(response: SharingPublicKeyResponseV1): AcceptedShare {
        identity.getOrCreate()

        val pending = pending().toMutableMap()
        val invite = pending.inviteFor(response.exchangeId)
            ?: error(
                "That reply doesn't match an invitation from this device. " +
                    "Ask them to use the newest link you sent.",
            )

        val results = controller.acceptKeyResponseFromPayload(
            invitation = invite.invitation,
            response = response,
            recipientEmailAddress = invite.email,
        )
        results.firstOrNull { it.status == "failed" }?.let { failed ->
            throw (failed.error as? Exception)
                ?: IllegalStateException("Keyweb couldn't finish giving them access.")
        }

        rememberMemberEmail(response.keyId, invite.email)
        rememberShared(invite.invitation.requestedGrants.map { it.datasetId })
        // The whole invitation is finished, not one keyring's row of it: the
        // reply carries the key for every file the exchange covered.
        pending.keys.filter { pending[it]?.invitation?.exchangeId == response.exchangeId }
            .forEach { pending.remove(it) }
        writeMeta(PENDING_KEY, json.encodeToString(PendingInvites, pending))

        return AcceptedShare(
            label = invite.label,
            email = invite.email,
            keyId = response.keyId,
            fingerprint = KeywebSharingIdentity.fingerprint(response.keyId),
        )
    }

    /**
     * Pick up any keyring that has become readable since last time.
     *
     * Joining cannot create the keyring, because until the owner accepts there
     * is nothing to read and nothing to name it. So the keyring arrives here
     * instead, on an ordinary sync, and the person sees it appear rather than
     * having to go back to a link.
     *
     * The keyring's id and name come from the shared document itself, never
     * from the link — so what appears is what the owner actually shared,
     * whatever the link claimed.
     */
    suspend fun adoptJoinedKeyrings(): List<String> {
        val joined = joined()
        if (joined.isEmpty()) return emptyList()

        val adopted = mutableListOf<String>()
        val remaining = mutableListOf<JoinedDataset>()
        val blockedNow = mutableListOf<BlockedJoin>()
        for (entry in joined) {
            val value = runCatching { controller.adoptDataset(entry.datasetId).value }.getOrNull()
            val keyring = value?.keyrings?.values?.firstOrNull { !it.deleted.value }
            if (value == null || keyring == null) {
                // Not readable yet — the owner has not accepted, or Drive is
                // away. Kept, because giving up would mean the share never
                // lands.
                remaining += entry
                continue
            }
            // The keyring's id comes from their document. First-run vaults
            // hardcode `"personal"`, so an inviter who names their keyring
            // that relocates every private password into their Drive file
            // the moment we bind. Refuse any id this vault already has,
            // rather than adopting it.
            val vault = sync.documentState(VAULT_DOCUMENT)
            val existing = vault.keyrings[keyring.id]
            val already = existing?.let { datasetOf(it) }
            if (existing != null && already != entry.datasetId) {
                // Kept, and said out loud. Dropping it made the share vanish
                // after the owner had already let this person in.
                remaining += entry
                blockedNow += BlockedJoin(
                    datasetId = entry.datasetId,
                    label = entry.label,
                    remoteKeyringId = keyring.id,
                    remoteName = keyring.name.value,
                    localName = existing.name.value,
                )
                continue
            }
            if (already == entry.datasetId) {
                adopted += keyring.name.value
                continue
            }
            // The contents land first, then the keyring, then the binding that
            // makes the two one thing. Binding an empty document and filling it
            // afterwards would show the person an empty keyring and invite them
            // to wonder what they were actually sent.
            sync.adoptDocument(entry.datasetId, value)
            sync.putKeyring(keyringId = keyring.id, name = keyring.name.value)
            sync.bindKeyring(keyring.id, entry.datasetId)
            // From the invitation, which is signed. The authoritative answer is
            // in the envelope and arrives the first time anybody looks at who
            // has access; until then this is what was actually granted.
            rememberRole(entry.datasetId, entry.role)
            adopted += keyring.name.value
        }
        blocked = blockedNow
        writeMeta(JOINED_KEY, json.encodeToString(JoinedDatasets, remaining))
        return adopted
    }

    /**
     * Add a blocked share under a fresh keyring id.
     *
     * The shared file keeps the id it already has. This vault files the
     * keyring under a new one, so the private keyring that collided stays
     * private and the shared passwords still sync.
     */
    suspend fun adoptAsNewKeyring(datasetId: String): String {
        val joined = joined().toMutableList()
        val entry = joined.firstOrNull { it.datasetId == datasetId }
            ?: error("That shared keyring is no longer waiting.")
        val value = controller.adoptDataset(entry.datasetId).value
        val keyring = value.keyrings.values.firstOrNull { !it.deleted.value }
            ?: error("That shared keyring has nothing in it yet.")
        val vault = sync.documentState(VAULT_DOCUMENT)
        val existing = vault.keyrings[keyring.id]
        val colliding = existing != null && datasetOf(existing) != entry.datasetId
        val localId = if (colliding) UUID.randomUUID().toString() else keyring.id

        sync.adoptDocument(entry.datasetId, value)
        sync.putKeyring(keyringId = localId, name = keyring.name.value)
        sync.bindKeyring(
            keyringId = localId,
            datasetId = entry.datasetId,
            sourceKeyringId = if (colliding) keyring.id else null,
        )
        rememberRole(entry.datasetId, entry.role)

        joined.removeAll { it.datasetId == datasetId }
        blocked = blocked.filter { it.datasetId != datasetId }
        writeMeta(JOINED_KEY, json.encodeToString(JoinedDatasets, joined))
        return keyring.name.value
    }

    // ---- Who has access ----

    /** Everyone who can read a shared keyring, newest information from Drive. */
    suspend fun members(datasetId: String): List<Member> {
        val mine = identity.getOrCreate().publicKey.keyId
        val emails = memberEmails()
        val members = controller.getDatasetParticipants(datasetId).participants.map { participant ->
            Member(
                keyId = participant.keyId,
                fingerprint = KeywebSharingIdentity.fingerprint(participant.keyId),
                role = participant.role,
                email = emails[participant.keyId],
                you = participant.keyId == mine,
            )
        }
        // The authoritative answer just arrived, so record what it says about us.
        members.firstOrNull { it.you }?.let { rememberRole(datasetId, it.role) }
        return members
    }

    /**
     * The keyrings this device may read but not write.
     *
     * Kept because the alternative is worse than a stale answer. Without it,
     * somebody shared a keyring as a viewer can type a new password into it,
     * see "Saved", and never learn that it went nowhere — the write is refused
     * at the Drive file, the operation sits in the outbox forever, and the
     * status line reports an unsaved change they can do nothing about.
     *
     * Answered from what was last learned rather than by asking Drive, because
     * it is consulted every time a screen renders an edit button. Wrong only in
     * the safe direction in between.
     */
    /**
     * Keyrings someone else can read, rather than keyrings that have a file.
     *
     * Every keyring has a file now, so "bound to a dataset" would call every
     * keyring shared — the one thing a person must never be told about a
     * keyring nobody else can see.
     */
    suspend fun sharedKeyrings(state: VaultState): Set<String> {
        val roles = roles()
        val withOthers = sharedDatasets().toSet()
        return state.keyrings.values
            .filter { !it.deleted.value }
            .mapNotNull { ring ->
                val datasetId = datasetOf(ring) ?: return@mapNotNull null
                val role = roles[datasetId]
                ring.id.takeIf {
                    (role != null && role != SharingRole.OWNER) || datasetId in withOthers
                }
            }
            .toSet()
    }

    /** Nobody else holds this keyring's key any more. */
    suspend fun forgetShared(datasetId: String) {
        writeMeta(SHARED_KEY, json.encodeToString(SharedDatasetIds, sharedDatasets().filter { it != datasetId }))
    }

    private suspend fun sharedDatasets(): List<String> =
        readMeta(SHARED_KEY)?.let {
            runCatching { json.decodeFromString(SharedDatasetIds, it) }.getOrNull()
        } ?: emptyList()

    private suspend fun rememberShared(datasetIds: List<String>) {
        writeMeta(SHARED_KEY, json.encodeToString(SharedDatasetIds, (sharedDatasets() + datasetIds).distinct()))
    }

    suspend fun readOnlyKeyrings(state: VaultState): Set<String> {
        val roles = roles()
        return state.keyrings.values
            .filter { !it.deleted.value }
            .mapNotNull { keyring ->
                val datasetId = datasetOf(keyring) ?: return@mapNotNull null
                val role = roles[datasetId] ?: return@mapNotNull null
                keyring.id.takeIf { role == SharingRole.VIEWER }
            }
            .toSet()
    }

    /**
     * Hand a keyring over to somebody who is already on it.
     *
     * Only an existing member can be made the owner, and that is the
     * protocol's rule rather than a simplification: the new owner has to
     * already hold a key on the dataset, or there would be nothing to re-sign
     * the head with.
     *
     * Returned as a link because there is no Keyweb server to leave a proposal
     * on. One link, not two — the recipient can accept *and* finalise without
     * anything coming back, so the person handing it over is finished when
     * they have sent it.
     *
     * The outgoing owner is left as an admin rather than dropped. Somebody
     * handing over a household keyring almost never means "and remove me from
     * it", and if they do, the new owner can now do it themselves — which is
     * the point of there being a new owner.
     */
    suspend fun proposeOwnership(datasetId: String, keyId: String, email: String): String {
        identity.getOrCreate()
        val transfer = controller.prepareOwnershipTransfer(
            listOf(datasetId),
            keyId,
            email,
            SharingRole.ADMIN,
            null,
        )
        val json = Json { encodeDefaults = true }
            .encodeToString(SharedBackupOwnershipTransferV1.serializer(), transfer)
        return ShareLinks.buildOwnership(json)
    }

    /**
     * Take a keyring over, from a link somebody sent.
     *
     * Accepting and finalising are one call because they are one decision for
     * the person: a half-finished transfer, accepted but never published, is a
     * keyring with two people believing different things about who owns it.
     *
     * sync-kit recognises datasets it has already transferred by transfer id,
     * so a retry after a failure halfway through is safe rather than a second
     * transfer.
     */
    suspend fun acceptOwnership(transferJson: String) {
        identity.getOrCreate()
        val proposal = Json { ignoreUnknownKeys = true }
            .decodeFromString(SharedBackupOwnershipTransferV1.serializer(), transferJson)
        val accepted = controller.acceptOwnershipTransferProposal(proposal)
        val results = controller.finalizeOwnershipTransfer(accepted)
        if (results.any { it.status == "failed" }) {
            error(
                "Keyweb couldn't finish taking over that keyring. Nothing has changed — " +
                    "ask for the link again.",
            )
        }
    }

    /** Change what somebody may do. Owners cannot be demoted by design. */
    suspend fun setRole(datasetId: String, keyId: String, role: SharingRole) {
        identity.getOrCreate()
        controller.setDatasetRole(datasetId, keyId, role, memberEmails()[keyId].orEmpty())
    }

    /**
     * Take somebody's access away.
     *
     * Two things happen: the keyring is re-encrypted for everyone *except*
     * them, so nothing written from now on is readable by them, and their Drive
     * permission is removed, so they cannot fetch the file at all.
     *
     * What it cannot do is un-read what they already read. Anything they saw,
     * or any copy of the file they kept, is theirs — so revoking is the moment
     * to change the passwords that mattered, and the app says so rather than
     * implying otherwise.
     */
    suspend fun revoke(datasetId: String, keyId: String) {
        identity.getOrCreate()
        controller.revokeDatasetKey(datasetId, keyId, memberEmails()[keyId])
    }

    /** Invitations sent from this device that nobody has answered yet. */
    suspend fun pendingInvites(keyringId: String? = null): List<PendingInvite> =
        pending().values.filter { keyringId == null || it.keyringId == keyringId }

    /** Give up on an invitation. The link stops being accepted from then on. */
    suspend fun cancelInvite(exchangeId: String) {
        // Every keyring the invitation covered, not just the row that was
        // tapped: one link, one exchange, one thing to give up on.
        val pending = pending().filterValues { it.invitation.exchangeId != exchangeId }
        writeMeta(PENDING_KEY, json.encodeToString(PendingInvites, pending))
    }

    // ---- Local bookkeeping ----

    private suspend fun pending(): Map<String, PendingInvite> =
        readMeta(PENDING_KEY)?.let {
            runCatching { json.decodeFromString(PendingInvites, it) }.getOrNull()
        } ?: emptyMap()

    /**
     * Keyed by the exchange *and* the keyring it is about.
     *
     * One invitation can cover several keyrings, and they all share its
     * exchange id — so keying on that alone meant each keyring's record
     * overwrote the last, and only the final one had anything outstanding to
     * show or cancel.
     */
    /**
     * The invitation an exchange id belongs to, whichever keyring's row holds it.
     *
     * Outstanding invitations are stored per keyring, because somebody looking
     * at one keyring wants to see what is outstanding on *it* — but an exchange
     * is one conversation with one person, and any row of it carries the
     * invitation a reply has to be matched against.
     */
    private fun Map<String, PendingInvite>.inviteFor(exchangeId: String): PendingInvite? =
        values.firstOrNull { it.invitation.exchangeId == exchangeId }

    private suspend fun rememberInvite(invite: PendingInvite) {
        val pending = pending().toMutableMap()
        pending["${invite.invitation.exchangeId}:${invite.keyringId}"] = invite
        writeMeta(PENDING_KEY, json.encodeToString(PendingInvites, pending))
    }

    private suspend fun joined(): List<JoinedDataset> =
        readMeta(JOINED_KEY)?.let {
            runCatching { json.decodeFromString(JoinedDatasets, it) }.getOrNull()
        } ?: emptyList()

    private suspend fun memberEmails(): Map<String, String> =
        readMeta(MEMBER_EMAILS_KEY)?.let {
            runCatching { json.decodeFromString(MemberEmails, it) }.getOrNull()
        } ?: emptyMap()

    private suspend fun roles(): Map<String, SharingRole> =
        readMeta(ROLES_KEY)?.let {
            runCatching { json.decodeFromString(Roles, it) }.getOrNull()
        } ?: emptyMap()

    private suspend fun rememberRole(datasetId: String, role: SharingRole) {
        val roles = roles()
        if (roles[datasetId] == role) return
        writeMeta(ROLES_KEY, json.encodeToString(Roles, roles + (datasetId to role)))
    }

    private suspend fun rememberMemberEmail(keyId: String, email: String) {
        val emails = memberEmails().toMutableMap()
        emails[keyId] = email
        writeMeta(MEMBER_EMAILS_KEY, json.encodeToString(MemberEmails, emails))
    }

    // ---- Your printed code, as a key of yours on every keyring ----

    /**
     * Put your recovery key on the index and every keyring you can write.
     *
     * Named apart from the top-level [protectWithRecoveryCode] it calls: a
     * member with the same name would shadow it and call itself.
     */
    suspend fun keepRecoveryKeys(secret: ByteArray, turnOn: Boolean? = null): List<RecoveryCoverage> {
        val me = identity.getOrCreate()
        val keyrings = sync.state().keyrings.values
            .filter { !it.deleted.value }
            .mapNotNull { datasetOf(it) }
            .distinct()
        return protectWithRecoveryCode(
            controller = controller,
            identity = me,
            memory = recoveryMemory,
            datasetIds = listOf(INDEX_DATASET_ID) + keyrings,
            secret = secret,
            turnOn = turnOn,
        )
    }

    /** Your index already carries a recovery key of yours: some device made a code. */
    suspend fun hasRecoveryKey(): Boolean {
        val me = identity.getOrCreate().publicKey.keyId
        return controller.getDatasetParticipantKeys(INDEX_DATASET_ID).keys
            .any { it.principalKeyId == me && it.purpose == "recovery" }
    }

    /** This code opens your recovery key on the index. */
    suspend fun recoveryKeyOpensWith(secret: ByteArray): Boolean =
        runCatching { controller.openRecoveryKey(INDEX_DATASET_ID, participantRecoveryCode(secret)) }.isSuccess

    private val recoveryMemory = object : RecoveryKeyMemory {
        override suspend fun remembered(): SharedBackupAdditionalKeyV1? =
            readMeta(RECOVERY_KEY_META)?.let {
                runCatching {
                    SyncKitJson.instance.decodeFromString(SharedBackupAdditionalKeyV1.serializer(), it)
                }.getOrNull()
            }

        override suspend fun remember(key: SharedBackupAdditionalKeyV1) =
            writeMeta(
                RECOVERY_KEY_META,
                SyncKitJson.instance.encodeToString(SharedBackupAdditionalKeyV1.serializer(), key),
            )
    }

    private suspend fun readMeta(key: String): String? = dao.meta(key)?.takeIf { it.isNotBlank() }

    private suspend fun writeMeta(key: String, value: String) = dao.putMeta(MetaRow(key, value))

}

data class AcceptedShare(
    val label: String,
    val email: String,
    val keyId: String,
    val fingerprint: String,
)
