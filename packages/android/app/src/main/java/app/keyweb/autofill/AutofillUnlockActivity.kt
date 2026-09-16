package app.keyweb.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.keyweb.R
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
 * The step between "Keyweb could fill this" and actually filling it.
 *
 * The autofill service runs constantly and holds no key. This activity is
 * where the vault is opened, and only after the same face, fingerprint or PIN
 * that opens the app itself. It shows no interface of its own beyond that
 * prompt: it authenticates, builds the list of matching passwords, hands it
 * back to the platform and finishes.
 *
 * Failing — cancelled, no match, vault unreadable — returns a cancelled result
 * rather than an empty one, so the platform leaves the field alone instead of
 * showing an empty Keyweb dropdown.
 */
class AutofillUnlockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val usernameId = intentExtra<AutofillId>(EXTRA_USERNAME_ID)
        val passwordId = intentExtra<AutofillId>(EXTRA_PASSWORD_ID)
        if (usernameId == null && passwordId == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val unlocked = VaultUnlock.prompt(this@AutofillUnlockActivity, firstRun = false)
            if (unlocked !is UnlockResult.Unlocked) {
                finish()
                return@launch
            }

            val matches = runCatching { matchingItems() }.getOrDefault(emptyList())
            if (matches.isEmpty()) {
                finish()
                return@launch
            }

            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(
                    AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                    response(matches, usernameId, passwordId),
                ),
            )
            finish()
        }
    }

    /**
     * The saved passwords that belong to whatever asked.
     *
     * By URL first, which is the only signal that is actually evidence. The
     * package-name guess is a fallback and is used only when the URL match
     * found nothing, so a browser request never falls back to it.
     */
    private suspend fun matchingItems(): List<ItemRecord> = withContext(Dispatchers.IO) {
        if (!VaultKeystore.exists()) return@withContext emptyList()
        val database = VaultDatabase.open(applicationContext)
        val storage = RoomVaultStorage(database.dao(), VaultKeystore.cipher())
        val items = visibleItems(storage.readState())

        val domain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        val byUrl = items.filter { Matching.urlMatches(it.field(Fields.URL), domain) }
        if (byUrl.isNotEmpty() || domain != null) return@withContext byUrl

        val guessed = Matching.domainGuessedFromPackage(intent.getStringExtra(EXTRA_PACKAGE))
        items.filter { Matching.urlMatches(it.field(Fields.URL), guessed) }
    }

    private fun response(
        matches: List<ItemRecord>,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
    ): FillResponse {
        val builder = FillResponse.Builder()
        for (item in matches) {
            val title = item.field(Fields.TITLE).orEmpty().ifEmpty { "Untitled" }
            val username = item.field(Fields.USERNAME).orEmpty()
            val dataset = Dataset.Builder(row(if (username.isEmpty()) title else "$title · $username"))
            usernameId?.let { dataset.setValue(it, AutofillValue.forText(username)) }
            passwordId?.let {
                dataset.setValue(it, AutofillValue.forText(item.field(Fields.PASSWORD).orEmpty()))
            }
            builder.addDataset(dataset.build())
        }
        return builder.build()
    }

    private fun row(label: String): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_label, label)
        }

    private inline fun <reified T> intentExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(name)
        }

    companion object {
        const val EXTRA_WEB_DOMAIN = "app.keyweb.autofill.WEB_DOMAIN"
        const val EXTRA_PACKAGE = "app.keyweb.autofill.PACKAGE"
        const val EXTRA_USERNAME_ID = "app.keyweb.autofill.USERNAME_ID"
        const val EXTRA_PASSWORD_ID = "app.keyweb.autofill.PASSWORD_ID"
    }
}
