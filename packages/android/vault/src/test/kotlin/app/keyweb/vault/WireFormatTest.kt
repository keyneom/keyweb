package app.keyweb.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cross-platform wire compatibility.
 *
 * Web and Android read and write the same encrypted Drive payload, so the two
 * implementations must agree exactly. This reads a fixture produced by the
 * TypeScript implementation (`npm run fixture --workspace @keyweb/vault-core`)
 * and asserts Kotlin parses it identically *and* computes the same fingerprint.
 *
 * The fingerprint half matters as much as the parse: fingerprint equality is
 * what decides whether a sync skips an upload. If the two platforms disagreed,
 * each would consider the other's published revision "different" and republish
 * it forever, burning quota and churning revisions on every sync.
 */
@Serializable
private data class WireFixture(val state: VaultState, val fingerprint: String)

class WireFormatTest {

    private val json = Json { ignoreUnknownKeys = false }

    private fun loadFixture(): WireFixture {
        val file = File("../../../fixtures/wire-v1.json")
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npx vite-node packages/vault-core/scripts/emit-wire-fixture.ts",
        )
        return json.decodeFromString(WireFixture.serializer(), file.readText())
    }

    @Test
    fun `parses a payload written by the TypeScript implementation`() {
        val fixture = loadFixture()
        val state = fixture.state

        val chase = assertNotNull(state.items["chase"], "expected the chase item")
        assertEquals("Chase Bank", chase.field(ItemField.TITLE))
        assertEquals("maria@example.com", chase.field(ItemField.USERNAME))
        assertEquals("rotated", chase.field(ItemField.PASSWORD))
        assertEquals("ring", chase.keyring.value)
        assertEquals(false, chase.deleted.value)

        // The superseded password must survive the round trip; it is the undo
        // window for an accidental overwrite.
        val previous = chase.history.firstOrNull { it.field == ItemField.PASSWORD }
        assertEquals("original", assertNotNull(previous).value)

        val netflix = assertNotNull(state.items["netflix"])
        assertEquals(true, netflix.deleted.value, "the tombstone must survive")

        val ring = assertNotNull(state.keyrings["ring"])
        assertEquals("Household", ring.name.value)

        // A deleted item is retained but not shown.
        assertEquals(listOf("chase"), visibleItems(state).map { it.id })
    }

    @Test
    fun `computes the same fingerprint as the TypeScript implementation`() {
        val fixture = loadFixture()
        assertEquals(fixture.fingerprint, fingerprint(fixture.state))
    }

    @Test
    fun `re-serialises to a payload the TypeScript implementation would accept`() {
        val fixture = loadFixture()
        val roundTripped = json.decodeFromString(
            VaultState.serializer(),
            json.encodeToString(VaultState.serializer(), fixture.state),
        )
        assertEquals(fixture.fingerprint, fingerprint(roundTripped))
        assertEquals(fixture.state, roundTripped)
    }

    @Test
    fun `merging the fixture with itself changes nothing`() {
        val fixture = loadFixture()
        assertEquals(
            fixture.fingerprint,
            fingerprint(mergeVaults(fixture.state, fixture.state)),
        )
    }
}
