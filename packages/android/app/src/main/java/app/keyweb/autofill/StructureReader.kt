package app.keyweb.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View

/** What a fill request turned out to be about. */
data class ReadStructure(
    val fields: List<FormField>,
    /** The web page's domain, when the request came from a browser. */
    val webDomain: String?,
)

/**
 * Turning the platform's view tree into the shape the finder understands.
 *
 * Kept apart from [FieldFinder] on purpose: this half cannot be tested off a
 * device, so it is deliberately dull — it walks, it copies, it decides
 * nothing. Every judgement lives in the half that has tests.
 */
object StructureReader {

    fun read(structure: AssistStructure): ReadStructure {
        val fields = mutableListOf<FormField>()
        var domain: String? = null

        for (index in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(index).rootViewNode, inherited = null) { node, here ->
                if (domain == null && !here.isNullOrBlank()) domain = here
                toField(node, here)?.let(fields::add)
            }
        }
        return ReadStructure(fields, domain)
    }

    private fun walk(
        node: AssistStructure.ViewNode?,
        inherited: String?,
        visit: (AssistStructure.ViewNode, String?) -> Unit,
    ) {
        if (node == null) return
        val here = node.webDomain?.takeIf { it.isNotBlank() } ?: inherited
        visit(node, here)
        for (index in 0 until node.childCount) walk(node.getChildAt(index), here, visit)
    }

    private fun toField(node: AssistStructure.ViewNode, webDomain: String?): FormField? {
        // Only nodes the platform will actually let us fill.
        val id = node.autofillId ?: return null
        if (node.autofillType != View.AUTOFILL_TYPE_TEXT) return null

        val html = node.htmlInfo
            ?.attributes
            ?.associate { it.first.lowercase() to it.second.orEmpty().lowercase() }
            .orEmpty()

        return FormField(
            id = id,
            hints = node.autofillHints?.toList().orEmpty(),
            idEntry = node.idEntry,
            html = html,
            isPassword = isObscured(node.inputType),
            // An invisible field is a trap as often as an oversight; either
            // way filling one puts a password somewhere nobody can see.
            fillable = node.visibility == View.VISIBLE,
            webDomain = webDomain,
        )
    }

    private fun isObscured(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isText = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        return isText && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}
