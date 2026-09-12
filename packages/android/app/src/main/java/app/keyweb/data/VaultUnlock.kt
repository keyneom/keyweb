package app.keyweb.data

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Asking the user to prove it is them, so the Keystore will release the vault key.
 *
 * The key is bound to a validity window rather than a single crypto operation,
 * so a plain authentication is enough — no `CryptoObject` round trip, and no
 * prompt on every save.
 */
sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    /** The user dismissed the prompt. Not an error; the vault stays locked. */
    data object Cancelled : UnlockResult
    data class Failed(val message: String) : UnlockResult
    /** No PIN, pattern, password or biometric is set up on the device at all. */
    data object NoDeviceLock : UnlockResult
}

private const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

object VaultUnlock {

    /** Whether this device can protect a vault at all. */
    fun deviceCanProtect(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    suspend fun prompt(activity: FragmentActivity, firstRun: Boolean): UnlockResult {
        if (!deviceCanProtect(activity)) return UnlockResult.NoDeviceLock

        val authenticated = suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) continuation.resume(UnlockResult.Unlocked)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (!continuation.isActive) return
                        val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                            code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            code == BiometricPrompt.ERROR_CANCELED
                        continuation.resume(
                            if (cancelled) {
                                UnlockResult.Cancelled
                            } else {
                                UnlockResult.Failed(message.toString())
                            },
                        )
                    }

                    // onAuthenticationFailed fires for a single bad read; the
                    // prompt stays up and the user tries again, so it is not
                    // an outcome we resume on.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (firstRun) "Set up Keyweb" else "Unlock Keyweb")
                .setSubtitle(
                    if (firstRun) {
                        "Keyweb will lock your passwords with this."
                    } else {
                        "Use your face, fingerprint or screen lock."
                    },
                )
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

        if (authenticated !is UnlockResult.Unlocked) return authenticated

        // The key can only be created once the user has authenticated, because
        // creating it requires a secure lock screen to exist.
        if (!VaultKeystore.exists() && !VaultKeystore.create()) {
            return UnlockResult.Failed(
                "Keyweb couldn't create a key on this device. Check that a screen lock is set up.",
            )
        }
        return UnlockResult.Unlocked
    }
}
