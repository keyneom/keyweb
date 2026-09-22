package app.keyweb.autofill

/**
 * One text field of a form, reduced to what deciding its purpose needs.
 *
 * Deliberately not an `AssistStructure.ViewNode`: the interesting logic is
 * which field is the password and which is the username, and tying that to a
 * platform class would make it testable only on a device. The service walks
 * the real tree into these.
 */
data class FormField(
    /** Opaque handle back to the real node. */
    val id: Any,
    /** `autofillHints`, which is the app's own declaration and the best signal. */
    val hints: List<String> = emptyList(),
    /** The view's resource id entry, e.g. "login_password". */
    val idEntry: String? = null,
    /** HTML `type`/`name`/`id` for web forms, lowercased. */
    val html: Map<String, String> = emptyMap(),
    /** True when the platform says the text is obscured. */
    val isPassword: Boolean = false,
    /** False for a field that cannot be filled, e.g. one that is not shown. */
    val fillable: Boolean = true,
    /**
     * The web domain of this field, inherited from the web view it sits in.
     *
     * Null for a native field. Never taken from a sibling view: a hidden
     * WebView for a bank must not make the app's own password box look like
     * that bank.
     */
    val webDomain: String? = null,
)

/** What a form turned out to be. */
data class FoundFields(
    val username: FormField? = null,
    val password: FormField? = null,
) {
    /** Nothing worth offering a password to. */
    val isEmpty: Boolean get() = password == null && username == null
}

/**
 * Working out which field is which.
 *
 * Signals in order of trust: what the app declares through `autofillHints`,
 * what an HTML form declares through `type`, and finally the names people give
 * their fields. Guessing from names last is deliberate — it is the signal that
 * produces the embarrassing mistakes, like treating a "search" box as a
 * username because it contains the letters "user".
 */
object FieldFinder {

    fun find(fields: List<FormField>): FoundFields {
        val usable = fields.filter { it.fillable }
        val password = usable.firstOrNull { isPassword(it) }
        // The username is the fillable text field before the password, which
        // is how these forms are laid out; failing that, anything that says it
        // is one. Taking the field *after* the password would find the
        // "confirm password" box on a sign-up form.
        val username = password
            ?.let { pw -> usable.takeWhile { it !== pw }.lastOrNull { isUsername(it) } }
            ?: usable.firstOrNull { isUsername(it) }

        return FoundFields(username = username, password = password)
    }

    private fun isPassword(field: FormField): Boolean {
        if (field.hints.any { it.lowercase() in PASSWORD_HINTS }) return true
        // A hint that names something else is a denial, not a maybe: a field
        // declaring itself an OTP or a credit card is not the password, even
        // if it is obscured.
        if (field.hints.isNotEmpty()) return false
        if (field.html["type"] == "password") return true
        if (field.html["type"] in NON_TEXT_HTML_TYPES) return false
        if (field.isPassword) return true
        return looksLike(field, PASSWORD_WORDS) && !looksLike(field, NOT_PASSWORD_WORDS)
    }

    private fun isUsername(field: FormField): Boolean {
        if (field.hints.any { it.lowercase() in USERNAME_HINTS }) return true
        if (field.hints.isNotEmpty()) return false
        if (field.html["type"] == "password") return false
        if (field.html["type"] in NON_TEXT_HTML_TYPES) return false
        if (field.isPassword) return false
        if (field.html["type"] == "email") return true
        return looksLike(field, USERNAME_WORDS) && !looksLike(field, NOT_USERNAME_WORDS)
    }

    /** The field's own names, which is all there is to go on by this point. */
    private fun looksLike(field: FormField, words: Set<String>): Boolean {
        val haystack = buildString {
            append(field.idEntry.orEmpty().lowercase())
            append(' ')
            append(field.html["name"].orEmpty())
            append(' ')
            append(field.html["id"].orEmpty())
        }
        return words.any { haystack.contains(it) }
    }

    private val PASSWORD_HINTS = setOf("password", "newPassword".lowercase())
    private val USERNAME_HINTS = setOf("username", "emailaddress", "newusername", "phone")

    private val PASSWORD_WORDS = setOf("password", "passwd", "pwd", "passphrase")
    private val USERNAME_WORDS = setOf("username", "user", "email", "login", "account", "identifier")

    /** Words that mean the field is about a password without being one. */
    private val NOT_PASSWORD_WORDS = setOf("forgot", "reset", "show", "toggle", "hint", "confirm")

    /** Boxes that contain the word "user" and are not a username. */
    private val NOT_USERNAME_WORDS = setOf("search", "query", "filter", "find")

    private val NON_TEXT_HTML_TYPES = setOf(
        "hidden", "submit", "button", "checkbox", "radio", "search", "file", "range", "color",
    )
}
