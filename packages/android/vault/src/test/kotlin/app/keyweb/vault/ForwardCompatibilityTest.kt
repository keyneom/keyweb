package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A vault written by a newer client must still open here.
 *
 * Devices update at different times. A phone left on an old build for months
 * will meet vaults containing fields it has never heard of — a crypto wallet's
 * seed phrase, a card's expiry, a field someone named themselves.
 *
 * This used to be fatal. Field keys were an enum, kotlinx serialization
 * enforces enum membership, and one unrecognised key threw
 * `SerializationException` for the whole document: not a hidden field, an
 * unreadable vault. Every password in it, gone from that device, until it was
 * updated — and the sync engine would have treated the failure as corruption.
 */
class ForwardCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vaultWithUnknownFields(): String = """
        {
          "items": {
            "wallet": {
              "id": "wallet",
              "keyring": { "value": "ring", "ts": "001700000000000-00000-new" },
              "deleted": { "value": false, "ts": "001700000000000-00000-new" },
              "fields": {
                "title": { "value": "Hardware wallet", "ts": "001700000000000-00000-new" },
                "seedPhrase": { "value": "twelve words here", "ts": "001700000000000-00000-new" },
                "derivationPath": { "value": "m/44'/60'/0'/0", "ts": "001700000000000-00000-new" },
                "secret:custom thing": { "value": "hidden", "ts": "001700000000000-00000-new" }
              },
              "history": []
            }
          },
          "keyrings": {
            "ring": {
              "id": "ring",
              "name": { "value": "Household", "ts": "001700000000000-00000-new" },
              "deleted": { "value": false, "ts": "001700000000000-00000-new" }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `opens a vault containing fields this version has never heard of`() {
        val state = json.decodeFromString(VaultState.serializer(), vaultWithUnknownFields())
        val item = assertNotNull(state.items["wallet"])

        assertEquals("Hardware wallet", item.field(Fields.TITLE))
        // Carried, not merely tolerated: an older client must be able to show
        // the value even without knowing how to present it specially.
        assertEquals("twelve words here", item.field("seedPhrase"))
        assertEquals("m/44'/60'/0'/0", item.field("derivationPath"))
    }

    @Test
    fun `carries unknown fields back out through a merge`() {
        val theirs = json.decodeFromString(VaultState.serializer(), vaultWithUnknownFields())

        // This device edits the one field it does understand, at a later time.
        val mine = applyOp(
            theirs,
            VaultOp.ItemPut(
                opId = "local",
                ts = "001700000000001-00000-old",
                itemId = "wallet",
                keyringId = "ring",
                fields = mapOf(Fields.TITLE to "Hardware wallet (renamed)"),
            ),
        )

        val merged = mergeVaults(mine, theirs)
        val item = assertNotNull(merged.items["wallet"])
        assertEquals("Hardware wallet (renamed)", item.field(Fields.TITLE))
        // Dropping these on the way back out would delete a seed phrase --
        // silently, on the next sync, from a device that never displayed it.
        assertEquals("twelve words here", item.field("seedPhrase"))
        assertEquals("hidden", item.field("secret:custom thing"))
    }

    @Test
    fun `re-serializes unknown fields unchanged`() {
        val state = json.decodeFromString(VaultState.serializer(), vaultWithUnknownFields())
        val round = json.decodeFromString(
            VaultState.serializer(),
            json.encodeToString(VaultState.serializer(), state),
        )
        assertEquals(fingerprint(state), fingerprint(round))
        assertEquals("twelve words here", round.items["wallet"]?.field("seedPhrase"))
    }

    @Test
    fun `treats the sensitive custom fields as secret`() {
        assertTrue(Fields.isSecret(Fields.PASSWORD))
        assertTrue(Fields.isSecret(Fields.OTP))
        assertTrue(Fields.isSecret("seedPhrase"))
        assertTrue(Fields.isSecret("privateKey"))
        assertTrue(Fields.isSecret("secret:anything the user named"))
        assertTrue(!Fields.isSecret(Fields.USERNAME))
        assertTrue(!Fields.isSecret("derivationPath"))
    }
}
