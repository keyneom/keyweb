package app.keyweb.vault.kdbx

import app.keyweb.vault.Fields
import app.keyweb.vault.HISTORY_LIMIT
import app.keyweb.vault.ItemField
import app.keyweb.vault.Totp
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * One entry as it sits in the KeePass file, before anything Keyweb-specific.
 *
 * `keyringName` is the top-level group, which becomes a keyring; `folder` keeps
 * the full nested path, so an organised database does not arrive as one flat
 * list. Both are preserved because losing them is how an import turns someone's
 * filing system into a pile.
 *
 * `keyringName` is null for an entry sitting at the database root, in no group
 * at all. Null rather than a stand-in name, because there is no answer to give
 * here: where those go is the user's decision, not something this reader can
 * infer. Inventing a name made that decision silently and got it wrong.
 */
data class KdbxEntry(
    val uuid: String,
    val keyringName: String?,
    val folder: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val note: String,
    val tags: String,
    /** Anything the database held that Keyweb has no named field for. */
    val extra: Map<String, String>,
    /**
     * Which of [extra] the database marked protected.
     *
     * Carried because it decides the name the field lands under — a protected
     * string becomes `secret:<name>` and stays masked, exactly as it was in
     * KeePass. Guessing from the name instead would mask an account holder's
     * name and reveal a field called "answer", and would disagree with the web
     * reader about the same file, which syncs as two fields rather than one.
     */
    val protectedKeys: Set<String>,
    /**
     * Earlier versions of this entry, oldest first.
     *
     * KeePass keeps whole prior entries; Keyweb keeps superseded values per
     * field. Rather than translate, each version is replayed as the edit it
     * was, stamped when it happened, and the vault's history falls out of it.
     */
    val versions: List<KdbxVersion>,
    /**
     * Files attached in KeePass, by name.
     *
     * Named rather than carried: Keyweb has nowhere to put them yet, and
     * counting them is what turns silent loss into a decision somebody can
     * make before deleting their original file.
     */
    val attachments: List<String>,
)

/**
 * Every field of an entry back under its KeePass name.
 *
 * The reader splits the five standard names out into their own properties;
 * a history version needs them put back together, because what changed
 * between two versions might be the password or might be a custom field.
 */
fun KdbxEntry.allFields(): Map<String, String> = buildMap {
    if (title.isNotEmpty()) put("Title", title)
    if (username.isNotEmpty()) put("UserName", username)
    if (password.isNotEmpty()) put("Password", password)
    if (url.isNotEmpty()) put("URL", url)
    if (note.isNotEmpty()) put("Notes", note)
    putAll(extra)
}

/** One earlier version of an entry, with the moment it was superseded. */
data class KdbxVersion(
    /** Milliseconds since the epoch, from KeePass's own modification time. */
    val atMs: Long,
    val fields: Map<String, String>,
    val protectedKeys: Set<String>,
)

data class KdbxFile(
    val entries: List<KdbxEntry>,
    /** Top-level group names, in the order they appear. */
    val keyringNames: List<String>,
    /** How many entries sit at the database root, in no group. */
    val ungrouped: Int,
    /**
     * Entries with nothing in them at all, plus anything in the recycle bin.
     *
     * The only thing safe to leave behind. Anything with content is imported
     * even without a title or a password, because "it had no password so we
     * dropped it" is how a membership number or a secure note disappears from
     * a file its owner then deletes.
     */
    val skipped: Int,
    /** Entries carrying files, and the files they carry. Nothing stores these yet. */
    val attachments: List<KdbxAttachment>,
    /** How many earlier versions came across, for the preview to report. */
    val versions: Int,
)

data class KdbxAttachment(val uuid: String, val title: String, val names: List<String>)

/**
 * The name to suggest for a keyring holding the entries that are in no group.
 *
 * The file's own name is the best guess available: it is what the user calls
 * this collection of passwords, and it is already on screen, so the suggestion
 * does not come out of nowhere. The database's internal root group name is a
 * worse guess — it is often a leftover default like "NewDatabase" that the
 * user has never seen.
 */
fun suggestedKeyringName(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    // Only the final extension: "Work passwords.v2.kdbx" keeps the ".v2".
    val withoutExtension = if (base.contains('.')) base.substringBeforeLast('.') else base
    return withoutExtension.trim().ifEmpty { "Imported" }
}

