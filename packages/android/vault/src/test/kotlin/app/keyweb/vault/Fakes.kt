package app.keyweb.vault

import kotlinx.coroutines.delay

/**
 * A remote that behaves the way Google Drive behaves at its worst: writes take
 * real time, another device can land a revision mid-flight, and the connection
 * can vanish. Used to prove the sync engine under conditions we cannot
 * reproduce on demand against live Drive.
 */
class FakeRemote : RemoteVaultStore {
    private var state: VaultState? = null
    private var version = 0

    /** Runs during the gap between read and write, to inject a race. */
    var onBeforeWrite: (suspend () -> Unit)? = null
    var readDelayMs: Long = 0
    var writeDelayMs: Long = 0
    var offline = false

    /** Reject the next N writes with a conflict, whatever the token. */
    var forceConflicts = 0

    var writes = 0
        private set
    var rejectedWrites = 0
        private set

    override suspend fun read(): RemoteRevision? {
        if (offline) throw RemoteUnavailableException()
        if (readDelayMs > 0) delay(readDelayMs)
        val current = state ?: return null
        return RemoteRevision(current, version.toString())
    }

    override suspend fun write(state: VaultState, expectedVersion: String?): String {
        if (offline) throw RemoteUnavailableException()
        onBeforeWrite?.invoke()
        if (writeDelayMs > 0) delay(writeDelayMs)

        if (forceConflicts > 0) {
            forceConflicts -= 1
            rejectedWrites += 1
            throw VersionConflictException()
        }
        val current = this.state?.let { version.toString() }
        if (current != expectedVersion) {
            rejectedWrites += 1
            throw VersionConflictException(
                "Expected version ${expectedVersion ?: "none"} but the remote is at ${current ?: "none"}.",
            )
        }
        this.state = state
        version += 1
        writes += 1
        return version.toString()
    }

    /** Simulate another device publishing a revision behind our back. */
    fun landForeignRevision(state: VaultState) {
        this.state = state
        version += 1
    }

    fun snapshot(): VaultState = state ?: emptyVault()
}

/** Deterministic id generator so failures are reproducible. */
fun seqIds(prefix: String): () -> String {
    var n = 0
    return { "$prefix-${++n}" }
}
