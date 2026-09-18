package app.keyweb.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.keyweb.vault.TooManyBackupsException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The slice of the Drive REST API Keyweb needs.
 *
 * Deliberately a direct port of sync-kit's `GoogleDriveFileStore` rather than a
 * fresh design: both platforms have to find *the same file*. That means the
 * same `appProperties` markers, the same folder name, the same query shape and
 * the same version token. A store that merely worked would quietly give the
 * phone its own separate backup, and the two would never converge.
 *
 * Written against HttpURLConnection so the app pulls in no HTTP library for
 * five endpoints.
 */
class DriveClient(
    private val token: suspend () -> String,
    /**
     * Which vault file this phone was told to use, when there are several.
     *
     * Supplied rather than read here, because where the answer is kept is the
     * app's business and this class only needs to be able to ask.
     */
    private val chosenFile: (() -> String?)? = null,
) : DriveFiles {

    private val json = Json { ignoreUnknownKeys = true }

    class DriveException(message: String, val status: Int = 0) : IOException(message)

    override suspend fun findFile(appProperties: Map<String, String>): String? {
        val clauses = buildList {
            add("trashed = false")
            appProperties.forEach { (key, value) ->
                add("appProperties has { key='${escape(key)}' and value='${escape(value)}' }")
            }
        }
        val query = buildString {
            append(FILES)
            append("?spaces=drive&corpora=user&pageSize=100")
            append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
            append("&fields=").append(encode("files(id,name)"))
            append("&q=").append(encode(clauses.joinToString(" and ")))
        }
        val body = json.parseToJsonElement(get(query)).jsonObject
        val files = body["files"]?.jsonArray ?: emptyList()

        /*
         * More than one file carrying this marker is not a choice to make.
         *
         * This preferred the canonical name and otherwise took the first — an
         * arbitrary pick from a list Drive returns in no promised order. Two
         * devices can land on two different files and each be perfectly
         * consistent: both sync, both succeed, both report themselves backed
         * up, and they hold different vaults. No error appears anywhere,
         * because from inside either one nothing is wrong.
         *
         * A vault file is the *whole vault* sealed as one blob, so a second
         * one is a parallel vault rather than more of this one. Stopping is
         * the only safe answer: picking means writing, and writing to the
         * wrong one strands everything in the other.
         */
        if (appProperties == VAULT_MARKER && files.size > 1) {
            // A choice already made is honoured; otherwise somebody is asked.
            val ids = files.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            val chosen = chosenFile?.invoke()
            if (chosen != null && ids.contains(chosen)) return chosen
            throw TooManyBackupsException(files.size)
        }

        val match = files.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content == VAULT_FILE_NAME
        } ?: files.firstOrNull()
        return match?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    override suspend fun listFiles(): List<DriveFiles.DriveFile> {
        val query = buildString {
            append(FILES)
            append("?spaces=drive&corpora=user&pageSize=200")
            append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
            append("&fields=").append(encode("files(id,name,modifiedTime,appProperties)"))
            append("&q=").append(encode("trashed = false"))
        }
        val body = json.parseToJsonElement(get(query)).jsonObject
        return (body["files"]?.jsonArray ?: emptyList()).mapNotNull { element ->
            val file = element.jsonObject
            val id = file["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            DriveFiles.DriveFile(
                fileId = id,
                name = file["name"]?.jsonPrimitive?.content.orEmpty(),
                modifiedAtMs = file["modifiedTime"]?.jsonPrimitive?.content?.let {
                    runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                },
                keywebMarker = file["appProperties"]?.jsonObject
                    ?.get("keyweb")?.jsonPrimitive?.content,
            )
        }
    }

    override suspend fun readText(fileId: String): String =
        get("$FILES/${encode(fileId)}?alt=media&supportsAllDrives=true")

    /** The raw bytes of a file, for anything that is not text -- a .kdbx. */
    suspend fun readBytes(fileId: String): ByteArray =
        sendBytes("$FILES/${encode(fileId)}?alt=media&supportsAllDrives=true")

    /**
     * The version token, taken from Drive **v2**.
     *
     * v3 rarely exposes an ETag and CORS hides it in a browser, so the web side
     * settled on v2's `headRevisionId`. Android could read an ETag, but it must
     * agree with the web on which token means "unchanged" or the two would read
     * each other's revisions as conflicts.
     */
    override suspend fun writeHead(fileId: String): DriveFiles.WriteHead {
        val body = json.parseToJsonElement(
            get("$FILES_V2/${encode(fileId)}?fields=etag,headRevisionId"),
        ).jsonObject
        val etag = body["etag"]?.jsonPrimitive?.content
            ?: throw DriveException("Google Drive did not return a version for this file.")
        return DriveFiles.WriteHead(etag, body["headRevisionId"]?.jsonPrimitive?.content)
    }

    override suspend fun createFolder(name: String, appProperties: Map<String, String>): String {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            put("writersCanShare", false)
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }
        val response = send(
            url = "$FILES?fields=id&supportsAllDrives=true",
            method = "POST",
            contentType = "application/json",
            body = metadata.toString().toByteArray(Charsets.UTF_8),
        )
        return fileIdOf(response)
    }

    override suspend fun create(
        name: String,
        content: String,
        parentId: String?,
        appProperties: Map<String, String>,
    ): String {
        val boundary = "keyweb-${UUID.randomUUID()}"
        val metadata = buildJsonObject {
            put("name", name)
            put("writersCanShare", false)
            if (parentId != null) putJsonArray("parents") { add(JsonPrimitive(parentId)) }
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--$boundary\r\nContent-Type: $CONTENT_TYPE\r\n\r\n")
            append(content)
            append("\r\n--$boundary--")
        }
        val response = send(
            url = "$UPLOAD?uploadType=multipart&fields=id&supportsAllDrives=true",
            method = "POST",
            contentType = "multipart/related; boundary=$boundary",
            body = body.toByteArray(Charsets.UTF_8),
        )
        return fileIdOf(response)
    }

    override suspend fun write(fileId: String, content: String) {
        send(
            url = "$UPLOAD/${encode(fileId)}?uploadType=media&fields=id&supportsAllDrives=true",
            method = "PATCH",
            contentType = CONTENT_TYPE,
            body = content.toByteArray(Charsets.UTF_8),
        )
    }

    private fun fileIdOf(response: String): String =
        json.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content
            ?: throw DriveException("Google Drive did not return a file id.")

    private suspend fun get(url: String): String = send(url, "GET", null, null)

    /**
     * A GET that keeps the bytes.
     *
     * A .kdbx is not text, and decoding it as UTF-8 to turn it back into bytes
     * would mangle every byte outside ASCII -- which is most of an encrypted
     * file.
     */
    private suspend fun sendBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val accessToken = token()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw DriveException(explain(status, detail), status)
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun send(
        url: String,
        method: String,
        contentType: String?,
        body: ByteArray?,
    ): String = withContext(Dispatchers.IO) {
        val accessToken = token()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            // HttpURLConnection rejects PATCH outright; Google accepts the
            // override header that every one of its clients relies on.
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            setRequestProperty("Authorization", "Bearer $accessToken")
            contentType?.let { setRequestProperty("Content-Type", it) }
            connectTimeout = 30_000
            readTimeout = 30_000
            doInput = true
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            body?.let { connection.outputStream.use { stream -> stream.write(it) } }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw DriveException(explain(status, detail), status)
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Plain sentences, because these surface to someone who is not debugging. */
    private fun explain(status: Int, detail: String): String = when (status) {
        401 -> "Google needs you to sign in again."
        403 ->
            if (detail.contains("insufficient", ignoreCase = true) ||
                detail.contains("scope", ignoreCase = true)
            ) {
                "Keyweb wasn't given permission to use your Google Drive."
            } else {
                "Google turned that request down. Your Drive may be full."
            }
        404 -> "That backup file is no longer in your Drive."
        429, in 500..599 -> "Google Drive is busy. Keyweb will try again."
        else -> "Google Drive returned an error ($status)."
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Drive query strings are single-quoted; a stray quote would change the query. */
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val FILES_V2 = "https://www.googleapis.com/drive/v2/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /**
         * Shared with the web app. These identify *the* Keyweb backup, so they
         * are as much a part of the wire contract as the envelope format.
         */
        const val CONTENT_TYPE = "application/json"
        const val VAULT_FILE_NAME = "keyweb-vault-v1.json"
        const val FOLDER_NAME = "Keyweb"
        val VAULT_MARKER = mapOf("keyweb" to "vault-v1")
        val FOLDER_MARKER = mapOf("keyweb" to "folder")
    }
}
