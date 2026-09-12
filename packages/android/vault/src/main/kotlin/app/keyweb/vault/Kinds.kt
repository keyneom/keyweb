package app.keyweb.vault

/**
 * What an item is, and what a screen should ask for.
 *
 * The vault stores flat named fields and merges them one register at a time. A
 * kind adds nothing to that model — it is presentation: which fields to offer,
 * in what order, under what words, and which ones stay hidden until asked for.
 *
 * Keeping it presentation rather than schema is what makes it safe to add kinds
 * later. An older client meeting an unknown kind falls back to showing whatever
 * fields the item actually has: plainer, never wrong, and nothing dropped.
 *
 * The keys are part of the cross-platform contract and must match `kinds.ts`
 * exactly. A seed phrase stored under `seedPhrase` on the phone and
 * `seed_phrase` in the browser would be two different fields, and half of
 * someone's wallet backup would appear to vanish depending where they looked.
 * `KindsParityTest` compares the two files rather than trusting this comment.
 */

enum class FieldShape {
    TEXT,
    SECRET,

    /** Multi-line and never masked: a note is read, not copied into a box. */
    NOTE,

    /** Numbered word by word, because it is checked against paper. */
    PHRASE,
    URL,
    MONTH,

    /** A live six-digit code rather than the stored secret. */
    OTP,
}

data class FieldSpec(
    val key: ItemField,
    val label: String,
    val shape: FieldShape,
    /** Shown under the input. Plain language, no jargon. */
    val hint: String? = null,
)

data class ItemKind(
    val id: String,
    /** Singular, as it appears on a button: "Add a **credit card**". */
    val name: String,
    /** One line explaining what belongs here, for someone choosing. */
    val summary: String,
    val fields: List<FieldSpec>,
)

private val TITLE = FieldSpec("title", "Name", FieldShape.TEXT)
private val NOTE = FieldSpec(
    "note",
    "Notes",
    FieldShape.NOTE,
    "Anything else you want to remember about this.",
)

object Kinds {

    const val DEFAULT = "login"

    /**
     * The prefix marking a field someone named themselves as sensitive.
     *
     * Custom fields are free-form, so there is no catalogue to consult for
     * whether one should be masked. The answer travels in the key.
     */
    const val SECRET_PREFIX = "secret:"

