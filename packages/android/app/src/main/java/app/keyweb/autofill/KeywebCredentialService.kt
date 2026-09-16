package app.keyweb.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.AuthenticationAction

/**
 * Keyweb as a Credential Manager provider.
 *
 * ## How this differs from the autofill service beside it
 *
 * The two are not alternatives. [KeywebAutofillService] answers when a form
 * appears — any login screen, any web page — by inspecting the view tree.
 * Credential Manager answers only when an app *asks* for a credential through
 * the platform API, and hands over a structured result instead of typing text
 * into fields. Modern apps use the second; everything else still relies on the
 * first, which is why both are here.
 *
 * ## Passwords only, deliberately
 *
 * A provider declares the credential types it handles. Keyweb declares
 * passwords and nothing else, which is a complete answer rather than a partial
 * one — the system simply asks other providers for the rest.
 *
 * Passkeys are absent because Keyweb has nowhere to keep one yet, not because
 * a password manager cannot hold them: a passkey is a keypair whose private
 * half stays with the provider, needing no server of ours. It needs a vault
 * item kind and a correct WebAuthn implementation, which is its own piece of
 * work. Federated sign-in belongs to identity providers and never will be
 * ours.
 *
 * ## It still never reads the vault
 *
 * Same rule as autofill, for the same reason. This returns an authentication
 * entry; the vault is opened behind the device lock in
 * [CredentialUnlockActivity] and only once someone has asked.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class KeywebCredentialService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        // Only password requests concern us; anything else is another
        // provider's to answer and is left alone rather than refused.
        val wantsPassword = request.beginGetCredentialOptions.any { it is BeginGetPasswordOption }
        if (!wantsPassword) {
            callback.onResult(BeginGetCredentialResponse())
            return
        }

        val intent = Intent(this, CredentialUnlockActivity::class.java).apply {
            /*
             * The package name, not the web origin.
             *
             * `CallingAppInfo.origin` is only ever set for a *privileged*
             * caller — a browser Google has allowlisted to request credentials
             * on a website's behalf — and androidx.credentials 1.6 made it
             * unreadable without that allowlist to stop providers treating a
             * plain app's claim about a website as trustworthy. Keyweb is not
             * privileged and never will be, so the field was always null here;
             * reading it was a request for a permission we do not hold.
             *
             * The package name is what actually identifies the caller, and it
             * is what the matching rules work from.
             */
            putExtra(
                CredentialUnlockActivity.EXTRA_ORIGIN,
                request.callingAppInfo?.packageName,
            )
            putExtra(
                CredentialUnlockActivity.EXTRA_PACKAGE,
                request.callingAppInfo?.packageName,
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        callback.onResult(
            BeginGetCredentialResponse(
                // Locked, so the one thing on offer is unlocking. The real
                // choices appear after the device lock, never before it.
                authenticationActions = listOf(
                    AuthenticationAction("Unlock Keyweb", pending),
                ),
            ),
        )
    }

    /**
     * Saving is not supported, matching the autofill service.
     *
     * A manager that offers to save on every sign-in accumulates
     * near-duplicates, and Keyweb has no way to show or tidy those yet.
     * Declining is honest; accepting and doing it badly is not.
     */
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        callback.onError(CreateCredentialUnsupportedException("Keyweb doesn't save from here yet."))
    }

    /**
     * Nothing to clear: Keyweb keeps no per-app credential state outside the
     * vault, and the vault is not something a sign-out should empty.
     */
    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    private companion object {
        const val REQUEST_CODE = 2001
    }
}
