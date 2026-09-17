package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two ways an import can quietly corrupt what it is importing.
 *
 * Both were found by looking at a real vault on a phone rather than by a test,
 * which is why they are pinned here: the symptom in each case is a field that
 * is still *present* and still syncs, so nothing fails and nothing is missing
 * — it is only wrong, on the one path where the original file gets deleted
 * afterwards. Parity with the web's `keepass.test.ts`.
 */
class FieldNamesTest {

    @Test
    fun `a field cannot wear one of Keyweb's own prefixes`() {
        // `file:` is how a password points at an attached file. Left alone,
        // this one became an attachment whose bytes never existed — hidden
        // from the detail screen and the editor as plumbing, and listed under
        // Files as permanently "still arriving".
        assertEquals("custom:file:sneaky", storedFieldName("file:sneaky", secret = false))
        // And `secret:` arrived masked though nobody had protected it.
        assertEquals("custom:secret:Already", storedFieldName("secret:Already", secret = false))
        assertFalse(Fields.isSecret("custom:secret:Already"))
        // A name colliding with one of Keyweb's own keys still cannot act like
        // the real one: a field called "folder" must not move the entry.
        assertEquals("custom:folder", storedFieldName("folder", secret = false))
        assertEquals("secret:custom:folder", storedFieldName("folder", secret = true))
        // Ordinary names are left exactly as they were typed.
        assertEquals("Account number", storedFieldName("Account number", secret = false))
        assertEquals("secret:Backup PIN", storedFieldName("Backup PIN", secret = true))

        // Whatever the key, the name its owner gave it is what shows.
        assertEquals("file:sneaky", fieldLabel("custom:file:sneaky"))
        assertEquals("folder", fieldLabel("secret:custom:folder"))
    }

    @Test
    fun `an earlier import's name for a field is retired`() {
        // The vault as an older build left it: every custom field stored as
        // `secret:<name>` whether or not KeePass had protected it, so an
        // account number arrived masked and under a key today's import never
        // writes.
        val item = ItemRecord(
            id = "kdbx:abc",
            keyring = Reg("ring", "001700000000000-00000-a"),
            fields = mapOf(
                "title" to Reg("Chase Bank", "001700000000001-00000-a"),
                "secret:Account number" to Reg("00112233", "001700000000001-00000-a"),
            ),
            deleted = Reg(false, HLC_ZERO),
        )

        val fresh = mapOf<ItemField, String>(
            "title" to "Chase Bank",
            "Account number" to "00112233",
        )

        // One field, not two. Without this the person sees their account
        // number twice — once masked under the old key, once correctly — with
        // no way to tell which is which or which a later edit will change.
        assertEquals(mapOf("secret:Account number" to ""), supersededAliases(item, fresh))
    }

    @Test
    fun `a field the import is not touching is left alone`() {
        val item = ItemRecord(
            id = "kdbx:abc",
            keyring = Reg("ring", "001700000000000-00000-a"),
            fields = mapOf(
                // Added by hand here, and named nothing the file mentions.
                "secret:Door code" to Reg("1234", "001700000000001-00000-a"),
                // Already blank: writing another blank would be noise in the
                // history for a field that has nothing left to supersede.
                "secret:Gone" to Reg("", "001700000000001-00000-a"),
            ),
            deleted = Reg(false, HLC_ZERO),
        )
        assertTrue(supersededAliases(item, mapOf("Gone" to "back")).containsKey("secret:Gone").not())
        assertTrue(supersededAliases(item, mapOf("title" to "x")).isEmpty())
    }
}