    val all: List<ItemKind> = listOf(
        ItemKind(
            id = "login",
            name = "password",
            summary = "A website or app you sign in to.",
            fields = listOf(
                TITLE.copy(hint = "Whose password is this? For example, your bank's name."),
                FieldSpec("username", "Username or email", FieldShape.TEXT),
                FieldSpec("password", "Password", FieldShape.SECRET),
                FieldSpec(
                    "otp",
                    "Six-digit code",
                    FieldShape.OTP,
                    "If this site sends you a changing code, Keyweb can show it here.",
                ),
                FieldSpec("url", "Website", FieldShape.URL),
                NOTE,
            ),
        ),
        ItemKind(
            id = "card",
            name = "card",
            summary = "A credit or debit card.",
            fields = listOf(
                TITLE.copy(hint = "For example, Visa ending 4429."),
                FieldSpec("cardholder", "Name on the card", FieldShape.TEXT),
                FieldSpec("cardNumber", "Card number", FieldShape.SECRET),
                FieldSpec("expiry", "Expires", FieldShape.MONTH, "The MM/YY on the front."),
                FieldSpec("cvv", "Security code", FieldShape.SECRET, "The 3 digits on the back."),
                FieldSpec("pin", "PIN", FieldShape.SECRET),
                NOTE,
            ),
        ),
        ItemKind(
            id = "bank",
            name = "bank account",
            summary = "Account and routing numbers.",
            fields = listOf(
                TITLE.copy(hint = "For example, Joint checking."),
                FieldSpec("bankName", "Bank", FieldShape.TEXT),
                FieldSpec("accountNumber", "Account number", FieldShape.SECRET),
                FieldSpec("routingNumber", "Routing number", FieldShape.TEXT),
                FieldSpec("iban", "IBAN", FieldShape.TEXT),
                FieldSpec("swift", "SWIFT or BIC", FieldShape.TEXT),
                FieldSpec("pin", "PIN", FieldShape.SECRET),
                NOTE,
            ),
        ),
        ItemKind(
            id = "wallet",
            name = "crypto wallet",
            summary = "A recovery phrase or private key.",
            fields = listOf(
                TITLE.copy(hint = "For example, Ledger, or MetaMask."),
                FieldSpec(
                    "seedPhrase",
                    "Recovery phrase",
                    FieldShape.PHRASE,
                    "The 12 or 24 words. Anyone with these words can move the money.",
                ),
                FieldSpec(
                    "walletPassphrase",
                    "Extra passphrase",
                    FieldShape.SECRET,
                    "Only if you set one. It is not part of the words.",
                ),
                FieldSpec("privateKey", "Private key", FieldShape.SECRET),
                FieldSpec("publicAddress", "Public address", FieldShape.TEXT),
                FieldSpec("network", "Network", FieldShape.TEXT, "For example, Bitcoin or Ethereum."),
                FieldSpec(
                    "derivationPath",
                    "Derivation path",
                    FieldShape.TEXT,
                    "Only if your wallet showed you one.",
                ),
                NOTE,
            ),
        ),
        ItemKind(
            id = "identity",
            name = "ID document",
            summary = "A passport, licence, or national ID.",
            fields = listOf(
                TITLE.copy(hint = "For example, Passport."),
                FieldSpec("fullName", "Full name", FieldShape.TEXT),
                FieldSpec("documentNumber", "Document number", FieldShape.SECRET),
                FieldSpec("issuedBy", "Issued by", FieldShape.TEXT),
                FieldSpec("expiry", "Expires", FieldShape.MONTH),
                NOTE,
            ),
        ),
        ItemKind(
            id = "server",
            name = "server or API key",
            summary = "A machine login, key, or token.",
            fields = listOf(
                TITLE,
                FieldSpec("host", "Host or address", FieldShape.TEXT),
                FieldSpec("username", "Username", FieldShape.TEXT),
                FieldSpec("password", "Password", FieldShape.SECRET),
                FieldSpec("privateKey", "Private key", FieldShape.NOTE, "Paste the whole key."),
                FieldSpec("apiToken", "API key or token", FieldShape.SECRET),
                NOTE,
            ),
        ),
        ItemKind(
            id = "licence",
            name = "software licence",
            summary = "A product key.",
            fields = listOf(
                TITLE,
                FieldSpec("product", "Product", FieldShape.TEXT),
                FieldSpec("licenceKey", "Licence key", FieldShape.SECRET),
                FieldSpec("licensedTo", "Licensed to", FieldShape.TEXT),
                NOTE,
            ),
        ),
        ItemKind(
            id = "note",
            name = "secure note",
            summary = "Anything else you want kept private.",
            fields = listOf(TITLE, FieldSpec("note", "Note", FieldShape.NOTE)),
        ),
    )

    /**
     * An unrecognised kind resolves to the login template rather than failing,
     * because a client that cannot name the kind can still show every field.
     */
    fun of(id: String?): ItemKind = all.firstOrNull { it.id == id } ?: all.first()

    /** Every key any built-in kind uses, for telling known fields from custom. */
    val knownKeys: Set<String> =
        all.flatMap { kind -> kind.fields.map { it.key } }.toSet() + setOf("kind", "folder", "tags")

    fun customFieldLabel(key: ItemField): String =
        if (key.startsWith(SECRET_PREFIX)) key.removePrefix(SECRET_PREFIX) else key

    /** How many words a BIP-39 recovery phrase is allowed to have. */
    val phraseLengths = listOf(12, 15, 18, 21, 24)

    /**
     * Check a recovery phrase for the mistake that actually destroys money.
     *
     * A phrase with the wrong number of words is not a phrase — it will never
     * restore anything. Catching that as it is typed is the difference between
     * a correction and a permanent loss, because the value is usually entered
     * once, from paper, and never verified again.
     *
     * This checks the count, not the words. Validating against the BIP-39
     * wordlist would also catch a misspelling and is worth doing; it is not done
     * yet, so the gap is stated rather than implied.
     */
    fun checkPhrase(value: String): String? {
        val words = value.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (words.size !in phraseLengths) {
            return "That's ${words.size} word${if (words.size == 1) "" else "s"}. A recovery " +
                "phrase is usually 12 or 24 — check none are missing before you rely on this."
        }
        return null
    }

    fun phraseWords(value: String): List<String> =
        value.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
