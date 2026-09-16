package app.keyweb.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.keyweb.data.RoomVaultStorage
import app.keyweb.data.UnlockResult
import app.keyweb.data.VaultDatabase
import app.keyweb.data.VaultKeystore
import app.keyweb.data.VaultUnlock
import app.keyweb.vault.Fields
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.field
import app.keyweb.vault.visibleItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unlocking, then handing one password to whoever asked for it.
 *
 * The Credential Manager counterpart of [AutofillUnlockActivity], and the same
 * shape for the same reason: the provider service holds no key, and the vault
 * is opened here, behind the device lock, only once a person has chosen to.
 *
 * The difference is what comes back. Autofill types text into two fields it
 * had to identify; this returns a [PasswordCredential] to an app that asked
 * for one by name, so there is nothing to guess about which field is which.
 *
 * Returning without a result cancels the request, which leaves the asking app
 * to fall back to its own sign-in rather than hang waiting.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialUnlockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        lifecycleScope.launch {
            val unlocked = VaultUnlock.prompt(this@CredentialUnlockActivity, firstRun = false)
            if (unlocked !is UnlockResult.Unlocked) {
                cancel("That was cancelled.")
                return@launch
            }

            val match = runCatching { bestMatch() }.getOrNull()
            if (match == null) {
                cancel("Keyweb has no password saved for this.")
                return@launch
            }

            val result = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                result,
                androidx.credentials.GetCredentialResponse(
                    PasswordCredential(
                        id = match.field(Fields.USERNAME).orEmpty(),
                        password = match.field(Fields.PASSWORD).orEmpty(),
                    ),
                ),
            )
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    /**
     * The one password that belongs to whoever asked.
     *
     * Credential Manager returns a single credential rather than a list, so
     * where autofill can offer a choice this has to pick. It picks only when
     * the answer is unambiguous: exactly one saved entry for that site. Two
     * accounts on one site is a real situation and guessing between them would
     * sign someone in as the wrong person, which is worse than not helping.
     */
    private suspend fun bestMatch(): ItemRecord? = withContext(Dispatchers.IO) {
        if (!VaultKeystore.exists()) return@withContext null
        val database = VaultDatabase.open(applicationContext)
        val storage = RoomVaultStorage(database.dao(), VaultKeystore.cipher())
        val items = visibleItems(storage.readState())

        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val byOrigin = items.filter { Matching.urlMatches(it.field(Fields.URL), origin) }
        val candidates = byOrigin.ifEmpty {
            val guessed = Matching.domainGuessedFromPackage(intent.getStringExtra(EXTRA_PACKAGE))
            items.filter { Matching.urlMatches(it.field(Fields.URL), guessed) }
        }
        candidates.singleOrNull()
    }

    private fun cancel(because: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(
            result,
            GetCredentialCancellationException(because),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_ORIGIN = "app.keyweb.credentials.ORIGIN"
        const val EXTRA_PACKAGE = "app.keyweb.credentials.PACKAGE"
    }
}
