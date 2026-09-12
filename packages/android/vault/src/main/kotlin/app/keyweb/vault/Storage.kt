package app.keyweb.vault

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
    suspend fun readState(): VaultState

    /** Atomic: persist [nextState] and append [op] to the outbox together. */
    suspend fun commit(op: VaultOp, nextState: VaultState)

    /** Atomic: join [incoming] into the current state and return the result. */
    suspend fun applyRemote(incoming: VaultState): VaultState

    /** Operations not yet proven present in a published revision, oldest first. */
    suspend fun pending(): List<VaultOp>

    /** Drop operations now known to be in a published revision. */
    suspend fun ack(opIds: List<String>)

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
    private var state: VaultState = emptyVault()
    private val outbox = mutableListOf<VaultOp>()
    private var clock: Hlc? = null

    override suspend fun readState(): VaultState = state

    override suspend fun commit(op: VaultOp, nextState: VaultState) {
        state = nextState
        outbox += op
    }

    override suspend fun applyRemote(incoming: VaultState): VaultState {
        state = mergeVaults(state, incoming)
        return state
    }

    override suspend fun pending(): List<VaultOp> = outbox.toList()

    override suspend fun ack(opIds: List<String>) {
        val drop = opIds.toSet()
        outbox.removeAll { it.opId in drop }
    }

    override suspend fun readClock(): Hlc? = clock

    override suspend fun writeClock(value: Hlc) {
        clock = value
    }

    /** Test helper: simulate a process restart with durable state intact. */
    fun fork(): MemoryVaultStorage {
        val next = MemoryVaultStorage()
        next.state = state
        next.outbox += outbox
        next.clock = clock
        return next
    }
}
