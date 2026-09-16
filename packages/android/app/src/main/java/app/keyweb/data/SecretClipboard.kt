package app.keyweb.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copying a secret, and taking it back again.
 *
 * Copy and paste is currently the only way to get a password into a browser,
 * so the clipboard holds real secrets rather than incidental text. Left alone
 * it holds them until something else happens to replace it — which on a phone
 * can be days, and every app that reads the clipboard sees it meanwhile.
 *
 * Two limits worth stating rather than implying:
 *
 *  - The timer lives in this process. If Keyweb is killed before it fires the
 *    password stays on the clipboard. Making that impossible would mean a
 *    foreground service, which is a large price for a narrow window.
 *  - Anything that already read the clipboard has already read it. Clearing
 *    shortens the exposure; it does not undo it.
 */
object SecretClipboard {

    /** Long enough to switch apps and paste, short enough not to linger. */
    const val CLEAR_AFTER_MS = 60_000L

    /**
     * Put [value] on the clipboard, marked sensitive where the platform knows
     * what that means.
     */
    fun copy(context: Context, value: String, label: String) {
        val clip = ClipData.newPlainText(label, value).apply {
            // Keep the value out of clipboard previews and history where the
            // platform supports it: a password on a shared screen is a leak.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
        }
        clipboard(context)?.setPrimaryClip(clip)
    }

    /**
     * Remove [value] from the clipboard, but only if it is still there.
     *
     * The check is the whole point. Someone who copied an address, a message
     * or another app's password in the meantime must not have it wiped by a
     * timer they know nothing about — a clipboard that empties itself at
     * random is its own kind of bug.
     */
    fun clearIfStill(context: Context, value: String) {
        val clipboard = clipboard(context) ?: return
        if (currentText(clipboard) != value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            // No clearPrimaryClip before Android 9. An empty clip is the
            // closest equivalent, and still removes the secret.
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun currentText(clipboard: ClipboardManager): String? =
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()

    private fun clipboard(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}
