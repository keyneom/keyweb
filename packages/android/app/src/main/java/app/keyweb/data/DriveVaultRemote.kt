package app.keyweb.data

import app.keyweb.vault.BackupUnreadableException
import app.keyweb.vault.EnvelopeDecryptException
import app.keyweb.vault.RemoteRevision
import app.keyweb.vault.RemoteUnavailableException
import app.keyweb.vault.RemoteVaultStore
import app.keyweb.vault.SyncEnvelopeV1
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.VaultState
import app.keyweb.vault.VersionConflictException
import java.io.IOException
import java.time.Instant
import app.keyweb.vault.envelopeJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Encrypted backup in the user's own Google Drive.
 *
 * Drive holds the same sealed bytes the phone holds. Google cannot read them
 * and neither can Keyweb, which has no server: if both the passkey and the
 * printed code are lost, the backup is gone, and the app says so rather than
 * implying a rescue that does not exist.
 *
 * ## What this device can and cannot rewrite
 *
 * The file holds the vault sealed twice — once under the web's passkey-derived
 * key, once under the printed recovery code. This app does not derive the
 * passkey key on Android — not because the platform cannot (sync-kit-android
 * ships `AndroidPasskeyKeyProvider` and easy-bc uses it) but because Keyweb was
 * built believing it could not, which is a mistake worth undoing. So this
 * device works through the recovery envelope, and carries the `passkey`
 * envelope forward byte for byte on every write.
 *
 * Carrying it forward is not politeness, it is the data-loss rule. Dropping an
 * envelope this device cannot rewrite would silently remove the browser's way
 * into the backup, discovered only by someone trying to use it.
 */
