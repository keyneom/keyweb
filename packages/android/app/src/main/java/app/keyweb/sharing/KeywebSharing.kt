package app.keyweb.sharing

import app.keyweb.data.MetaRow
import app.keyweb.data.VaultDao
import app.keyweb.vault.VAULT_DOCUMENT
import app.keyweb.vault.VaultState
import app.keyweb.vault.VaultSync
import app.keyweb.vault.datasetOf
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
    ): String {
        // Before anything else and before any browser hand-off.
        identity.getOrCreate()

        val keyring = sync.state().keyrings[keyringId]
        require(keyring != null && !keyring.deleted.value) { "That keyring doesn't exist." }
        val label = keyring.name.value

        val datasetId = ensureDataset(keyringId)
        val invited = controller.inviteParticipantForLink(
            emailAddress = email,
            requestedGrants = listOf(SharingDatasetGrantV1(datasetId, role)),
        )

        rememberInvite(
            PendingInvite(
                invitation = invited.invitation,
                email = email,
                keyringId = keyringId,
                label = label,
                createdAt = java.time.Instant.now().toString(),
            ),
        )

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
        val invite = pending()[response.exchangeId]
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
        val invite = pending[response.exchangeId]
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
        pending.remove(response.exchangeId)
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
            if (existing != null && already != entry.datasetId) continue
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
        writeMeta(JOINED_KEY, json.encodeToString(JoinedDatasets, remaining))
        return adopted
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
        val pending = pending().toMutableMap()
        pending.remove(exchangeId)
        writeMeta(PENDING_KEY, json.encodeToString(PendingInvites, pending))
    }

    // ---- Local bookkeeping ----

    private suspend fun pending(): Map<String, PendingInvite> =
        readMeta(PENDING_KEY)?.let {
            runCatching { json.decodeFromString(PendingInvites, it) }.getOrNull()
        } ?: emptyMap()

    private suspend fun rememberInvite(invite: PendingInvite) {
        val pending = pending().toMutableMap()
        pending[invite.invitation.exchangeId] = invite
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

    private suspend fun readMeta(key: String): String? = dao.meta(key)?.takeIf { it.isNotBlank() }

    private suspend fun writeMeta(key: String, value: String) = dao.putMeta(MetaRow(key, value))

}

data class AcceptedShare(
    val label: String,
    val email: String,
    val keyId: String,
    val fingerprint: String,
)
