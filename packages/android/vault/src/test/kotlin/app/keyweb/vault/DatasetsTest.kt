package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Moving a keyring into its own document.
 *
 * The same cases the web suite asks, because the two platforms have to agree
 * about where a password lives. Getting this wrong does not throw: it puts
 * someone's password in the wrong file, where the other device never looks.
 */
class DatasetsTest {

    private val clock = Clock("test")
    private var n = 0
    private fun id() = "op-${++n}"

    private fun ring(keyringId: String, name: String) =
        VaultOp.KeyringPut(id(), clock.now(), keyringId, name)

    private fun put(itemId: String, keyringId: String) =
        VaultOp.ItemPut(id(), clock.now(), itemId, keyringId, mapOf("title" to itemId))

    private fun bind(keyringId: String, datasetId: String) =
        VaultOp.KeyringBind(id(), clock.now(), keyringId, datasetId)

    private fun vault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            ring("personal", "Just mine"),
            ring("house", "Household"),
            put("bank", "personal"),
            put("wifi", "house"),
            put("power", "house"),
        ),
    )

    @Test
    fun `is off until something says otherwise`() {
        // Invisible to a vault that shares nothing, which is every vault until
        // someone presses a button.
        val state = vault()
        assertNull(datasetOf(state.keyrings["house"]))
        assertEquals(emptyList(), boundDatasets(state))
    }

    @Test
    fun `extracts exactly that keyring and its items`() {
        val state = applyOps(vault(), listOf(bind("house", "ds-house")))
        val dataset = extractDataset(state, "house")

        assertEquals(setOf("house"), dataset.keyrings.keys)
        assertEquals(setOf("power", "wifi"), dataset.items.keys)
        // Nothing of the other keyring leaks into the file that will be shared.
        assertNull(dataset.items["bank"])
        assertNull(dataset.keyrings["personal"])
    }

    @Test
    fun `puts back together into exactly what it was`() {
        val before = applyOps(vault(), listOf(bind("house", "ds-house")))
        val dataset = extractDataset(before, "house")
        val rest = withoutDatasetItems(before, "house")

        val after = composeVault(rest, mapOf("ds-house" to dataset))
        assertEquals(
            visibleItems(before).map { it.id }.sorted(),
            visibleItems(after).map { it.id }.sorted(),
        )
    }

    @Test
    fun `carries tombstones into the dataset`() {
        val state = applyOps(
            vault(),
            listOf(bind("house", "ds-house"), VaultOp.ItemDelete(id(), clock.now(), "power")),
        )
        assertTrue(extractDataset(state, "house").items.getValue("power").deleted.value)

        val rejoined = composeVault(
            withoutDatasetItems(state, "house"),
            mapOf("ds-house" to extractDataset(state, "house")),
        )
        assertEquals(listOf("bank", "wifi"), visibleItems(rejoined).map { it.id }.sorted())
    }

    @Test
    fun `two devices binding the same keyring at once agree on one dataset`() {
        val base = vault()
        val a = applyOps(base, listOf(bind("house", "ds-from-phone")))
        val b = applyOps(base, listOf(bind("house", "ds-from-laptop")))

        assertEquals(
            datasetOf(mergeVaults(a, b).keyrings["house"]),
            datasetOf(mergeVaults(b, a).keyrings["house"]),
        )
    }

    @Test
    fun `can be put back in the vault`() {
        // Unsharing has to be possible, or sharing once is a one-way door.
        val state = applyOps(vault(), listOf(bind("house", "ds-house"), bind("house", "")))
        assertNull(datasetOf(state.keyrings["house"]))
    }

    @Test
    fun `a vault written before datasets existed still decodes`() {
        // The field is new. Every vault on every phone predates it, and one
        // that refused to load would be a vault nobody could open.
        val json = Json { ignoreUnknownKeys = true }
        val old = """
            {"items":{},"keyrings":{"r":{"id":"r",
             "name":{"value":"Household","ts":"001700000000000-00000-a"},
             "deleted":{"value":false,"ts":"000000000000000-00000-"}}}}
        """.trimIndent()

        val state = json.decodeFromString(VaultState.serializer(), old)
        assertEquals("Household", state.keyrings.getValue("r").name.value)
        assertNull(datasetOf(state.keyrings["r"]))
    }

    @Test
    fun `does not let a shared document contribute another keyring`() {
        val before = applyOps(vault(), listOf(bind("house", "ds-house")))
        val rest = withoutDatasetItems(before, "house")
        val house = extractDataset(before, "house")
        val later = "999999999999999-00000-evil"
        val poisoned = house.copy(
            items = house.items + ("bank" to ItemRecord(
                id = "bank",
                keyring = Reg("personal", later),
                fields = mapOf("password" to Reg("s3cret", later)),
                deleted = Reg(false, HLC_ZERO),
            )),
            keyrings = house.keyrings + mapOf(
                "personal" to KeyringRecord(
                    id = "personal",
                    name = Reg("Stolen", later),
                    deleted = Reg(false, later),
                    dataset = Reg("ds-house", later),
                ),
                "house" to house.keyrings.getValue("house").copy(
                    dataset = Reg("ds-evil", later),
                ),
            ),
        )

        val after = composeVault(rest, mapOf("ds-house" to poisoned))

        assertEquals("Just mine", after.keyrings["personal"]?.name?.value)
        assertNull(datasetOf(after.keyrings["personal"]))
        assertEquals("ds-house", datasetOf(after.keyrings["house"]))
        assertNull(datasetForItem(after, "personal"))
        assertNull(after.items["bank"]?.field("password"))
        assertEquals("wifi", after.items["wifi"]?.field("title"))
    }
}
