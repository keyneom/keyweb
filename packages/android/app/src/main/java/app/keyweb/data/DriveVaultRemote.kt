package app.keyweb.data

import app.keyweb.vault.BackupBehindException
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
    /**
     * The seed this person's sharing identity is wrapped with.
     *
     * Published into the backup, sealed under both keys, so the person's other
     * devices arrive at the same identity without being handed the printed
     * code. Null when this phone does not know it, in which case whatever the
     * file already holds is carried forward untouched — a second seed would be
     * a second identity, and one person appearing as two participants who
     * cannot open the keyrings the other shared.
     */
    private val sharingSeed: ByteArray? = null,
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
        val seed: JsonElement? get() = raw["seed"]
    }

    private fun parse(content: String): Payload? {
        if (content.isBlank()) return null
        return try {
            Payload(json.parseToJsonElement(content).jsonObject)
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
        val text = drive.readText(id)
        if (text.isBlank()) return@reachable null
        val payload = parse(text) ?: throw BackupUnreadableException(
            "The backup file is in Google Drive but Keyweb couldn't read it, so nothing " +
                "was changed.",
        )

        /*
         * The newest copy this phone can actually open.
         *
         * Not "the passkey one, always". A phone that writes recovery-only
         * leaves the passkey envelope frozen, so opening that one and calling
         * it current publishes the old vault back over the newer recovery
         * copy. The browser already sorts by updatedAt. This is that, here.
         *
         * A copy that opens but is older than one that does not is not a
         * success either. Returning it would let the next write stamp the
         * stale vault as the newest, and the newer copy would never be read
         * again.
         */
        val candidates = buildList {
            passkeyCipher?.let { key ->
                payload.passkey?.let { raw ->
                    add((sealedAt(raw) ?: "") to {
                        key.open(json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), raw))
                    })
                }
            }
            payload.recovery?.let { raw ->
                add((sealedAt(raw) ?: "") to {
                    cipher.open(json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), raw))
                })
            }
        }.sortedByDescending { it.first }

        var opened: VaultState? = null
        var openedAt = ""
        for ((at, open) in candidates) {
            val state = runCatching { open() }.getOrNull() ?: continue
            opened = state
            openedAt = at
            break
        }

        val newestAt = listOfNotNull(payload.passkey, payload.recovery)
            .mapNotNull { sealedAt(it) }
            .maxOrNull()
        if (opened != null) {
            if (newestAt != null && openedAt < newestAt) {
                throw BackupBehindException(
                    "A browser has newer passwords than this phone can read. Set up the shared " +
                        "key on this phone to catch up.",
                )
            }
            return@reachable RemoteRevision(opened, current)
        }

        if (payload.passkey == null && payload.recovery == null) return@reachable null
        val recovery = payload.recovery
        val passkeyCopy = payload.passkey
        if (recovery != null && passkeyCopy != null && behind(recovery, passkeyCopy)) {
            throw BackupBehindException(
                "A browser has newer passwords than this phone can read. Set up the shared " +
                    "key on this phone to catch up.",
            )
        }
        if (passkeyCopy != null && recovery != null && behind(passkeyCopy, recovery)) {
            throw BackupBehindException(
                "This phone's recovery copy is newer than the passkey copy it can open. " +
                    "Enter the recovery code for this backup to catch up.",
            )
        }
        throw BackupUnreadableException(
            "The backup in Google Drive has no copy this phone can open. Nothing on this " +
                "phone has changed.",
        )
    }

    override suspend fun write(
        state: VaultState,
        expectedVersion: String?,
        createOnly: Boolean,
    ): String {
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
            // Preflight before anything else. A revision that moved is a
            // conflict even when the new bytes do not parse.
            if (expectedVersion != null) {
                val current = version(id)
                if (current != expectedVersion) {
                    throw VersionConflictException(
                        "Expected revision $expectedVersion but the backup is at $current.",
                    )
                }
            }
            val text = drive.readText(id)
            if (text.isNotBlank() && parse(text) == null) {
                // A deliberate replace is allowed to overwrite a file this
                // phone cannot read. Every other write leaves it untouched:
                // replacing it with less is how a passkey envelope disappears.
                if (createOnly || expectedVersion != null) {
                    throw BackupUnreadableException(
                        "The backup file is in Google Drive but Keyweb couldn't read it, so " +
                            "nothing was changed.",
                    )
                }
            }
            // First publish may only create. A file that showed up after the
            // read holds somebody else's vault; the engine re-reads and merges
            // rather than writing this one over it.
            if (createOnly && text.isNotBlank()) {
                throw VersionConflictException(
                    "A backup appeared after it was read. Keyweb will merge it instead of replacing it.",
                )
            }
            // Read before write, so an envelope this device cannot rewrite is
            // carried rather than dropped.
            val existing = if (text.isBlank()) null else parse(text)
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
        val rewritten = mutableSetOf<String>()
        // Reseal a copy only with a cipher that already opens it. Holding *a*
        // recovery code is not the same as holding *this* backup's, and
        // resealing under the wrong one retires the printed sheet.
        val recovery = existing?.recovery?.takeUnless { opens(cipher, it) }
            ?: cipher.seal(state, updatedAt = at).also { rewritten += "recovery" }
        val passkey = passkeyCipher?.let { key ->
            val carried = existing?.passkey
            if (carried != null && !opens(key, carried)) null
            else key.seal(state, updatedAt = at).also { rewritten += "passkey" }
        }

        // Written when this phone knows it and the file does not; carried
        // forward otherwise. Never reminted.
        val seed = if (existing?.seed == null && sharingSeed != null) {
            rewritten += "seed"
            buildJsonObject {
                put(
                    "passkey",
                    wire.encodeToJsonElement(
                        SyncEnvelopeV1.serializer(),
                        (passkeyCipher ?: cipher).sealBytes(sharingSeed, at),
                    ),
                )
                put(
                    "recovery",
                    wire.encodeToJsonElement(
                        SyncEnvelopeV1.serializer(),
                        cipher.sealBytes(sharingSeed, at),
                    ),
                )
            }
        } else {
            null
        }

        val next = buildJsonObject {
            put("v", 1)
            // Every member this device is not authoritative for survives.
            existing?.raw?.forEach { (key, value) -> if (key !in rewritten) put(key, value) }
            when (recovery) {
                is SyncEnvelopeV1 ->
                    put("recovery", wire.encodeToJsonElement(SyncEnvelopeV1.serializer(), recovery))
                else -> existing?.recovery?.let { put("recovery", it) }
            }
            when (passkey) {
                is SyncEnvelopeV1 ->
                    put("passkey", wire.encodeToJsonElement(SyncEnvelopeV1.serializer(), passkey))
                null -> if ("passkey" !in rewritten) existing?.passkey?.let { put("passkey", it) }
            }
            seed?.let { put("seed", it) }
        }
        return next.toString()
    }

    /** Can this cipher open that envelope? The only honest test is to try. */
    private fun opens(cipher: VaultEnvelopeCipher, raw: JsonElement): Boolean {
        val envelope = runCatching {
            json.decodeFromJsonElement(SyncEnvelopeV1.serializer(), raw)
        }.getOrNull() ?: return false
        return runCatching { cipher.open(envelope) }.isSuccess
    }

    /**
     * Is [older] sealed before [newer]?
     *
     * Read off `updatedAt`, which is the one thing comparable without holding
     * either key. Equal is not behind, and equal is the normal case: a device
     * that can seal both copies writes both in one pass, from one state, at
     * one moment — which both platforms now do.
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
