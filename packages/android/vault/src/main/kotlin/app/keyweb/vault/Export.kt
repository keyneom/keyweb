package app.keyweb.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Getting everything back out.
 *
 * ## Why this exists at all
 *
 * The import was built on a promise: bring your KeePass file in and you can
 * delete the original. That promise is only honest if the door swings both
 * ways. Without an export, "your data is yours" means "your data is yours as
 * long as you keep using Keyweb" — and the backup in Drive is a sealed
 * envelope that only Keyweb can open, so it is a safety net, not a way out.
 *
 * ## Two formats, because one cannot be both things
 *
 * CSV is what other password managers can read. It carries the columns they
 * all agree on and nothing else — no attachments, no per-entry history, and
 * custom fields only as text appended to the note. That loss is real, so
 * [csvOmissions] counts it and the screen says so before the file is written
 * rather than after it is relied on.
 *
 * JSON is lossless: every field under its own name, every file with its bytes,
 * every superseded value with the moment it was replaced. Nothing else reads
 * it today, which is exactly why it has to be a plain documented shape rather
 * than an internal dump — somebody should be able to write twenty lines of
 * script against it in ten years.
 *
 * ## Both are unencrypted, and that is the point
 *
 * An encrypted export that only Keyweb can open is the thing being escaped
 * from. So these are plaintext, and every screen that offers them says so.
 *
 * Byte-identical to the web's `export.ts`, which is checked by a fixture.
 */

@Serializable
data class ExportedFile(
    val name: String,
    val type: String,
    val bytes: Int,
    /** Base64 of the file's contents, exactly as the vault holds it. */
    val data: String,
)

@Serializable
data class ExportedField(val name: String, val value: String, val secret: Boolean)

@Serializable
data class ExportedHistory(val field: String, val value: String, val at: String)

@Serializable
data class ExportedItem(
    val id: String,
    val keyring: String,
    val keyringName: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val note: String,
    /** The `otpauth://` URI, empty when there is no second factor. */
    val otp: String,
    val folder: String,
    val tags: String,
    /**
     * Everything else, under the name its owner gave it.
     *
     * `secret` records whether Keyweb was masking it, which is information
     * about the field rather than about the value — dropping it would mean a
     * re-import could not tell a security answer from an account number.
     */
    val fields: List<ExportedField>,
    val files: List<ExportedFile>,
    val history: List<ExportedHistory>,
)

@Serializable
data class ExportedKeyring(val id: String, val name: String)

@Serializable
data class VaultExport(
    val format: String = "keyweb-export",
    val version: Int = 1,
    val exportedAt: String,
    /** In the file itself, because a file outlives the screen that made it. */
    val warning: String,
    val keyrings: List<ExportedKeyring>,
    val items: List<ExportedItem>,
)

/** What a CSV cannot carry, counted so it can be said before the file is made. */
data class CsvOmissions(val files: Int, val history: Int, val customFields: Int)

private const val WARNING =
    "This file is NOT encrypted. Anyone who opens it can read every password in it. " +
        "Keep it somewhere safe, or delete it once you have finished with it."

private val PRESENTED = setOf(
    "title", "username", "password", "url", "note", "otp", "folder", "tags", "kind",
)

fun exportVault(state: VaultState, nowIso: String): VaultExport {
    val keyrings = state.keyrings.values
        .filter { !it.deleted.value }
        .map { ExportedKeyring(it.id, it.name.value) }

    val items = liveItems(state)
        .filter { !it.isBlob() }
        .map { exportItem(it, state) }
        .sortedWith(compareBy({ it.title }, { it.id }))

    return VaultExport(
        exportedAt = nowIso,
        warning = WARNING,
        keyrings = keyrings,
        items = items,
    )
}

