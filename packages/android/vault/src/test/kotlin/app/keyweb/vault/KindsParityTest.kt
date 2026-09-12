package app.keyweb.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The two catalogues must agree, field key for field key.
 *
 * Keys are the contract, not the labels. A seed phrase saved under `seedPhrase`
 * on the phone and `seed_phrase` in the browser would be two independent
 * registers: both would sync, neither would fail, and someone would open the
 * other app to find half their wallet backup missing. Nothing about the CRDT
 * would notice, because to it they are simply two different fields.
 *
 * The shapes are compared too, because a field the browser masks and the phone
 * prints in the clear is the same kind of divergence with a worse outcome.
 */
@Serializable
private data class KindsFixture(val note: String = "", val kinds: List<KindFixture>)

@Serializable
private data class KindFixture(
    val id: String,
    val name: String,
    val summary: String,
    val fields: List<FieldFixture>,
)

@Serializable
private data class FieldFixture(val key: String, val label: String, val shape: String)

class KindsParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): KindsFixture {
        val file = File("../../../fixtures/kinds-v1.json")
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npm run fixture:kinds --workspace @keyweb/vault-core",
        )
        return json.decodeFromString(KindsFixture.serializer(), file.readText())
    }

    @Test
    fun `offers the same kinds as the TypeScript catalogue`() {
        assertEquals(fixture().kinds.map { it.id }, Kinds.all.map { it.id })
    }

    @Test
    fun `agrees on every field key, in the same order`() {
        for (expected in fixture().kinds) {
            val actual = Kinds.all.first { it.id == expected.id }
            assertEquals(
                expected.fields.map { it.key },
                actual.fields.map { it.key },
                "field keys differ for kind '${expected.id}'",
            )
        }
    }

    @Test
    fun `agrees on which fields are secret`() {
        for (expected in fixture().kinds) {
            val actual = Kinds.all.first { it.id == expected.id }
            for ((index, field) in expected.fields.withIndex()) {
                assertEquals(
                    field.shape.uppercase(),
                    actual.fields[index].shape.name,
                    "shape differs for ${expected.id}.${field.key}",
                )
            }
        }
    }

    @Test
    fun `agrees on the words shown to people`() {
        // Divergent labels are not a data hazard, but two apps calling the same
        // thing different names is exactly the confusion this app exists to
        // avoid.
        for (expected in fixture().kinds) {
            val actual = Kinds.all.first { it.id == expected.id }
            assertEquals(expected.name, actual.name)
            assertEquals(expected.summary, actual.summary)
            assertEquals(expected.fields.map { it.label }, actual.fields.map { it.label })
        }
    }

    @Test
    fun `falls back to a usable template for a kind it does not recognise`() {
        // An older build meeting an item kind added later must still show it.
        assertEquals("login", Kinds.of("some-kind-from-the-future").id)
        assertEquals("login", Kinds.of(null).id)
        assertEquals("wallet", Kinds.of("wallet").id)
    }

    @Test
    fun `flags a recovery phrase with the wrong number of words`() {
        val twelve = List(12) { "abandon" }.joinToString(" ")
        assertEquals(null, Kinds.checkPhrase(twelve))
        assertEquals(null, Kinds.checkPhrase(List(24) { "abandon" }.joinToString(" ")))
        // The mistake that loses money: a word missed while copying from paper.
        assertTrue(Kinds.checkPhrase(List(11) { "abandon" }.joinToString(" ")) != null)
        assertTrue(Kinds.checkPhrase(List(13) { "abandon" }.joinToString(" ")) != null)
        // Empty is not yet wrong -- nobody has typed anything.
        assertEquals(null, Kinds.checkPhrase("   "))
        // Ragged spacing is how a phrase actually arrives.
        assertEquals(12, Kinds.phraseWords("  ABANDON   abandon\nabandon " + twelve.substringAfter("abandon abandon abandon")).size)
    }
}
