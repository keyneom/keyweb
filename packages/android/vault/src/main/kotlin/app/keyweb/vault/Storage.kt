package app.keyweb.vault

/**
 * Which document an operation concerns.
 *
 * The empty string is the vault itself, which is why it is the default
 * everywhere: every existing call site means the vault, and adding a parameter
 * must not change what any of them do. A non-empty id is a keyring that lives
 * in its own document — see `docs/keyring-sharing.md` for why it has to.
 */
const val VAULT_DOCUMENT = ""

/**
 * Durable local storage.
 *
 * Two of these methods carry an atomicity requirement that the whole
 * no-data-loss guarantee rests on. A platform implementation (Room on Android)
 * MUST perform each inside a single write transaction:
 *
 *  - [commit] writes the new state AND appends the operation to the outbox. If
 *    the state landed but the outbox append did not, a crash before the next
 *    sync loses the edit from the cloud forever.
 *
 *  - [applyRemote] reads the current state, joins the incoming one into it, and
 *    writes the result. It must never be a blind assignment: an edit committed
 *    while a sync was in flight lives only in local state, and overwriting
 *    instead of joining is exactly how a saved password silently disappears.
 */
interface VaultStorage {
    suspend fun readState(documentId: String = VAULT_DOCUMENT): VaultState

    /** Atomic: persist [nextState] and append [op] to the outbox together. */
    suspend fun commit(op: VaultOp, nextState: VaultState)

    /**
     * Atomic: persist [nextState] and append every op in [ops] together.
     *
     * The whole point is that it is one write. Committing a bulk change an op
     * at a time re-encrypts the entire vault per op, which is what made
     * deleting a keyring of 200 passwords take the better part of a minute.
     */
    suspend fun commitAll(
        ops: List<VaultOp>,
        nextState: VaultState,
        documentId: String = VAULT_DOCUMENT,
    )

    /** Atomic: join [incoming] into the current state and return the result. */
    suspend fun applyRemote(incoming: VaultState, documentId: String = VAULT_DOCUMENT): VaultState

    /** Operations not yet proven present in a published revision, oldest first. */
    suspend fun pending(documentId: String = VAULT_DOCUMENT): List<VaultOp>

    /** Drop operations now known to be in a published revision. */
    suspend fun ack(opIds: List<String>, documentId: String = VAULT_DOCUMENT)

    /**
     * Every document this device holds, the vault excluded.
     *
     * Asked rather than remembered, because the vault's list of bound keyrings
     * and the documents actually on disk legitimately disagree — a keyring
     * bound on another device arrives before its document does.
     */
    suspend fun knownDocuments(): List<String>

    /**
     * Remove a document entirely: its state and anything queued for it.
     *
     * For leaving a keyring somebody else shared. "Remove it from my vault"
     * has to mean the passwords go, not merely that they stop being listed —
     * the same reason [VaultOp.ItemPurge] blanks fields rather than only
     * tombstoning. A genuine deletion rather than a tombstone because nothing
     * has to converge on it: the document belongs to somebody else, and this
     * device is simply no longer carrying a copy.
     *
     * Must refuse the vault. Nothing should be able to ask for that by passing
     * an empty string it did not mean to pass.
     */
    suspend fun forgetDocument(documentId: String)

    /** Persisted causal time, so a restart cannot rewind the clock. */
    suspend fun readClock(): Hlc?

    suspend fun writeClock(value: Hlc)
}

/**
 * How the vault is transformed on its way to and from disk.
 *
 * Operates on the serialised JSON rather than the typed value, so the storage
 * layer keeps one serialisation step and the cipher stays a pure string
 * transform. Synchronous on purpose: Android's AES-GCM is, and keeping it
 * synchronous means it can run inside a Room transaction without holding the
 * database connection across a suspension point.
 */
interface VaultCipher {
    fun seal(plaintext: String): String
    fun open(sealed: String): String
}

/**
 * Stores the vault as-is. Only appropriate where the threat model does not
 * include an attacker reading app storage — in practice, tests.
 */
object PlaintextVaultCipher : VaultCipher {
    override fun seal(plaintext: String): String = plaintext
    override fun open(sealed: String): String = sealed
}

class VersionConflictException(
    message: String = "The remote vault moved since it was read.",
) : Exception(message)

/**
 * The remote could not be reached. Distinct from a conflict: the vault is
 * intact, the user is simply offline, and pending work stays queued.
 */
class RemoteUnavailableException(
    message: String = "The encrypted backup could not be reached.",
) : Exception(message)

