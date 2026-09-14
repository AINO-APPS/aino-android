package app.aino.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import app.aino.mobile.core.auth.AuthViewModel
import app.aino.mobile.core.designsystem.theme.AinoTheme
import app.aino.mobile.core.navigation.AinoApp
import app.aino.mobile.core.realtime.RealtimeViewModel
import app.aino.mobile.core.update.UpdateViewModel
import app.aino.mobile.feature.home.DashboardViewModel

class MainActivity : FragmentActivity() {
    private val authViewModel by viewModels<AuthViewModel> { AuthViewModel.factory(applicationContext) }
    private val updateViewModel by viewModels<UpdateViewModel> { UpdateViewModel.Factory }
    private val realtimeViewModel by viewModels<RealtimeViewModel> { RealtimeViewModel.factory(applicationContext) }
    private val dashboardViewModel by viewModels<DashboardViewModel> { DashboardViewModel.factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AinoTheme {
                AinoApp(
                    authViewModel,
                    updateViewModel,
                    realtimeViewModel,
                    dashboardViewModel,
                    biometricAvailable = biometricAvailable(),
                    onBiometricLogin = ::requestBiometricLogin,
                    onBiometricEnroll = ::requestBiometricEnrollment,
                )
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        authViewModel.recordUserActivity()
    }

    private fun biometricAvailable(): Boolean = BiometricManager.from(this).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG,
    ) == BiometricManager.BIOMETRIC_SUCCESS

    private fun requestBiometricEnrollment() {
        val cipher = runCatching(authViewModel::encryptionCipher).getOrElse {
            authViewModel.reportBiometricError("Secure biometric storage is unavailable on this device.")
            return
        }
        showBiometricPrompt("Enable biometric sign-in", cipher) { authenticated ->
            authViewModel.enrollBiometric(authenticated, "Android device")
        }
    }

    private fun requestBiometricLogin() {
        val cipher = authViewModel.decryptionCipher()
        if (cipher == null) {
            authViewModel.reportBiometricError("No biometric credential is enrolled on this device.")
            return
        }
        showBiometricPrompt("Sign in to AINO", cipher, authViewModel::biometricLogin)
    }

    private fun showBiometricPrompt(title: String, cipher: Cipher, onSuccess: (Cipher) -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onSuccess)
                        ?: authViewModel.reportBiometricError("Biometric authorization did not unlock the credential.")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        authViewModel.reportBiometricError(errString.toString())
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint or strong face unlock")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }
}
