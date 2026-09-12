package app.keyweb.data

import android.content.Context
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Drive access token, and the consent screen that earns one.
 *
 * There is no client secret here and there must not be. An Android app cannot
 * hold one — anyone can unzip the APK — so Google identifies Keyweb by its
 * package name and signing certificate, registered as an Android OAuth client.
 * That is why a release build signed with a different key cannot reach Drive,
 * and why the release keystore matters as much as it does.
 *
 * The token is short-lived and deliberately not cached beyond Play Services'
 * own cache: `authorize` returns silently once consent exists, so asking every
 * time costs nothing and never serves a stale token.
 */
class GoogleAuthorizer(context: Context) {

    private val client = Identity.getAuthorizationClient(context.applicationContext)

    /** Serialized so a burst of syncs cannot raise two consent screens. */
    private val gate = Mutex()

    /**
     * Raised when Google needs the user to approve access.
     *
     * Delivered rather than launched, because only an Activity can show it and
     * this class deliberately holds no reference to one.
     */
    class ConsentRequired(val intentSender: IntentSender) :
        Exception("Keyweb needs your permission to use Google Drive.")

    class NotSignedIn(message: String) : Exception(message)

    private val request: AuthorizationRequest by lazy {
        AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map(::Scope))
            .build()
    }

    /**
     * The current access token, asking for consent only when there is none.
     *
     * Throws [ConsentRequired] rather than blocking, so the caller decides
     * whether this is a moment to interrupt someone. A background sync should
     * stay quiet; a tap on "Set up backup" should not.
     */
    suspend fun accessToken(): String = gate.withLock {
        val result = suspendCancellableCoroutine { continuation ->
            client.authorize(request)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener {
                    continuation.resumeWithException(
                        NotSignedIn(
                            it.message ?: "Keyweb couldn't reach your Google account.",
                        ),
                    )
                }
        }
        result.pendingIntent?.let { throw ConsentRequired(it.intentSender) }
        result.accessToken
            ?: throw NotSignedIn("Google didn't return permission to use Drive.")
    }

    /** True when Drive access is already granted, so nothing needs to be shown. */
    suspend fun hasAccess(): Boolean =
        try {
            accessToken()
            true
        } catch (cause: Exception) {
            false
        }

    companion object {
        /**
         * Both scopes, for the reasons recorded in `docs/google-setup.md`.
         *
         * `drive.file` holds the encrypted vault. `drive.appdata` holds the
         * sharing identity that lets a *second* device of the same user decrypt
         * what the first one wrote — without it the backup is readable only on
         * the device that created it, which defeats the point.
         */
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive.file",
            "https://www.googleapis.com/auth/drive.appdata",
        )
    }
}