/**
 * Reads a KeePass database.
 *
 * Read-only in the strongest sense available: this file has no writer, no
 * serializer and no way to produce a KDBX byte at all. Someone's KeePass
 * database is often the only copy of decades of accounts, and the safest
 * guarantee is one the code cannot break.
 */
object KdbxReader {

    fun read(bytes: ByteArray, password: String, keyFile: ByteArray? = null): KdbxFile {
        val reader = LittleEndianReader(bytes)
        val header = KdbxHeader.parse(reader)

        val composite = KdbxCrypto.compositeKey(password, keyFile)
        val transformed = transformKey(header, composite)
        val masterKey = KdbxCrypto.sha256(header.masterSeed, transformed)

        val payload = if (header.isVersion4) {
            readVersion4(reader, header, masterKey, transformed)
        } else {
            readVersion3(reader, header, masterKey)
        }

        return parseXml(payload.xml, payload.stream)
    }

    private class Payload(val xml: ByteArray, val stream: InnerStream)

    private fun transformKey(header: KdbxHeader, composite: ByteArray): ByteArray {
        if (!header.isVersion4) {
            val seed = header.transformSeed
                ?: throw KdbxException("This file is missing the values needed to open it.")
            return KdbxCrypto.aesKdf(composite, seed, header.transformRounds ?: 6000L)
        }

        val parameters = header.kdfParameters
            ?: throw KdbxException("This file is missing the values needed to open it.")
        val uuid = parameters.bytes("\$UUID")
            ?: throw KdbxException("This file doesn't say how its key was made.")

        return when {
            uuid.contentEquals(Uuids.AES_KDF) -> KdbxCrypto.aesKdf(
                composite,
                parameters.bytes("S") ?: throw KdbxException("This file is missing its key seed."),
                parameters.long("R") ?: 6000L,
            )

            uuid.contentEquals(Uuids.ARGON2D) || uuid.contentEquals(Uuids.ARGON2ID) -> {
                val memoryBytes = parameters.long("M")
                    ?: throw KdbxException("This file is missing its key settings.")
                KdbxCrypto.argon2(
                    compositeKey = composite,
                    salt = parameters.bytes("S")
                        ?: throw KdbxException("This file is missing its key salt."),
                    parallelism = (parameters.long("P") ?: 1L).toInt(),
                    // KeePass records memory in bytes; Argon2 wants kibibytes.
                    memoryKib = (memoryBytes / 1024L).toInt().coerceAtLeast(8),
                    iterations = (parameters.long("I") ?: 2L).toInt(),
                    version = (parameters.long("V") ?: 0x13L).toInt(),
                    argon2id = uuid.contentEquals(Uuids.ARGON2ID),
                )
            }

            else -> throw KdbxException("This file uses a key method Keyweb doesn't support.")
        }
    }

    private fun readVersion4(
        reader: LittleEndianReader,
        header: KdbxHeader,
        masterKey: ByteArray,
        transformed: ByteArray,
    ): Payload {
        val headerHash = reader.take(32)
        if (!KdbxCrypto.sha256(header.raw).contentEquals(headerHash)) {
            throw KdbxException("This file's header has been altered since it was saved.")
        }

        val hmacKey = KdbxCrypto.sha512(header.masterSeed, transformed, byteArrayOf(1))
        val headerHmac = reader.take(32)
        // Verified before the password is blamed for anything: a wrong HMAC on
        // the header with a *correct* password means tampering, and the two
        // must not be reported as the same thing.
        val expected = KdbxCrypto.headerHmac(hmacKey, header.raw)
        if (!expected.contentEquals(headerHmac)) throw WrongMasterPassword()

        val encrypted = readHmacBlocks(reader, hmacKey)
        val decrypted = decrypt(header, masterKey, encrypted)
        val body = if (header.compressed) gunzip(decrypted) else decrypted

        val bodyReader = LittleEndianReader(body)
        val inner = KdbxInnerHeader.parse(bodyReader)
        return Payload(bodyReader.rest(), KdbxCrypto.innerStream(inner.streamId, inner.streamKey))
    }

