package app.keyweb.data

import app.keyweb.vault.RemoteRevision
import app.keyweb.vault.RemoteUnavailableException
import app.keyweb.vault.RemoteVaultStore
import app.keyweb.vault.VaultState

/**
 * A remote that can be turned on after the engine is already running.
 *
 * Backup is set up mid-session, but [app.keyweb.vault.VaultSync] takes its
 * remote once at construction. Rebuilding the engine to attach one would mean
 * rebuilding its outbox and clock too — exactly the kind of moving part that
 * loses a pending edit. Swapping the delegate leaves everything else alone.
 *
 * Before a delegate is attached, every call fails as unavailable rather than
 * pretending to succeed, so nothing is ever reported as backed up when it is
 * only saved on the phone.
 */
class SwitchableRemote : RemoteVaultStore {

    @Volatile
    private var delegate: RemoteVaultStore? = null

    val configured: Boolean get() = delegate != null

    fun attach(remote: RemoteVaultStore) {
        delegate = remote
    }

    fun detach() {
        delegate = null
    }

    private fun require(): RemoteVaultStore =
        delegate ?: throw RemoteUnavailableException("Encrypted backup is not set up yet.")

    override suspend fun read(): RemoteRevision? = require().read()

    override suspend fun write(state: VaultState, expectedVersion: String?): String =
        require().write(state, expectedVersion)
}
