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
}
