package app.keyweb.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import app.keyweb.vault.Hlc
import app.keyweb.vault.VaultOp
import app.keyweb.vault.VaultState
import app.keyweb.vault.VaultStorage
import app.keyweb.vault.emptyVault
import app.keyweb.vault.mergeVaults
import kotlinx.serialization.json.Json

@Entity(tableName = "vault_state")
data class VaultStateRow(
    @PrimaryKey val id: Int = 0,
    val json: String,
)

@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val json: String,
)

@Entity(tableName = "meta")
data class MetaRow(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
abstract class VaultDao {

    @Query("SELECT json FROM vault_state WHERE id = 0")
    abstract suspend fun stateJson(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putState(row: VaultStateRow)

    @Insert
    abstract suspend fun appendOutbox(row: OutboxRow)

    @Query("SELECT * FROM outbox ORDER BY seq ASC")
    abstract suspend fun outbox(): List<OutboxRow>

    @Query("DELETE FROM outbox WHERE opId IN (:opIds)")
    abstract suspend fun deleteOutbox(opIds: List<String>)

    @Query("SELECT COUNT(*) FROM outbox")
    abstract suspend fun outboxSize(): Int

    @Query("SELECT value FROM meta WHERE key = :key")
    abstract suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(row: MetaRow)

    /**
     * Atomic: the new state and the outbox append commit together or not at all.
     *
     * If the state landed but the outbox append did not, a crash before the next
     * sync would lose the edit from the cloud forever while the device kept
     * showing it. @Transaction is what makes that impossible.
     */
    @Transaction
    open suspend fun commit(stateJson: String, opId: String, opJson: String) {
        putState(VaultStateRow(json = stateJson))
        appendOutbox(OutboxRow(opId = opId, json = opJson))
    }

    /**
     * Atomic read-join-write.
     *
     * Never a blind assignment: an edit committed while a sync was in flight
     * exists only in local state, and overwriting instead of joining is exactly
     * how a saved password silently disappears.
     */
    @Transaction
    open suspend fun join(incoming: VaultState, json: Json): VaultState {
        val current = stateJson()
            ?.let { json.decodeFromString(VaultState.serializer(), it) }
            ?: emptyVault()
        val joined = mergeVaults(current, incoming)
        putState(VaultStateRow(json = json.encodeToString(VaultState.serializer(), joined)))
        return joined
    }
}

@Database(
    entities = [VaultStateRow::class, OutboxRow::class, MetaRow::class],
    version = 1,
    exportSchema = false,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        fun open(context: Context, name: String = "keyweb-vault"): VaultDatabase =
            Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, name)
                .build()
    }
}

private const val CLOCK_KEY = "clock"

/**
 * Room implementation of the vault storage contract. The two atomicity-critical
 * operations delegate to @Transaction DAO methods above.
 */
class RoomVaultStorage(
    private val dao: VaultDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : VaultStorage {

    override suspend fun readState(): VaultState =
        dao.stateJson()?.let { json.decodeFromString(VaultState.serializer(), it) } ?: emptyVault()

    override suspend fun commit(op: VaultOp, nextState: VaultState) {
        dao.commit(
            stateJson = json.encodeToString(VaultState.serializer(), nextState),
            opId = op.opId,
            opJson = json.encodeToString(VaultOp.serializer(), op),
        )
    }

    override suspend fun applyRemote(incoming: VaultState): VaultState = dao.join(incoming, json)

    override suspend fun pending(): List<VaultOp> =
        dao.outbox().map { json.decodeFromString(VaultOp.serializer(), it.json) }

    override suspend fun ack(opIds: List<String>) {
        if (opIds.isNotEmpty()) dao.deleteOutbox(opIds)
    }

    override suspend fun readClock(): Hlc? = dao.meta(CLOCK_KEY)

    override suspend fun writeClock(value: Hlc) = dao.putMeta(MetaRow(CLOCK_KEY, value))
}
