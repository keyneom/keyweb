package app.keyweb.data

/**
 * The Drive operations the backup needs, as a seam.
 *
 * [DriveVaultRemote] carries rules that decide whether someone's backup
 * survives — carrying forward an envelope it cannot rewrite, refusing a write
 * whose revision moved. Those rules have to be tested, and testing them against
 * live Google would prove nothing repeatable, so the transport sits behind this
 * and a fake takes its place in the suite.
 */
interface DriveFiles {
    /** The id of the file carrying these markers, or null if there is none. */
    suspend fun findFile(appProperties: Map<String, String>): String?

    /**
     * Every file this account has handed over.
     *
     * Under `drive.file` the answer is exactly the files the app created or was
     * granted through the Picker — so this is the same question the web app
     * asks, with the same answer, and neither platform has to keep or sync a
     * list of its own.
     */
    suspend fun listFiles(): List<DriveFile>

    suspend fun readText(fileId: String): String

    /** The version token both platforms agree on. */
    suspend fun writeHead(fileId: String): WriteHead

    suspend fun createFolder(name: String, appProperties: Map<String, String>): String

    suspend fun create(
        name: String,
        content: String,
        parentId: String?,
        appProperties: Map<String, String>,
    ): String

    suspend fun write(fileId: String, content: String)

    /** `headRevisionId` when Drive supplies one; the etag is the fallback. */
    data class WriteHead(val etag: String, val headRevisionId: String?)

    data class DriveFile(
        val fileId: String,
        val name: String,
        val modifiedAtMs: Long?,
        /** Keyweb's own files carry a marker, so its backup is never offered. */
        val keywebMarker: String?,
        /**
         * How big the sealed file is, when Drive said.
         *
         * The nearest thing to "what is in it" that can be had without a key:
         * an empty vault seals to about a kilobyte, a real one does not.
         */
        val bytes: Long? = null,
    )
}
