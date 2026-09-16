package app.keyweb.sharing

import com.keyneom.synckit.sharing.ProtectedSharingIdentityCrypto
import com.keyneom.synckit.sharing.ProtectedSharingIdentityStore
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One person is one participant, on their phone and in their browser.
 *
 * This is the property the whole sharing design rests on, and its failure has
 * no error message: a phone that derived a *different* identity would look
 * entirely normal, right up to the moment it could not open a keyring its owner
 * had shared from a browser — or worse, silently became a second person nobody
 * had invited to anything.
 *
 * So the browser writes a real record here and the phone unwraps that exact
 * file. Both sides being "HKDF then AES-GCM" proves nothing: the HKDF label,
 * the salt, the AAD over the record header and the packing of the two private
 * keys all have to agree too.
 */
class SharingIdentityInteropTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = File("../../../fixtures")

    @Serializable
    private data class Fixture(
        val secretBase64: String,
        val keyId: String,
        val fingerprint: String,
        val record: ProtectedSharingIdentityV1,
    )

    private fun fixture(): Fixture {
        val file = File(fixtures, "sharing-identity-v1.json")
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npm run fixture:sharing --workspace @keyweb/web",
        )
        return json.decodeFromString(Fixture.serializer(), file.readText())
    }

    private class MemoryStore(private var record: ProtectedSharingIdentityV1?) :
        ProtectedSharingIdentityStore {
        var saves = 0
            private set

        override suspend fun load(appId: String) = record
        override suspend fun save(record: ProtectedSharingIdentityV1) {
            saves += 1
            this.record = record
        }

        override suspend fun delete(appId: String) {
            record = null
        }
    }

    @Test
    fun `opens the identity the browser wrapped, and is the same person`() = runTest {
        val fixture = fixture()
        val secret = java.util.Base64.getDecoder().decode(fixture.secretBase64)
        val store = MemoryStore(fixture.record)

        val identity = KeywebSharingIdentity(store = store, secret = { secret }).get()

        assertEquals(fixture.keyId, identity.publicKey.keyId)
        // Loading must never write: a save here would mean the phone decided
        // the record needed replacing, which is how a second identity starts.
        assertEquals(0, store.saves)
    }

    @Test
    fun `shows the same six characters the browser shows`() = runTest {
        val fixture = fixture()
        assertEquals(fixture.fingerprint, KeywebSharingIdentity.fingerprint(fixture.keyId))
    }

    @Test
    fun `does not open with the wrong code`() = runTest {
        val store = MemoryStore(fixture().record)
        val wrong = ByteArray(20) { 9 }
        var threw = false
        try {
            KeywebSharingIdentity(store = store, secret = { wrong }).get()
        } catch (cause: Exception) {
            threw = true
        }
        assertTrue(threw, "A wrong recovery code must not unwrap the sharing identity.")
        assertEquals(0, store.saves)
    }

    /**
     * The failure the store exists to prevent. If an unreachable Drive read as
     * "there is no identity", the app would helpfully make a second one — a
     * different participant, holding none of the first one's grants.
     */
    @Test
    fun `says unreachable rather than absent, so no second identity is minted`() = runTest {
        val offline = object : ProtectedSharingIdentityStore {
            override suspend fun load(appId: String): ProtectedSharingIdentityV1 =
                throw java.io.IOException("network unreachable")

            override suspend fun save(record: ProtectedSharingIdentityV1) =
                throw java.io.IOException("network unreachable")

            override suspend fun delete(appId: String) = Unit
        }
        val local = MemoryStore(null)
        val store = KeywebSharingIdentityStore(local = local, remote = offline)

        var threw = false
        try {
            KeywebSharingIdentity(store = store, secret = { ByteArray(20) }).getOrCreate()
        } catch (cause: Exception) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0, local.saves)
    }

    @Test
    fun `reads the cached copy first, so a shared keyring opens with no network`() = runTest {
        val fixture = fixture()
        val offline = object : ProtectedSharingIdentityStore {
            override suspend fun load(appId: String): ProtectedSharingIdentityV1 =
                throw java.io.IOException("network unreachable")

            override suspend fun save(record: ProtectedSharingIdentityV1) =
                throw java.io.IOException("network unreachable")

            override suspend fun delete(appId: String) = Unit
        }
        val store = KeywebSharingIdentityStore(
            local = MemoryStore(fixture.record),
            remote = offline,
        )

        val secret = java.util.Base64.getDecoder().decode(fixture.secretBase64)
        val identity = KeywebSharingIdentity(store = store, secret = { secret }).get()
        assertEquals(fixture.keyId, identity.publicKey.keyId)
    }

    @Test
    fun `parses the record the browser wrote without reshaping it`() {
        val fixture = fixture()
        val parsed = ProtectedSharingIdentityCrypto.parse(fixture.record)
        assertEquals(fixture.keyId, parsed.publicKey.keyId)
        assertEquals("recovery", parsed.credentialId)
    }
}
