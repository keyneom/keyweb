package app.keyweb.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    /** The version offered, without its leading "v". */
    val version: String,
    /** The releases page, which is where a person can actually get the file. */
    val downloadUrl: String,
)

/**
 * Noticing that a newer Keyweb has been published.
 *
 * Keyweb is not on Play, so nothing tells anyone a new build exists. Without
 * this, an installed copy stays on whatever version it was given until someone
 * thinks to go and look — which for a fix to a data bug is not good enough.
 *
 * ## What this does and does not do
 *
 * It reads the latest release tag and compares it with this build. It does not
 * download anything, and deliberately does not try to install anything: a
 * password manager that silently fetches and side-loads code would be a far
 * worse idea than one that is a version behind. Tapping through opens the
 * releases page in a browser, and Android's own installer takes it from there
 * with its usual warnings.
 *
 * ## The privacy cost, stated plainly
 *
 * This is a request to github.com on launch, which tells GitHub the device's
 * IP address and that it is running Keyweb. No vault data is involved and no
 * account is used — the call is unauthenticated. A failure is treated as "no
 * update", so a device that cannot reach GitHub, or is offline entirely, is
 * unaffected and says nothing.
 *
 * ## Dismissal
 *
 * Remembered per version, so declining 0.1.2 does not also decline 0.1.3. A
 * dismissal that silenced every future version would quietly turn the feature
 * off for good on the first "not now".
 */
/** Which versions have been waved away. Behind an interface so the decision
 *  above it can be tested without an Android Context. */
interface UpdateDismissals {
    fun isDismissed(version: String): Boolean
    fun dismiss(version: String)
}

private class StoredDismissals(context: Context) : UpdateDismissals {
    private val preferences =
        context.getSharedPreferences("keyweb_updates", Context.MODE_PRIVATE)

    override fun isDismissed(version: String): Boolean =
        preferences.getBoolean(key(version), false)

    override fun dismiss(version: String) {
        preferences.edit().putBoolean(key(version), true).apply()
    }

    private fun key(version: String) = "dismissed:${SemVer.normalize(version)}"
}

class ReleaseUpdateChecker(
    private val dismissals: UpdateDismissals,
    private val fetchLatestTag: suspend () -> String? = ::fetchLatestTagFromGitHub,
) {
    constructor(context: Context) : this(StoredDismissals(context))

    suspend fun checkForUpdate(currentVersion: String): AppUpdateInfo? {
        val latest = runCatching { fetchLatestTag() }.getOrNull()?.let(SemVer::normalize)
        if (latest.isNullOrBlank()) return null
        if (!SemVer.isNewer(latest, currentVersion)) return null
        if (dismissals.isDismissed(latest)) return null
        return AppUpdateInfo(version = latest, downloadUrl = RELEASES_URL)
    }

    fun dismiss(version: String) = dismissals.dismiss(version)

    companion object {
        const val RELEASES_URL = "https://github.com/keyneom/keyweb/releases/latest"
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/keyneom/keyweb/releases/latest"

        /**
         * The newest release's tag, or null for any reason at all.
         *
         * Every failure is the same answer — no update — because there is no
         * version of "we could not reach GitHub" worth putting in front of
         * someone who opened a password manager.
         */
        private suspend fun fetchLatestTagFromGitHub(): String? = withContext(Dispatchers.IO) {
            val connection = (URL(RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Keyweb-Android")
            }
            try {
                if (connection.responseCode !in 200..299) return@withContext null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
            } catch (cause: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }
    }
}
