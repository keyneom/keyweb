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
 *     phone with three of them.
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
     * Returns false only when nothing on the device would take an https link.
     * The caller has already been handed the URL on the clipboard by then, so
     * the fallback is reading it out rather than a dead end.
     */
    fun open(activity: Activity, url: String = GRANT_URL): Boolean {
        val target = Uri.parse(url)

        // Preferred: name the browser explicitly, so the intent cannot be
        // routed back into Keyweb by an App Link we may claim later.
        resolveBrowser(activity)?.let { browser ->
            if (start(activity, viewIntent(target).setPackage(browser))) return true
        }

        // The device answered no browser, or that browser refused to start.
        // An unaddressed intent lets the system resolve or offer a chooser,
        // which still reaches a browser on most phones.
        return start(activity, viewIntent(target))
    }

    /**
     * Puts the grant URL on the clipboard so it can be opened by hand.
     *
     * Called before the attempt rather than after it: if `startActivity` throws
     * the user is already looking at a failure, and asking them to retype a
     * URL from an error message is not a recovery worth offering.
     */
    fun copyLink(context: Context, url: String = GRANT_URL) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Keyweb grant link", url))
    }

    private fun viewIntent(target: Uri): Intent =
        Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * The default browser's package, resolved with a **neutral** URL.
     *
     * Resolving the grant URL itself would return Keyweb if it ever owns that
     * origin's App Link, which is the one answer that cannot work here.
     */
    private fun resolveBrowser(activity: Activity): String? {
        val probe = viewIntent(Uri.parse("https://www.google.com"))
        val default = activity.packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it.isUsableBrowser(activity) }
        if (default != null) return default

        // No default set — the resolver activity answers instead. Take any
        // browser that can handle the probe.
        return activity.packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.packageName }
            .firstOrNull { it.isUsableBrowser(activity) }
    }

    /** Neither Keyweb itself nor the system's "which app?" resolver. */
    private fun String.isUsableBrowser(activity: Activity): Boolean =
        this != activity.packageName && this != "android"

    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (cause: Exception) {
        false
    }
}
