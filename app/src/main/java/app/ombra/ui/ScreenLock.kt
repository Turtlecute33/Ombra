package app.ombra.ui

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Asks for the screen lock (a strong biometric, or the PIN, pattern or password), then runs
 * [onSuccess] (DESIGN §10). Without a screen lock there is nothing to ask, so [onSuccess] runs at
 * once. Cancel and errors run nothing; the system prompt shows its own error text.
 */
fun confirmScreenLock(context: Context, title: String, onSuccess: () -> Unit) {
    if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
        onSuccess()
        return
    }
    BiometricPrompt.Builder(context)
        .setTitle(title)
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        .build()
        .authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
}