/**
 * A backup is there, and this device's key does not open it.
 *
 * Deliberately not [RemoteUnavailableException] and emphatically not `null`.
 * "Offline" makes the engine retry quietly forever; `null` means "there is no
 * backup", and a sync that believes that **publishes over it** — which is how
 * a browser that could not read a phone's backup replaced it with an empty
 * one. Unreadable is its own answer, it stops the sync, and it is somebody's
 * decision what to do next rather than the engine's.
 */
open class BackupUnreadableException(
    message: String = "This backup was not written by this vault.",
) : Exception(message)

/**
 * This Google account holds more than one Keyweb vault file.
 *
 * Refused rather than resolved. Picking one means writing to it, and writing
 * to the wrong one strands everything in the other — so the only safe move is
 * to stop and let somebody look at their own Drive, where the files are
 * visible and dated.
 */
class TooManyBackupsException(count: Int) : BackupUnreadableException(
    "There are $count Keyweb backup files in this Google account, and Keyweb won't guess " +
        "which one is yours. Open Google Drive, look in the Keyweb folder, and remove or " +
        "rename the ones you don't want — the newest is usually the one to keep.",
)

/**
 * The copy this device can open is older than the copy it cannot.
 *
 * Unreadable in the sense that matters — the current vault cannot be got at —
 * but for the opposite reason from a backup written under somebody else's key,
 * and with the opposite remedy. Nothing is wrong with the file: this device is
 * simply missing the key to the newer copy, and the fix is to get that key.
 *
 * Its own type because the two were indistinguishable on screen, and the
 * action offered was "replace the backup" — which would have thrown away the
 * newer passwords this exists to protect.
 */
class BackupBehindException(message: String) : BackupUnreadableException(message)

data class RemoteRevision(val state: VaultState, val version: String)

/**
 * The encrypted remote. In production this wraps sync-kit-android's dataset
 * transport; [RemoteRevision.version] is the Drive change token captured at
 * read time.
 */
interface RemoteVaultStore {
    suspend fun read(): RemoteRevision?

    /**
     * Publish a revision. MUST throw [VersionConflictException] when
     * [expectedVersion] no longer matches the remote head, so a concurrent
     * writer's revision is never silently clobbered.
     */
    suspend fun write(state: VaultState, expectedVersion: String?): String
}

/**
 * In-memory storage. Used by tests, but the semantics here are the contract
 * every platform implementation must reproduce.
 */
class MemoryVaultStorage : VaultStorage {
    /** One entry per document; the vault is the one keyed by VAULT_DOCUMENT. */
    private val states = mutableMapOf<String, VaultState>()
    private val outboxes = mutableMapOf<String, MutableList<VaultOp>>()
    private var clock: Hlc? = null

    private fun outboxFor(documentId: String) = outboxes.getOrPut(documentId) { mutableListOf() }

    override suspend fun readState(documentId: String): VaultState =
        states[documentId] ?: emptyVault()

    override suspend fun commit(op: VaultOp, nextState: VaultState) {
        states[VAULT_DOCUMENT] = nextState
        outboxFor(VAULT_DOCUMENT) += op
    }

    override suspend fun commitAll(
        ops: List<VaultOp>,
        nextState: VaultState,
        documentId: String,
    ) {
        states[documentId] = nextState
        outboxFor(documentId) += ops
    }

    override suspend fun applyRemote(incoming: VaultState, documentId: String): VaultState {
        val joined = mergeVaults(states[documentId] ?: emptyVault(), incoming)
        states[documentId] = joined
        return joined
    }

    override suspend fun knownDocuments(): List<String> =
        (states.keys + outboxes.keys).filter { it != VAULT_DOCUMENT }.sorted()

    override suspend fun forgetDocument(documentId: String) {
        require(documentId != VAULT_DOCUMENT) { "The vault itself cannot be forgotten." }
        states.remove(documentId)
        outboxes.remove(documentId)
    }

    override suspend fun pending(documentId: String): List<VaultOp> =
        outboxFor(documentId).toList()

    override suspend fun ack(opIds: List<String>, documentId: String) {
        val drop = opIds.toSet()
        outboxFor(documentId).removeAll { it.opId in drop }
    }

    override suspend fun readClock(): Hlc? = clock

    override suspend fun writeClock(value: Hlc) {
        clock = value
    }

    /** Test helper: simulate a process restart with durable state intact. */
    fun fork(): MemoryVaultStorage {
        val next = MemoryVaultStorage()
        // Every document, not just the vault: a restart that forgot the shared
        // keyrings would look exactly like them never having been bound.
        next.states += states
        for ((documentId, ops) in outboxes) next.outboxFor(documentId) += ops
        next.clock = clock
        return next
    }
}