class DriveVaultRemote(
    private val drive: DriveFiles,
    private val cipher: VaultEnvelopeCipher,
    /**
     * The passkey-sealed copy, when this phone has opened one.
     *
     * Null is not "there isn't one" — it is "this phone cannot write it right
     * now", which happens when the passkey ceremony was declined or Credential
     * Manager was unavailable. Either way the envelope is carried forward
     * untouched rather than dropped, because a device that cannot rewrite an
     * envelope has no business deleting it.
     */
    private val passkeyCipher: VaultEnvelopeCipher? = null,
) : RemoteVaultStore {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The serializer the envelope itself must go out through.
     *
     * `schemaVersion` and `algorithm` have defaults on [SyncEnvelopeV1], and
     * kotlinx.serialization omits defaulted properties unless told not to. The
     * envelope written here was therefore missing both, and the web's parser
     * requires them — so every backup a phone wrote was unreadable in a
     * browser, with the recovery code as well as without it. Android read its
     * own file fine, because a Kotlin decoder puts the defaults back.
     *
     * `envelopeJson` exists for exactly this and says so; this file simply
     * used its own local `Json` for both parsing and writing. Parsing wants
     * `ignoreUnknownKeys`, writing wants `encodeDefaults`, and one instance
     * cannot be both without saying so.
     */
    private val wire = envelopeJson
    private var fileId: String? = null
    private var folderId: String? = null

    /**
     * What sits in the Drive file: `{ v, passkey, recovery }`.
     *
     * Parsed as raw JSON rather than typed fields, because the point is to
     * preserve members this version does not understand. A future envelope
     * added by another client must survive a write from this one.
     */
    private data class Payload(val raw: JsonObject) {
        val passkey: JsonElement? get() = raw["passkey"]
        val recovery: JsonElement? get() = raw["recovery"]
    }

    private fun parse(content: String): Payload? {
        if (content.isBlank()) return null
        return try {
            val element = json.parseToJsonElement(content).jsonObject
            if (element.containsKey("passkey") || element.containsKey("recovery")) {
                Payload(element)
            } else {
                // A backup written before the recovery copy existed is a bare
                // envelope rather than a wrapper.
                Payload(buildJsonObject { put("passkey", element) })
            }
        } catch (cause: Exception) {
            null
        }
    }

    private suspend fun locate(): String? = fileId ?: drive.findFile(DriveClient.VAULT_MARKER).also {
        fileId = it
    }

    private suspend fun version(id: String): String {
        val head = drive.writeHead(id)
        return head.headRevisionId ?: head.etag
    }

    /** The recovery envelope alone, for opening a backup on a replacement phone. */
    suspend fun fetchRecoverySealed(): SyncEnvelopeV1? = reachable {
        val id = locate() ?: return@reachable null
        val recovery = parse(drive.readText(id))?.recovery ?: return@reachable null
        json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), recovery)
    }

    /**
     * What is actually in the backup file, for a person to read out.
     *
     * Every question about "both devices say they are synced and show
     * different things" is answerable from this and unanswerable without it:
     * which file, which copies it holds, and when each was sealed. Guessing at
     * it from behaviour has cost more rounds than building it would have.
     *
     * Nothing here is secret. It is a Drive file id and two timestamps.
     */
    suspend fun describe(): String = reachable {
        val id = locate() ?: return@reachable "No Keyweb backup file in this Google account."
        val all = drive.listFiles().filter { it.keywebMarker == "vault-v1" }
        val payload = parse(drive.readText(id))
            ?: return@reachable "File $id is empty."

        buildString {
            append("file ").append(id.takeLast(8))
            if (all.size > 1) append(" (").append(all.size).append(" vault files!)")
            append(" · passkey copy ")
            append(payload.passkey?.let { sealedAt(it) ?: "yes" } ?: "none")
            append(" · code copy ")
            append(payload.recovery?.let { sealedAt(it) ?: "yes" } ?: "none")
        }
    }

    /** The passkey-sealed copy, when the file has one. */
    suspend fun fetchPasskeySealed(): SyncEnvelopeV1? = reachable {
        val id = locate() ?: return@reachable null
        val passkey = parse(drive.readText(id))?.passkey ?: return@reachable null
        runCatching {
            json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), passkey)
        }.getOrNull()
    }

    override suspend fun read(): RemoteRevision? = reachable {
        val id = locate() ?: return@reachable null
        val current = version(id)
        val payload = parse(drive.readText(id)) ?: return@reachable null

        /*
         * The passkey copy first, when this phone can open one.
         *
         * Both copies hold the same vault whenever the device that wrote them
         * could seal both — which, now that the phone has a passkey, is every
         * ordinary write. Preferring the passkey envelope is what makes a
         * browser's write visible here without waiting for anything, and it is
         * the direction that used to be impossible: the phone could only ever
         * read the copy the browser could not rewrite.
         */
        val viaPasskey = passkeyCipher?.let { key ->
            payload.passkey?.let { raw ->
                runCatching {
                    key.open(json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), raw))
                }.getOrNull()
            }
        }
        if (viaPasskey != null) return@reachable RemoteRevision(viaPasskey, current)

        val recovery = payload.recovery ?: return@reachable null

        /*
         * Refuse the recovery copy when the passkey copy is demonstrably newer.
         *
         * The mirror of the browser's check, and the same failure: opening a
         * copy that is behind succeeds, so the phone reports itself synced
         * while showing a vault a browser has moved on from. A wrong answer
         * delivered confidently is worse than an error, and the timestamps are
         * the only evidence available without the key to the other envelope.
         *
         * Only when this phone cannot open the passkey copy — if it could, it
         * already returned it above.
         */
        val passkeyCopy = payload.passkey
        if (passkeyCopy != null && behind(recovery, passkeyCopy)) {
            throw BackupUnreadableException(
                "A browser has newer passwords than this phone can read. Set up the shared " +
                    "key on this phone to catch up.",
            )
        }

        val envelope = json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), recovery)
        /*
         * A backup that exists and will not open is not an absent backup.
         *
         * Letting this throw killed the app outright; returning null would
         * have been worse, because the engine would have read "no backup" and
         * published over whatever is actually in the file. Both were reachable
         * the moment something else wrote a recovery envelope sealed under a
         * different code — which is precisely what a browser did.
         */
        val state = try {
            cipher.open(envelope)
        } catch (cause: EnvelopeDecryptException) {
            throw BackupUnreadableException(
                "The backup in Google Drive was written by something else and this phone's " +
                    "code doesn't open it. Nothing on this phone has changed.",
            )
        }

        /*
         * Repair a backup written before the envelope carried its own version.
         *
         * Builds up to 0.2.0-beta.8 dropped `schemaVersion` and `algorithm`,
         * because both hold defaults and the serializer used here omitted
         * those. This phone reads such a file perfectly — a Kotlin decoder
         * puts the defaults back — so nothing here would ever have noticed,
         * and a browser could not open the file at all.
         *
         * Repaired on read rather than left for the next edit, because the
         * engine skips a write when nothing has changed: a person whose vault
         * is simply *correct* would never publish again, and their backup
         * would stay unreadable in a browser forever. Rewriting it here costs
         * one upload, once, on a file that is already in hand.
         *
         * Idempotent by construction: the rewrite goes out through `wire`,
         * which emits both fields, so a repaired file never matches again.
         */
        if (recovery.jsonObject["schemaVersion"] == null) {
            runCatching { drive.write(id, sealed(state, payload)) }
            return@reachable RemoteRevision(state, version(id))
        }

        RemoteRevision(state, current)
    }

    override suspend fun write(state: VaultState, expectedVersion: String?): String {
        val id = try {
            locate()
        } catch (cause: IOException) {
            throw RemoteUnavailableException(cause.message ?: "Keyweb couldn't reach your backup.")
        }

        if (id == null) {
            if (expectedVersion != null) {
                // Told to expect a revision but the file is gone. Refusing beats
                // recreating: the file may simply be invisible to this session.
                throw VersionConflictException("The backup file could not be found.")
            }
            return reachable {
                val parent = ensureFolder()
                val created = drive.create(
                    name = DriveClient.VAULT_FILE_NAME,
                    content = sealed(state, existing = null),
                    parentId = parent,
                    appProperties = DriveClient.VAULT_MARKER,
                )
                fileId = created
                version(created)
            }
        }

        return reachable {
            // Preflight: refuse if the remote moved since it was read. Drive has
            // no compare-and-set, so a narrow window remains between this check
            // and the upload; the CRDT join is what makes losing that race an
            // extra round trip rather than a lost edit.
            if (expectedVersion != null) {
                val current = version(id)
                if (current != expectedVersion) {
                    throw VersionConflictException(
                        "Expected revision $expectedVersion but the backup is at $current.",
                    )
                }
            }
            // Read before write, so the passkey envelope is carried rather than
            // dropped. Not knowing what is in the file must never escalate into
            // replacing it with less.
            val existing = parse(drive.readText(id))
            drive.write(id, sealed(state, existing))
            version(id)
        }
    }

    /**
     * The file as this device would write it now.
     *
     * Both envelopes when this phone can seal both, which since it gained a
     * passkey is the ordinary case. That is the whole point of the passkey:
     * every write refreshes both copies from the same state, so they cannot
     * drift apart, and neither device is left reading a copy the other cannot
     * update.
     *
     * Whatever cannot be sealed here is carried forward byte for byte. A
     * device that cannot rewrite an envelope has no business deleting it —
     * dropping one silently removes somebody's way into their own backup, and
     * they find out on the day they need it.
     */
    private fun sealed(state: VaultState, existing: Payload?): String {
        val at = Instant.now().toString()
        val rewritten = mutableSetOf("recovery")
        val recovery = cipher.seal(state, updatedAt = at)
        val passkey = passkeyCipher?.seal(state, updatedAt = at)?.also { rewritten += "passkey" }

        val next = buildJsonObject {
            put("v", 1)
            // Every member this device is not authoritative for survives.
            existing?.raw?.forEach { (key, value) -> if (key !in rewritten) put(key, value) }
            put("recovery", wire.encodeToJsonElement(SyncEnvelopeV1.serializer(), recovery))
            passkey?.let {
                put("passkey", wire.encodeToJsonElement(SyncEnvelopeV1.serializer(), it))
            }
        }
        return next.toString()
    }

    /**
     * Is [older] sealed before [newer]?
     *
     * Read off `updatedAt`, which is the one thing comparable without holding
     * either key. Equal is not behind: a device that can seal both writes both
     * in one pass, from one state, at one moment — which is the normal case
     * and must not be reported as a problem.
     */
    private fun behind(older: JsonElement, newer: JsonElement): Boolean {
        val a = sealedAt(older) ?: return false
        val b = sealedAt(newer) ?: return false
        return a < b
    }

    private fun sealedAt(envelope: JsonElement): String? =
        runCatching {
            envelope.jsonObject["updatedAt"]?.jsonPrimitive?.content
        }.getOrNull()

    private suspend fun ensureFolder(): String {
        folderId?.let { return it }
        val existing = drive.findFile(DriveClient.FOLDER_MARKER)
        val id = existing
            ?: drive.createFolder(DriveClient.FOLDER_NAME, DriveClient.FOLDER_MARKER)
        folderId = id
        return id
    }

    /**
     * Transport failures become the calm offline state; everything else keeps
     * its own meaning. A [VersionConflictException] in particular must not be
     * flattened into "offline", because the engine handles the two differently.
     */
    private suspend fun <T> reachable(block: suspend () -> T): T =
        try {
            block()
        } catch (cause: VersionConflictException) {
            throw cause
        } catch (cause: IOException) {
            throw RemoteUnavailableException(cause.message ?: "Keyweb couldn't reach your backup.")
        }
}
