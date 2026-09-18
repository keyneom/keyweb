package app.keyweb.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.keyweb.data.RoomVaultStorage
import app.keyweb.ui.KeywebTheme
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
 *
 * ## Why a password is never handed over silently
 *
 * This used to return the single matching entry as soon as the device lock was
 * satisfied. The person saw a fingerprint prompt and then nothing: not which
 * password left the vault, and not who received it.
 *
 * That would be defensible if the recipient were established. It is not.
 * Credential Manager gives a non-privileged provider a package name and
 * nothing more — `CallingAppInfo.origin` is null unless the caller is
 * privileged — so the site a request belongs to is *inferred* by reversing the
 * package name, which is a guess, unverified, and chosen by the caller. An app
 * named `com.chase` would be offered the Chase password without anybody being
 * shown that it was about to happen.
 *
 * So the vault is opened behind the device lock, and then the person is shown
 * what is being asked for and by whom, and picks. Digital Asset Links is the
 * real answer to the identity question and needs a network call per lookup;
 * until it is here, a human confirming is the check.
 *
 * Asking also fixed the other half. Two accounts on one site is ordinary, and
 * the old code refused to help at all in that case rather than guess between
 * them — correct, and useless. A list is the answer to both problems.
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

            val found = runCatching { matches() }.getOrNull()
            if (found == null || found.items.isEmpty()) {
                cancel("Keyweb has no password saved for this.")
                return@launch
            }

            setContent {
                KeywebTheme {
                    CredentialChooser(
                        asking = found.asking,
                        guessed = found.guessed,
                        items = found.items,
                        onPick = ::hand,
                        onCancel = { cancel("That was cancelled.") },
                    )
                }
            }
        }
    }

    /** Hand one password to the app that asked, and only when told to. */
    private fun hand(item: ItemRecord) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(
                PasswordCredential(
                    id = item.field(Fields.USERNAME).orEmpty(),
                    password = item.field(Fields.PASSWORD).orEmpty(),
                ),
            ),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /**
     * What could plausibly be meant, and how sure we are about who is asking.
     *
     * [guessed] is the honest part: true when nothing identified the caller
     * except its package name, reversed into a domain. The chooser says so, so
     * the person is deciding with the same information this code has rather
     * than with a confident-looking name it inferred.
     */
    private data class Candidates(
        val items: List<ItemRecord>,
        val asking: String,
        val guessed: Boolean,
    )

    private suspend fun matches(): Candidates? = withContext(Dispatchers.IO) {
        if (!VaultKeystore.exists()) return@withContext null
        val database = VaultDatabase.open(applicationContext)
        val storage = RoomVaultStorage(database.dao(), VaultKeystore.cipher())
        val items = visibleItems(storage.readState())

        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val byOrigin = items.filter { Matching.urlMatches(it.field(Fields.URL), origin) }
        if (byOrigin.isNotEmpty() && !origin.isNullOrEmpty()) {
            return@withContext Candidates(byOrigin, origin, guessed = false)
        }

        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val guessed = Matching.domainGuessedFromPackage(packageName)
        Candidates(
            items = items.filter { Matching.urlMatches(it.field(Fields.URL), guessed) },
            asking = guessed ?: packageName.orEmpty(),
            guessed = true,
        )
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
