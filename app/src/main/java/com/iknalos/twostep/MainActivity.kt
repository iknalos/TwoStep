package com.iknalos.twostep

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.iknalos.twostep.ui.TwoStepApp
import com.iknalos.twostep.ui.TwoStepTheme
import com.journeyapps.barcodescanner.CaptureActivity

/** Hosts the Compose UI, plus the bits that need an Activity: app lock, screenshot blocking, clipboard. */
class MainActivity : FragmentActivity() {

    val model: AppModel by viewModels()
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy()
        setContent {
            TwoStepTheme {
                TwoStepApp(model, this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onStart()
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
        if (model.locked) promptUnlock()
    }

    override fun onStop() {
        model.onStop()
        super.onStop()
    }

    /** FLAG_SECURE keeps codes out of screenshots, screen recordings and the recent-apps preview. */
    fun applyScreenshotPolicy() {
        if (model.settings.allowScreenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun canUseLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun promptUnlock() = authenticate("Unlock TwoStep") { model.unlocked() }

    fun authenticate(title: String, onSuccess: () -> Unit) {
        if (prompting) return
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint or screen lock")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }

    /** Marked sensitive, so Android 13+ hides the code in its clipboard preview and keyboards don't store it. */
    fun copyCode(code: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("2FA code", code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}

/** The QR scanner, kept upright (the library defaults to landscape). */
class PortraitCaptureActivity : CaptureActivity()
