package app.keyweb

/**
 * The public pages a person, and Google's consent screen, are sent to.
 *
 * These live on the same host the app claims as an App Link, so an ordinary
 * VIEW intent would open Keyweb and then complain that the address was not a
 * share. Callers open them through [app.keyweb.data.GrantBrowser], which names
 * a browser rather than this package.
 */
object LegalPages {
    const val PRIVACY = "https://keyneom.github.io/keyweb/privacy.html"
    const val TERMS = "https://keyneom.github.io/keyweb/terms.html"

    /** True for the policy and the terms, which must not be read as a share. */
    fun isPage(url: String): Boolean {
        val bare = url.substringBefore('?').substringBefore('#').removeSuffix("/")
        return bare == PRIVACY || bare == TERMS
    }
}
