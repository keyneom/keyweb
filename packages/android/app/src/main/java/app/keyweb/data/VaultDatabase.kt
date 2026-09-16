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
import app.keyweb.vault.PlaintextVaultCipher
import app.keyweb.vault.VaultCipher
import app.keyweb.vault.VaultStorage
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.keyweb.vault.VAULT_DOCUMENT
import app.keyweb.vault.emptyVault
import app.keyweb.vault.mergeVaults
import kotlinx.serialization.json.Json

/**
 * One row per document.
 *
 * `document` is the empty string for the vault, which is what the single row
 * of every existing database already is — so the migration is a column added
 * with that default and nothing moved.
 */
@Entity(tableName = "vault_state")
data class VaultStateRow(
    @PrimaryKey val document: String = VAULT_DOCUMENT,
    val json: String,
)

@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val json: String,
    /** Which document this operation belongs to; empty is the vault. */
    val document: String = VAULT_DOCUMENT,
)

@Entity(tableName = "meta")
data class MetaRow(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
abstract class VaultDao {

    @Query("SELECT json FROM vault_state WHERE document = :document")
    abstract suspend fun stateJson(document: String = VAULT_DOCUMENT): String?

    @Query("SELECT document FROM vault_state WHERE document != '' ORDER BY document")
    abstract suspend fun documentIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putState(row: VaultStateRow)

    @Insert
    abstract suspend fun appendOutbox(row: OutboxRow)

    @Query("SELECT * FROM outbox WHERE document = :document ORDER BY seq ASC")
    abstract suspend fun outbox(document: String = VAULT_DOCUMENT): List<OutboxRow>

    @Query("DELETE FROM outbox WHERE opId IN (:opIds)")
    abstract suspend fun deleteOutbox(opIds: List<String>)

    @Query("SELECT COUNT(*) FROM outbox")
    abstract suspend fun outboxSize(): Int

    @Query("SELECT DISTINCT document FROM outbox WHERE document != '' ORDER BY document")
    abstract suspend fun outboxDocumentIds(): List<String>

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
     * The same guarantee for a bulk change: one transaction, however many ops.
     *
     * A half-written bulk delete would leave the state saying the passwords
     * are gone while the outbox has no record of it, so the next sync would
     * quietly restore them from the remote.
     */
    @Transaction
    open suspend fun commitAll(
        stateJson: String,
        rows: List<OutboxRow>,
        document: String = VAULT_DOCUMENT,
    ) {
        putState(VaultStateRow(document = document, json = stateJson))
        for (row in rows) appendOutbox(row)
    }

    /**
     * Atomic read-join-write.
     *
     * Never a blind assignment: an edit committed while a sync was in flight
     * exists only in local state, and overwriting instead of joining is exactly
     * how a saved password silently disappears.
     */
    @Transaction
    open suspend fun join(
        incoming: VaultState,
        json: Json,
        cipher: VaultCipher,
        document: String = VAULT_DOCUMENT,
    ): VaultState {
        val current = stateJson(document)
            ?.let { json.decodeFromString(VaultState.serializer(), cipher.open(it)) }
            ?: emptyVault()
        val joined = mergeVaults(current, incoming)
        putState(
            VaultStateRow(
                document = document,
                json = cipher.seal(json.encodeToString(VaultState.serializer(), joined)),
            ),
        )
        return joined
    }
}

@Database(
    entities = [VaultStateRow::class, OutboxRow::class, MetaRow::class],
    version = 2,
    exportSchema = false,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        /**
         * Version 2 gave both tables a `document` column.
         *
         * Written by hand rather than destructively, because the alternative
         * Room offers is dropping the table — which here means deleting
         * somebody's passwords to add a column. Existing rows become the
         * vault, which is exactly what they already were.
         *
         * `vault_state` is rebuilt because its primary key changes from the
         * constant 0 to the document id; SQLite cannot alter a primary key in
         * place, so the table is recreated and its one row carried across.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN document TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE vault_state_new " +
                        "(document TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO vault_state_new (document, json) SELECT '', json FROM vault_state",
                )
                db.execSQL("DROP TABLE vault_state")
                db.execSQL("ALTER TABLE vault_state_new RENAME TO vault_state")
            }
        }

        fun open(context: Context, name: String = "keyweb-vault"): VaultDatabase =
            Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
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
    /**
     * Everything written through here is sealed first. Queued operations carry
     * passwords too, so the outbox is encrypted alongside the state; operation
     * ids stay in the clear so an acknowledgement needs no key.
     */
    private val cipher: VaultCipher = PlaintextVaultCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : VaultStorage {

    override suspend fun readState(documentId: String): VaultState =
        dao.stateJson(documentId)
            ?.let { json.decodeFromString(VaultState.serializer(), cipher.open(it)) }
            ?: emptyVault()

    /**
     * Documents other than the vault, from what is actually stored.
     *
     * The outbox is consulted as well as the state table, so a keyring bound
     * locally counts as known before its first write has been composed.
     */
    override suspend fun knownDocuments(): List<String> =
        (dao.documentIds() + dao.outboxDocumentIds()).distinct().sorted()

    override suspend fun commit(op: VaultOp, nextState: VaultState) {
        dao.commit(
            stateJson = cipher.seal(json.encodeToString(VaultState.serializer(), nextState)),
            opId = op.opId,
            opJson = cipher.seal(json.encodeToString(VaultOp.serializer(), op)),
        )
    }

    override suspend fun commitAll(
        ops: List<VaultOp>,
        nextState: VaultState,
        documentId: String,
    ) {
        if (ops.isEmpty()) return
        // The state is sealed once here rather than once per op, which is the
        // whole saving: it is the entire vault, and sealing it is the
        // expensive part of a commit.
        dao.commitAll(
            document = documentId,
            stateJson = cipher.seal(json.encodeToString(VaultState.serializer(), nextState)),
            rows = ops.map { op ->
                OutboxRow(
                    opId = op.opId,
                    json = cipher.seal(json.encodeToString(VaultOp.serializer(), op)),
                    document = documentId,
                )
            },
        )
    }

    override suspend fun applyRemote(incoming: VaultState, documentId: String): VaultState =
        dao.join(incoming, json, cipher, documentId)

    override suspend fun pending(documentId: String): List<VaultOp> =
        dao.outbox(documentId)
            .map { json.decodeFromString(VaultOp.serializer(), cipher.open(it.json)) }

    /** Operation ids are unique across documents, so this needs no filter. */
    override suspend fun ack(opIds: List<String>, documentId: String) {
        if (opIds.isNotEmpty()) dao.deleteOutbox(opIds)
    }

    override suspend fun readClock(): Hlc? = dao.meta(CLOCK_KEY)

    override suspend fun writeClock(value: Hlc) = dao.putMeta(MetaRow(CLOCK_KEY, value))
}
