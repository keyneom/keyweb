package app.keyweb.sharing

import com.keyneom.synckit.sharing.ProtectedSharingIdentityStore
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The recovery code as a second lock on the same identity.
 *
 * Every keyring is a file whose key is wrapped to its participants, and you
 * are a participant on every one of yours. So the printed code does not need
 * to read each file, and there is no second copy of anything for a device to
 * keep current: it only has to make you *you* again on a device that lost its
 * passkey. That is what this pins — that the code opens the very keypair the
 * passkey record holds, never a fresh one, because a fresh one is a stranger
 * that none of your files were ever wrapped to.
 */
class RecoveryLockTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Fixture(
        val secretBase64: String,
        val keyId: String,
        val fingerprint: String,
        val record: ProtectedSharingIdentityV1,
    )

    private fun fixture(): Fixture = json.decodeFromString(
        Fixture.serializer(),
        File("../../../fixtures/sharing-identity-v1.json").readText(),
    )

    /** App-data as it really is: one record per app id, side by side. */
    private class AppData(seed: ProtectedSharingIdentityV1) : ProtectedSharingIdentityStore {
        val records = mutableMapOf(seed.appId to seed)
        override suspend fun load(appId: String) = records[appId]
        override suspend fun save(record: ProtectedSharingIdentityV1) {
            records[record.appId] = record
        }
        override suspend fun delete(appId: String) {
            records.remove(appId)
        }
    }

    /** No activity, so no passkey sheet: the code path alone is under test. */
    private fun identity(store: AppData, secret: ByteArray) =
        KeywebSharingIdentity(store = store, passkey = SharingPasskey { null }, secret = { secret })

    @Test
    fun `writes a recovery lock beside the identity the first time it is opened`() = runTest {
        val fixture = fixture()
        val secret = java.util.Base64.getDecoder().decode(fixture.secretBase64)
        val store = AppData(fixture.record)

        identity(store, secret).get()

        assertNotNull(store.records[RECOVERY_APP_ID])
    }

    @Test
    fun `the code alone gives back the same person`() = runTest {
        val fixture = fixture()
        val secret = java.util.Base64.getDecoder().decode(fixture.secretBase64)
        val store = AppData(fixture.record)
        identity(store, secret).get()

        // A new device: nothing cached, only the printed code in hand.
        val recovered = identity(store, ByteArray(0)).recoverWith(secret)

        assertEquals(fixture.keyId, recovered.publicKey.keyId)
    }

    @Test
    fun `a wrong code opens nothing`() = runTest {
        val fixture = fixture()
        val secret = java.util.Base64.getDecoder().decode(fixture.secretBase64)
        val store = AppData(fixture.record)
        identity(store, secret).get()

        assertFailsWith<Exception> {
            identity(store, ByteArray(0)).recoverWith(ByteArray(20) { 9 })
        }
    }

    @Test
    fun `is written once, not again on every open`() = runTest {
        val fixture = fixture()
        val secret = java.util.Base64.getDecoder().decode(fixture.secretBase64)
        val store = AppData(fixture.record)
        identity(store, secret).get()
        val first = store.records[RECOVERY_APP_ID]

        identity(store, secret).get()

        assertEquals(first, store.records[RECOVERY_APP_ID])
    }
}