private fun exportItem(item: ItemRecord, state: VaultState): ExportedItem {
    fun of(name: String) = item.fields[name]?.value ?: ""

    val fields = item.fields.keys
        // The pointer at a file is plumbing; the file itself is in `files`.
        .filter { it !in PRESENTED && !it.startsWith("file:") }
        .filter { of(it).isNotEmpty() }
        .sortedBy { fieldLabel(it) }
        .map { ExportedField(fieldLabel(it), of(it), Fields.isSecret(it)) }

    val files = item.attachments().mapNotNull { attachment ->
        val blob = state.items[attachment.blobId] ?: return@mapNotNull null
        val data = blob.fields["secret:data"]?.value ?: return@mapNotNull null
        if (data.isEmpty()) return@mapNotNull null
        ExportedFile(
            name = attachment.name,
            type = blob.fields["type"]?.value ?: "application/octet-stream",
            bytes = blob.fields["size"]?.value?.toIntOrNull() ?: 0,
            data = data,
        )
    }

    return ExportedItem(
        id = item.id,
        keyring = item.keyring.value,
        // Named rather than blank when the keyring is gone, because a CSV row
        // with an empty group column reads as a mistake in the export. The
        // password is real and it is in the vault; what it lacks is a keyring.
        keyringName = keyringLabel(state, item.keyring.value),
        title = of("title"),
        username = of("username"),
        password = of("password"),
        url = of("url"),
        note = of("note"),
        otp = of("otp"),
        folder = of("folder"),
        tags = of("tags"),
        fields = fields,
        files = files,
        history = item.history
            .filter { it.value.isNotEmpty() }
            .map {
                ExportedHistory(
                    field = fieldLabel(it.field),
                    value = it.value,
                    at = java.time.Instant.ofEpochMilli(decodeHlc(it.ts).wall).toString(),
                )
            },
    )
}

fun csvOmissions(exported: VaultExport): CsvOmissions = CsvOmissions(
    files = exported.items.sumOf { it.files.size },
    history = exported.items.sumOf { it.history.size },
    customFields = exported.items.sumOf { it.fields.size },
)

/**
 * The columns every other password manager agrees on.
 *
 * KeePass's own export order, because that is what the importers in KeePassXC,
 * Bitwarden, 1Password and Chrome were all written against. `TOTP` is appended
 * as a seventh column: importers that do not know it ignore a trailing column,
 * and the ones that do know it get the second factor across.
 *
 * Custom fields go into the note rather than into columns of their own — extra
 * columns are what makes an importer reject a file outright, and a security
 * answer buried in a note is recoverable where a rejected file is not. The
 * JSON export is the one that keeps them properly.
 */
fun exportCsv(exported: VaultExport): String {
    val header = listOf("Group", "Title", "Username", "Password", "URL", "Notes", "TOTP")
    val rows = exported.items.map { item ->
        val extras = item.fields.map { "${it.name}: ${it.value}" }
        val dropped = item.files.map { "[file not included in CSV: ${it.name}]" }
        val note = (listOf(item.note) + extras + dropped).filter { it.isNotEmpty() }
            .joinToString("\n")
        listOf(
            item.folder.ifEmpty { item.keyringName },
            item.title,
            item.username,
            item.password,
            item.url,
            note,
            item.otp,
        )
    }
    // CRLF and a trailing newline: RFC 4180, and Excel mangles anything else.
    return (listOf(header) + rows).joinToString("\r\n") { row ->
        row.joinToString(",") { csvCell(it) }
    } + "\r\n"
}

/**
 * One CSV cell.
 *
 * Always quoted. Conditional quoting is where CSV writers go wrong — a value
 * that gains a comma later stops being quoted correctly by a rule written for
 * the values present on the day — and no importer minds.
 *
 * A leading `=`, `+`, `-` or `@` is prefixed with a single quote. A password
 * beginning with `=` is a formula to Excel and Sheets, which will evaluate it:
 * the value on screen stops being the value in the vault, and in the worst
 * case the spreadsheet fetches a URL built out of somebody's password.
 */
private fun csvCell(value: String): String {
    val guarded = if (value.isNotEmpty() && value.first() in "=+-@\t\r") "'$value" else value
    return "\"" + guarded.replace("\"", "\"\"") + "\""
}

/** The JSON text written to the file, formatted so a person can read it. */
fun exportJson(exported: VaultExport): String =
    Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(VaultExport.serializer(), exported)
