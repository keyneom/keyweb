package app.keyweb.data

import android.app.Activity
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
 * Three details decide whether this works, each of which fails silently:
 *
 *  1. **A full browser tab, not a Custom Tab.** Google Identity Services runs
 *     its token flow in a popup, and inside a Custom Tab the popup replaces the
 *     page — the token never reaches the opener and the Picker never loads.
 *  2. **The browser package must be forced.** Keyweb owns the App Link for its
 *     own web origin, so an unaddressed VIEW intent is routed straight back
 *     into this app rather than to a browser.
 *  3. **The default browser must be resolved with a neutral URL.** Resolving
 *     the grant URL itself returns Keyweb, for the same reason.
 */
object GrantBrowser {

    /** The web app's import screen, which opens the Picker on arrival. */
    const val GRANT_URL = "https://keyneom.github.io/keyweb/?grant=import"

    /**
     * Returns false when no browser could be resolved, which is rare but real
     * on stripped-down devices. The caller offers the link to copy instead of
     * leaving the user at a dead end.
     */
    fun open(activity: Activity, url: String = GRANT_URL): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = activity.packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != activity.packageName && it != "android" }
            ?: return false

        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(browser),
            )
            true
        } catch (cause: Exception) {
            false
        }
    }
}