    private fun readVersion3(
        reader: LittleEndianReader,
        header: KdbxHeader,
        masterKey: ByteArray,
    ): Payload {
        val decrypted = decrypt(header, masterKey, reader.rest())
        val expectedStart = header.streamStartBytes
            ?: throw KdbxException("This file is missing its start marker.")

        val start = decrypted.copyOfRange(0, minOf(expectedStart.size, decrypted.size))
        // In KDBX 3.1 this is the only password check there is.
        if (!start.contentEquals(expectedStart)) throw WrongMasterPassword()

        val blocks = readHashedBlocks(
            LittleEndianReader(decrypted.copyOfRange(expectedStart.size, decrypted.size)),
        )
        val body = if (header.compressed) gunzip(blocks) else blocks
        val streamKey = header.protectedStreamKey
            ?: throw KdbxException("This file is missing its inner header key.")
        return Payload(body, KdbxCrypto.innerStream(header.innerStreamId ?: 2, streamKey))
    }

    private fun decrypt(header: KdbxHeader, masterKey: ByteArray, data: ByteArray): ByteArray =
        when {
            header.cipherId.contentEquals(Uuids.AES_CBC) ->
                KdbxCrypto.decryptAesCbc(masterKey, header.encryptionIv, data)

            header.cipherId.contentEquals(Uuids.CHACHA20) ->
                KdbxCrypto.decryptChaCha20(masterKey, header.encryptionIv, data)

            else -> throw KdbxException(
                "This file is encrypted with a cipher Keyweb doesn't support.",
            )
        }

