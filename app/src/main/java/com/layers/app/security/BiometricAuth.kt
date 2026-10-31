package com.layers.app.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint, face or the phone's PIN/pattern/password via BiometricPrompt.
 * Android 10 cannot combine "strong biometric" with "device credential", so it falls back to
 * BIOMETRIC_WEAK | DEVICE_CREDENTIAL there.
 */
object BiometricAuth {

    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }

    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit = {},
    ) {
        if (!canAuthenticate(activity)) {
            onFailure("Set up a screen lock (PIN, pattern, password or fingerprint) in your phone settings to use locking.")
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onFailure(errString.toString())
                    } else {
                        onFailure("")
                    }
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
        if (subtitle != null) builder.setSubtitle(subtitle)
        prompt.authenticate(builder.build())
    }
}

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity

/** Reference-counted FLAG_SECURE so overlapping screens don't clear each other's protection. */
object SecureWindow {
    private var count = 0

    fun acquire(activity: Activity) {
        count++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        count = (count - 1).coerceAtLeast(0)
        if (count == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Blocks screenshots and hides the recent-apps preview while [enabled] is true. */
@Composable
fun SecureContent(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        if (enabled && activity != null) SecureWindow.acquire(activity)
        onDispose {
            if (enabled && activity != null) SecureWindow.release(activity)
        }
    }
}
