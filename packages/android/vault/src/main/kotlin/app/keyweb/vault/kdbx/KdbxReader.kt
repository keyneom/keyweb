package app.keyweb.vault.kdbx

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
 */
data class KdbxEntry(
    val uuid: String,
    val keyringName: String,
    val folder: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val note: String,
    val tags: String,
    /** Anything the database held that Keyweb has no named field for. */
    val extra: Map<String, String>,
)

data class KdbxFile(
    val entries: List<KdbxEntry>,
    /** Top-level group names, in the order they appear. */
    val keyringNames: List<String>,
    /** Entries with nothing worth importing, plus anything in the recycle bin. */
    val skipped: Int,
)

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
                            entry.keyringName.takeIf { it.isNotEmpty() }?.let(keyringNames::add)
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
            // Loose entries do need a name, though. Left without one they
            // matched no keyring at commit time and were quietly filed under
            // whichever keyring came out of the map first — a different folder
            // on a different run, and never the right one. The root group's
            // name is what KeePass shows at the top of the tree, so an entry
            // that lived there arrives somewhere the user recognises.
            val rootName = container.children("Name").firstOrNull()
                ?.textContent?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Imported"

            for (child in container.elements()) {
                when (child.tagName) {
                    "Group" -> walk(child, emptyList())
                    // An entry sitting loose at the root belongs to no group;
                    // it is still someone's password and must not vanish.
                    "Entry" -> {
                        val entry = readEntry(child, listOf(rootName), stream)
                        if (entry == null) {
                            skipped += 1
                        } else {
                            entries += entry
                            keyringNames += rootName
                        }
                    }
                }
            }
        }
        return KdbxFile(entries, keyringNames.toList(), skipped)
    }

    private fun readEntry(
        element: Element,
        path: List<String>,
        stream: InnerStream,
    ): KdbxEntry? {
        val uuid = element.children("UUID").firstOrNull()?.textContent?.trim().orEmpty()
        val tags = element.children("Tags").firstOrNull()?.textContent?.trim().orEmpty()
        val fields = LinkedHashMap<String, String>()

        // Every <String> in order, including the protected ones, so the
        // keystream advances exactly as it did when the file was written.
        for (field in element.children("String")) {
            val key = field.children("Key").firstOrNull()?.textContent.orEmpty()
            val valueNode = field.children("Value").firstOrNull() ?: continue
            val raw = valueNode.textContent.orEmpty()
            val protectedAttribute = valueNode.getAttribute("Protected")
            val value = if (protectedAttribute.equals("True", ignoreCase = true)) {
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
        }

        // History entries carry their own protected values and must be walked
        // too, or every value after the first history item decrypts as noise.
        for (history in element.children("History")) {
            for (past in history.children("Entry")) readEntry(past, path, stream)
        }

        val title = fields.remove("Title").orEmpty()
        val username = fields.remove("UserName").orEmpty()
        val password = fields.remove("Password").orEmpty()
        val url = fields.remove("URL").orEmpty()
        val note = fields.remove("Notes").orEmpty()

        // Nothing worth carrying across. Importing these would add blank rows
        // to somebody's list and nothing else.
        if (title.isEmpty() && username.isEmpty() && password.isEmpty() && url.isEmpty() &&
            note.isEmpty() && fields.isEmpty()
        ) {
            return null
        }

        return KdbxEntry(
            uuid = uuid,
            keyringName = path.firstOrNull().orEmpty(),
            folder = path.joinToString(" / "),
            title = title,
            username = username,
            password = password,
            url = url,
            note = note,
            tags = tags.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", "),
            extra = fields,
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