    /**
     * Walk the XML.
     *
     * Strictly in document order, because every protected value is a slice of
     * one keystream: reading them out of order leaves everything after the
     * first as noise. That constraint is why this is a manual walk rather than
     * a set of queries.
     */
    private fun parseXml(xml: ByteArray, stream: InnerStream): KdbxFile {
        val document = try {
            safeDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (cause: Exception) {
            throw KdbxException("This file opened, but its contents could not be read.", cause)
        }

        val root = document.documentElement
            ?.children("Root")?.firstOrNull()
            ?: throw KdbxException("This file has no entries in it.")

        val recycleBin = document.documentElement
            ?.children("Meta")?.firstOrNull()
            ?.children("RecycleBinUUID")?.firstOrNull()
            ?.textContent?.trim()
            ?.takeIf { it.isNotEmpty() && it != "AAAAAAAAAAAAAAAAAAAAAA==" }

        val entries = mutableListOf<KdbxEntry>()
        val keyringNames = linkedSetOf<String>()
        var ungrouped = 0
        var skipped = 0

        /**
         * [inBin] is inherited, and that is the whole point of it.
         *
         * Deleting a *group* in KeePass does not delete anything: the group is
         * moved into the recycle bin with its entries still inside it. Testing
         * only whether this group is the bin therefore catches loose deleted
         * entries and misses every entry that was deleted as part of a folder
         * — which came back on import wearing "Recycle Bin" as a keyring, a
         * folder the user never made, holding passwords they had thrown away.
         */
        fun walk(group: Element, path: List<String>, inBin: Boolean = false) {
            val name = group.children("Name").firstOrNull()?.textContent?.trim().orEmpty()
            val uuid = group.children("UUID").firstOrNull()?.textContent?.trim()
            val binned = inBin || (recycleBin != null && uuid == recycleBin)
            val here = if (path.isEmpty() && name.isEmpty()) path else path + name

            // Children are visited in document order so the keystream stays in
            // step, which means entries and subgroups cannot be handled in two
            // separate passes.
            for (child in group.elements()) {
                when (child.tagName) {
                    "Entry" -> {
                        val entry = readEntry(child, here, stream)
                        if (binned || entry == null) {
                            skipped += 1
                        } else {
                            entries += entry
                            entry.keyringName?.let(keyringNames::add)
                        }
                    }

                    "Group" -> walk(child, here, binned)
                }
            }
        }

        // <Root> holds a single container group: the database's own root, whose
        // name is the database name rather than a keyring. Treating it as a
        // group would collapse every keyring into one called "Household".
        for (container in root.children("Group")) {
            for (child in container.elements()) {
                when (child.tagName) {
                    "Group" -> walk(child, emptyList())
                    // An entry sitting loose at the root belongs to no group.
                    // It is counted rather than given a name, because where it
                    // should go is a question only the user can answer.
                    "Entry" -> {
                        val entry = readEntry(child, emptyList(), stream)
                        if (entry == null) {
                            skipped += 1
                        } else {
                            entries += entry
                            ungrouped += 1
                        }
                    }
                }
            }
        }
        return KdbxFile(
            entries = entries,
            keyringNames = keyringNames.toList(),
            ungrouped = ungrouped,
            skipped = skipped,
            attachments = entries
                .filter { it.attachments.isNotEmpty() }
                .map {
                    KdbxAttachment(
                        uuid = it.uuid,
                        title = it.title.ifEmpty { "Untitled" },
                        names = it.attachments,
                    )
                },
            versions = entries.sumOf { it.versions.size },
        )
    }

    /**
     * KeePass writes times as base64 seconds since year 1 in KDBX 4, and as an
     * ISO-8601 string in 3.1. Both appear in files in the wild, and an
     * unreadable one is not worth failing an import over — it only costs that
     * version its place in the history.
     */
    /** Seconds from 0001-01-01 to 1970-01-01, which is what .NET counts from. */
    private val SECONDS_YEAR_1_TO_EPOCH = 62_135_596_800L

    private fun parseKdbxTime(value: String): Long {
        if (value.isEmpty()) return 0
        runCatching {
            return java.time.Instant.parse(value).toEpochMilli()
        }
        runCatching {
            val bytes = Base64.getDecoder().decode(value)
            if (bytes.size < 8) return 0
            var seconds = 0L
            for (i in 7 downTo 0) seconds = (seconds shl 8) or (bytes[i].toLong() and 0xff)
            // Seconds since 0001-01-01, which is what .NET's DateTime uses.
            return (seconds - SECONDS_YEAR_1_TO_EPOCH) * 1000
        }
        return 0
    }

    private fun readEntry(
        element: Element,
        path: List<String>,
        stream: InnerStream,
    ): KdbxEntry? {
        val uuid = element.children("UUID").firstOrNull()?.textContent?.trim().orEmpty()
        val tags = element.children("Tags").firstOrNull()?.textContent?.trim().orEmpty()
        val fields = LinkedHashMap<String, String>()
        val protectedKeys = LinkedHashSet<String>()

        // Every <String> in order, including the protected ones, so the
        // keystream advances exactly as it did when the file was written.
        for (field in element.children("String")) {
            val key = field.children("Key").firstOrNull()?.textContent.orEmpty()
            val valueNode = field.children("Value").firstOrNull() ?: continue
            val raw = valueNode.textContent.orEmpty()
            val isProtected = valueNode.getAttribute("Protected").equals("True", ignoreCase = true)
            val value = if (isProtected) {
                if (raw.isEmpty()) {
                    ""
                } else {
                    String(
                        stream.decrypt(Base64.getDecoder().decode(raw)),
                        Charsets.UTF_8,
                    )
                }
            } else {
                raw
            }
            fields[key] = value
            if (isProtected) protectedKeys += key
        }

        // Files attached to this entry. Only the names: the bytes are in the
        // database's binary pool and Keyweb has nowhere to put them yet, so
        // what matters is being able to say which entries have them.
        val attachments = element.children("Binary").mapNotNull { binary ->
            binary.children("Key").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }

        // History entries carry their own protected values and must be walked
        // in order, or every value after the first history item decrypts as
        // noise. They are also worth keeping: an earlier password is what
        // somebody looks for when a change turns out to have been a mistake.
        val versions = mutableListOf<KdbxVersion>()
        for (history in element.children("History")) {
            for (past in history.children("Entry")) {
                val version = readEntry(past, path, stream) ?: continue
                val at = past.children("Times").firstOrNull()
                    ?.children("LastModificationTime")?.firstOrNull()
                    ?.textContent?.trim().orEmpty()
                val atMs = parseKdbxTime(at)
                if (atMs > 0) {
                    versions += KdbxVersion(
                        atMs = atMs,
                        fields = version.allFields(),
                        protectedKeys = version.protectedKeys,
                    )
                }
            }
        }

        val title = fields.remove("Title").orEmpty()
        val username = fields.remove("UserName").orEmpty()
        val password = fields.remove("Password").orEmpty()
        val url = fields.remove("URL").orEmpty()
        val note = fields.remove("Notes").orEmpty()

        // The only entry safe to leave behind is one with nothing in it at
        // all. Anything with a field, a file or a past version comes across,
        // because "no title and no password so we dropped it" is how a secure
        // note full of account numbers disappears.
        if (title.isEmpty() && username.isEmpty() && password.isEmpty() && url.isEmpty() &&
            note.isEmpty() && fields.isEmpty() && attachments.isEmpty() && versions.isEmpty()
        ) {
            return null
        }

        return KdbxEntry(
            uuid = uuid,
            keyringName = path.firstOrNull(),
            folder = path.joinToString(" / "),
            title = title,
            username = username,
            password = password,
            url = url,
            note = note,
            tags = tags.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", "),
            extra = fields,
            protectedKeys = protectedKeys,
            versions = versions.sortedBy { it.atMs }.takeLast(HISTORY_LIMIT),
            attachments = attachments,
        )
    }
}

