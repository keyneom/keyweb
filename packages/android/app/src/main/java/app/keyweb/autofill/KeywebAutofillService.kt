package app.keyweb.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import app.keyweb.R

/**
 * Filling passwords into other apps and into the browser.
 *
 * ## Why this never reads the vault
 *
 * The vault's key lives in the Keystore behind the device lock, and this
 * service runs whenever any app shows a text field — unprompted, in the
 * background, many times a minute. It must not be able to decrypt anything.
 *
 * So the response it builds is an *authentication* response: a single row
 * saying "Unlock Keyweb", whose selection launches [AutofillUnlockActivity].
 * That activity raises the usual biometric prompt, and only then is the vault
 * opened and the real choices returned. The effect is that a password is
 * decrypted only after the person has asked for it and proved who they are,
 * which is the same rule the rest of the app follows.
 *
 * ## What it deliberately does not do
 *
 * It does not offer to save. A password manager that pops up "save this?" on
 * every form is how people end up with a vault full of half-entries, and
 * Keyweb has no way yet to show and tidy those. [onSaveRequest] is therefore
 * unimplemented rather than half-implemented.
 */
class KeywebAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        val read = StructureReader.read(structure)
        val found = FieldFinder.find(read.fields)
        if (found.isEmpty) {
            // Nothing here is a login. Saying so costs nothing and stops the
            // platform asking again for this request.
            callback.onSuccess(null)
            return
        }

        val fillable = listOfNotNull(found.username?.id, found.password?.id)
            .filterIsInstance<AutofillId>()
            .toTypedArray()
        if (fillable.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val domain = Matching.domainForFill(found.username?.webDomain, found.password?.webDomain)
        if (domain is Matching.FillDomain.Conflict) {
            // A form that names two sites is not one login. Offering either
            // password would hand it to the other site.
            callback.onSuccess(null)
            return
        }
        val webDomain = (domain as? Matching.FillDomain.Known)?.domain

        val intent = Intent(this, AutofillUnlockActivity::class.java).apply {
            putExtra(AutofillUnlockActivity.EXTRA_WEB_DOMAIN, webDomain)
            putExtra(AutofillUnlockActivity.EXTRA_PACKAGE, callingPackageOf(structure))
            putExtra(AutofillUnlockActivity.EXTRA_USERNAME_ID, found.username?.id as? AutofillId)
            putExtra(AutofillUnlockActivity.EXTRA_PASSWORD_ID, found.password?.id as? AutofillId)
        }
        val pending = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            // Mutable: the platform fills in the authentication result extras.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        callback.onSuccess(
            FillResponse.Builder()
                .setAuthentication(fillable, pending.intentSender, unlockRow())
                .build(),
        )
    }

    /**
     * Not implemented, and that is a decision rather than an omission.
     *
     * See the note on the class: offering to save on every form produces a
     * vault of near-duplicates, and Keyweb cannot yet help anyone clean that
     * up. Declining to be asked is better than asking badly.
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onFailure(null)
    }

    private fun unlockRow(): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_label, "Unlock Keyweb to fill")
        }

    /** The app being filled, used to guess a domain when there is no URL. */
    private fun callingPackageOf(structure: android.app.assist.AssistStructure): String? =
        structure.activityComponent?.packageName

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
