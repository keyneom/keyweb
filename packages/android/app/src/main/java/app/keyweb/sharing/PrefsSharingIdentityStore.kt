package app.keyweb.sharing

import android.content.SharedPreferences
import com.keyneom.synckit.sharing.ProtectedSharingIdentityCrypto
import com.keyneom.synckit.sharing.ProtectedSharingIdentityStore
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1

/**
 * This phone's cached copy of the wrapped sharing identity.
 *
 * Plain preferences, deliberately. What is stored is the record exactly as it
 * sits in Drive: the private keys inside it are already AES-GCM encrypted under
 * a key derived from the recovery secret, and this file has no more of that
 * secret than Google does. Encrypting it a second time with a Keystore key
 * would bind the cache to this device — which is the opposite of the point,
 * since the whole reason the record exists in this shape is that it must be
 * readable on every device its owner has.
 *
 * It is a cache and nothing more. Losing it costs one Drive round trip; it is
 * kept so that a shared keyring still opens with no network at all.
 */
class PrefsSharingIdentityStore(
    private val prefs: SharedPreferences,
) : ProtectedSharingIdentityStore {

    override suspend fun load(appId: String): ProtectedSharingIdentityV1? =
        prefs.getString(key(appId), null)?.let {
            runCatching { ProtectedSharingIdentityCrypto.parse(it) }.getOrNull()
        }

    override suspend fun save(record: ProtectedSharingIdentityV1) {
        prefs.edit().putString(key(record.appId), encode(record)).apply()
    }

    override suspend fun delete(appId: String) {
        prefs.edit().remove(key(appId)).apply()
    }

    private fun key(appId: String) = "sharing-identity:$appId"

    private fun encode(record: ProtectedSharingIdentityV1): String =
        kotlinx.serialization.json.Json.encodeToString(
            ProtectedSharingIdentityV1.serializer(),
            record,
        )
}