/**
 * An XML parser that will not fetch anything.
 *
 * The file arrives from cloud storage and is not to be trusted: an external
 * entity reference in it could otherwise read local files or make network
 * requests while "importing passwords".
 *
 * Each hardening feature is applied defensively because the parsers differ.
 * Android's does not implement `disallow-doctype-decl` at all and throws
 * `ParserConfigurationException` when asked for it — which is how this was
 * found, since the JVM used by the tests supports it and the phone does not.
 * Refusing every entity at resolution time is the part that works everywhere,
 * so it is the guarantee rather than the belt-and-braces.
 */
internal fun safeDocumentBuilder(): DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance()
    for (feature in listOf(
        "http://apache.org/xml/features/disallow-doctype-decl",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    )) {
        val enable = feature.endsWith("disallow-doctype-decl")
        runCatching { factory.setFeature(feature, enable) }
    }
    runCatching { factory.isXIncludeAware = false }
    factory.isExpandEntityReferences = false

    return factory.newDocumentBuilder().apply {
        // Nothing external is ever fetched, whatever the parser allowed above.
        setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
    }
}

private fun Element.elements(): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
    }
    return out
}

private fun Element.children(tag: String): List<Element> = elements().filter { it.tagName == tag }

/**
 * What an imported field is called inside Keyweb.
 *
 * Deliberately here rather than in the Android app, because the web reader has
 * to agree with it exactly: the same file imported on a phone and in a browser
 * lands in the *same* vault, and a field named `secret:Answer` on one side and
 * `Answer` on the other is one field that has become two. A frozen fixture
 * checks the two implementations still agree.
 *
 * A protected string becomes `secret:<name>`, which is what makes it masked on
 * screen — `isSecretField` keys off that prefix, so a field its owner marked
 * protected in KeePass stays protected here without anybody maintaining a
 * list. Guessing from the name instead would mask an account holder's name and
 * reveal a field called "answer".
 *
 * A custom field whose name collides with one of Keyweb's own keys is prefixed
 * rather than allowed to overwrite it: a KeePass field called "folder" must not
 * be able to move the entry.
 */
fun importedFieldName(name: String, isProtected: Boolean): ItemField {
    val collides = Fields.KNOWN.contains(name.lowercase())
    val safe = if (collides) "custom:$name" else name
    return if (isProtected) "secret:$safe" else safe
}

/** KeePass has no field for a one-time-code seed, so every tool invented one. */
private val OTP_NAMES = setOf("otp", "totp", "totp seed", "totp-seed", "otpauth")

/** Settings that only make sense beside a seed already normalised into a URI. */
private val OTP_NOISE = setOf("totp settings", "totp-settings")

/**
 * Turn one KeePass entry's fields into Keyweb's, keeping all of them.
 *
 * The five KeePass names Keyweb has its own names for are mapped; everything
 * else is somebody's own field — a security question, a backup PIN, an account
 * number — and comes through under the name they gave it.
 */
fun kdbxFieldsToItemFields(
    fields: Map<String, String>,
    protectedKeys: Set<String>,
): Map<ItemField, String> = buildMap {
    for ((name, value) in fields) {
        if (value.isEmpty()) continue
        val standard = KDBX_STANDARD[name]
        if (standard != null) {
            put(standard, value)
            continue
        }
        val lower = name.lowercase()
        // Settings for a seed about to become an otpauth URI, which carries its
        // own digits and period. Keeping them leaves two sources of truth.
        if (OTP_NOISE.contains(lower)) continue
        if (OTP_NAMES.contains(lower)) {
            val normalised = runCatching { Totp.format(Totp.parse(value)) }.getOrNull()
            if (normalised != null) {
                put(Fields.OTP, normalised)
            } else {
                // Unreadable as a seed, but still the user's data.
                put(importedFieldName(name, protectedKeys.contains(name)), value)
            }
            continue
        }
        put(importedFieldName(name, protectedKeys.contains(name)), value)
    }
}

private val KDBX_STANDARD: Map<String, ItemField> = mapOf(
    "Title" to Fields.TITLE,
    "UserName" to Fields.USERNAME,
    "Password" to Fields.PASSWORD,
    "URL" to Fields.URL,
    "Notes" to Fields.NOTE,
)
