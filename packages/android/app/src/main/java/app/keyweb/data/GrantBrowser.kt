package app.keyweb.data

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Opens the web app's Picker grant page in a real browser tab.
 *
 * Android has no native UI for `drive.file`. A file is invisible to the app
 * until the user hands it over through the Google Picker, and the Picker only
 * runs in a browser — so granting access on a phone means leaving the app.
 *
 * That detour buys something worth having: a `drive.file` grant is scoped to
 * the **Cloud project and the Google account**, not to a device or an OAuth
 * client. A file handed over once is then visible to the web app, to this
 * phone, and to any other device signed into the same account. The alternative
 * — Android's own document picker — grants a URI permission to one app install
 * on one device and authorizes nothing at the Drive API at all.
 *
 * Four details decide whether this works, each of which fails silently:
 *
 *  1. **`<queries>` must be declared in the manifest.** Since Android 11 the
 *     platform hides other apps from us, and every resolve below returns
 *     nothing at all unless we declare the https VIEW intent we dispatch. This
 *     is the failure that looks exactly like having no browser installed, on a
 *     phone with three of them, and it is why the manifest carries a `<queries>`
 *     element that must not be removed.
 *  2. **A full browser tab, not a Custom Tab.** Google Identity Services runs
 *     its token flow in a popup, and inside a Custom Tab the popup replaces the
 *     page — the token never reaches the opener and the Picker never loads.
 *  3. **The browser package should be forced.** If Keyweb ever claims the App
 *     Link for its own web origin, an unaddressed VIEW intent would be routed
 *     straight back into this app rather than out to a browser.
 *  4. **There must be somewhere to go when resolution fails.** Falling back to
 *     an unaddressed intent, and then to the clipboard, keeps a stripped-down
 *     device from becoming a dead end.
 */
object GrantBrowser {

    /** The web app's grant page, which opens the Picker on arrival. */
    const val GRANT_URL = "https://keyneom.github.io/keyweb/?grant=import"

    /**
     * The same page, asked for a *shared keyring's* file instead.
     *
     * Only the grant happens over there. The phone holds the vault and does the
     * joining itself, which is why this hands over a file list rather than the
     * join link: opening the join link in a browser would join the keyring into
     * whatever vault that browser has, which is the wrong vault and possibly no
     * vault at all.
     */
    fun shareGrantUrl(encodedFiles: String): String =
        "https://keyneom.github.io/keyweb/?grant=share&sk-files=" +
            // Not `Uri.encode`: that is an unimplemented stub in a JVM unit
            // test, and building this URL is worth a test rather than a phone.
            java.net.URLEncoder.encode(encodedFiles, "UTF-8")

    /**
     * A neutral URL, used to find a browser.
     *
     * Deliberately not the grant URL: resolving that would return Keyweb if it
     * ever owns the origin's App Link, which is the one answer that cannot
     * work here.
     */
    private const val PROBE_URL = "https://www.google.com"

    /**
     * The slice of the platform this needs.
     *
     * Pulled out so the choosing can be tested on the JVM. The bug that made
     * this necessary — no `<queries>`, so nothing resolves — is invisible in a
     * build and only appears on a device, so the least this code can do is be
     * provably correct about what it does when nothing resolves.
     */
    interface Environment {
        /** This app, which must never be the target. */
        val ownPackage: String

        /** The default handler for [url], or null when the user has set none. */
        fun defaultHandler(url: String): String?

        /** Every package that can handle [url]. */
        fun handlers(url: String): List<String>

        /** Returns false when the launch failed. [target] null means unaddressed. */
        fun launch(url: String, target: String?): Boolean
    }

    /**
     * Returns false only when nothing on the device would take an https link.
     * The caller has already put the URL on the clipboard by then, so the
     * fallback is pasting it rather than a dead end.
     */
    fun open(activity: Activity, url: String = GRANT_URL): Boolean =
        choose(AndroidEnvironment(activity), url)

    /** Puts the grant URL on the clipboard so it can be opened by hand. */
    fun copyLink(context: Context, url: String = GRANT_URL) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Keyweb grant link", url))
    }

    /**
     * Name a browser if one can be found, and otherwise let the system try.
     *
     * The unaddressed attempt is last rather than absent: a device that
     * answers no browser to a query may still route a plain VIEW intent, and
     * the alternative is telling someone to go and do it themselves.
     */
    internal fun choose(environment: Environment, url: String = GRANT_URL): Boolean {
        val browser = environment.defaultHandler(PROBE_URL)?.takeIf { environment.usable(it) }
            ?: environment.handlers(PROBE_URL).firstOrNull { environment.usable(it) }

        if (browser != null && environment.launch(url, browser)) return true
        return environment.launch(url, null)
    }

    /** Neither Keyweb itself nor the system's "which app?" resolver. */
    private fun Environment.usable(candidate: String): Boolean =
        candidate != ownPackage && candidate != "android"

    private class AndroidEnvironment(private val activity: Activity) : Environment {
        override val ownPackage: String get() = activity.packageName

        override fun defaultHandler(url: String): String? =
            activity.packageManager
                .resolveActivity(viewIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName

        override fun handlers(url: String): List<String> =
            activity.packageManager
                .queryIntentActivities(viewIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }

        override fun launch(url: String, target: String?): Boolean = try {
            activity.startActivity(viewIntent(url).apply { target?.let(::setPackage) })
            true
        } catch (cause: Exception) {
            false
        }

        /**
         * A plain browser intent, never a Custom Tab.
         *
         * The dependency for Custom Tabs was carried for a year with a comment
         * saying it was for this, and nothing ever called it — which was
         * lucky, because a Custom Tab is the wrong answer: Google Identity
         * Services' popup token flow breaks inside one, the popup replaces the
         * page, and the token never reaches the opener. sync-kit documents
         * this now, and ships `launchGrantInBrowser` doing the same thing this
         * does.
         */
        private fun viewIntent(url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}
