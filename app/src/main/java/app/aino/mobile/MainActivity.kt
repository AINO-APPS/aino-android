package app.aino.mobile

import android.os.Bundle
import android.Manifest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
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
import app.aino.mobile.feature.attendance.AttendanceViewModel
import app.aino.mobile.feature.tasks.TaskViewModel
import app.aino.mobile.feature.leaves.LeaveViewModel
import app.aino.mobile.feature.profile.ProfileViewModel
import app.aino.mobile.core.push.PushNotifications
import app.aino.mobile.core.push.PushTokenRegistrar
import app.aino.mobile.core.call.PipController
import app.aino.mobile.core.call.IncomingCallViewModel
import app.aino.mobile.core.call.parseIncomingCallRoute
import app.aino.mobile.core.call.PendingCallActionStore
import app.aino.mobile.core.call.LockScreenController
import app.aino.mobile.core.call.IncomingCallState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import app.aino.mobile.feature.chat.ChatViewModel

class MainActivity : FragmentActivity() {
    private val pipController = PipController()
    private val lockScreenController = LockScreenController()
    private val authViewModel by viewModels<AuthViewModel> { AuthViewModel.factory(applicationContext) }
    private val updateViewModel by viewModels<UpdateViewModel> { UpdateViewModel.Factory }
    private val realtimeViewModel by viewModels<RealtimeViewModel> { RealtimeViewModel.factory(applicationContext) }
    private val dashboardViewModel by viewModels<DashboardViewModel> { DashboardViewModel.factory(applicationContext) }
    private val attendanceViewModel by viewModels<AttendanceViewModel> { AttendanceViewModel.factory(applicationContext) }
    private val taskViewModel by viewModels<TaskViewModel> { TaskViewModel.factory(applicationContext) }
    private val leaveViewModel by viewModels<LeaveViewModel> { LeaveViewModel.factory(applicationContext) }
    private val profileViewModel by viewModels<ProfileViewModel> { ProfileViewModel.factory(applicationContext) }
    private val chatViewModel by viewModels<ChatViewModel> { ChatViewModel.factory(applicationContext) }
    private val incomingCallViewModel by viewModels<IncomingCallViewModel> { IncomingCallViewModel.factory(applicationContext) }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestPendingAttendanceAction()
        else attendanceViewModel.reportError("Precise location permission is required for verified office attendance.")
    }
    private val chatDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(chatViewModel::upload)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PushNotifications.createChannels(applicationContext)
        setContent {
            AinoTheme {
                AinoApp(
                    authViewModel,
                    updateViewModel,
                    realtimeViewModel,
                    dashboardViewModel,
                    attendanceViewModel,
                    taskViewModel,
                    leaveViewModel,
                    profileViewModel,
                    chatViewModel,
                    incomingCallViewModel,
                    biometricAvailable = biometricAvailable(),
                    onBiometricLogin = ::requestBiometricLogin,
                    onBiometricEnroll = ::requestBiometricEnrollment,
                    onAttendanceLocationPermission = { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    onAttendanceBiometric = ::requestAttendanceBiometric,
                    onPickChatDocument = {
                        chatDocumentPicker.launch(
                            arrayOf(
                                "image/*", "video/*", "audio/*", "application/pdf", "application/zip",
                                "application/msword", "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "text/plain", "text/csv",
                            ),
                        )
                    },
                    onAuthenticatedForPush = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
        consumeCallIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                incomingCallViewModel.ui.collect { state ->
                    val visible = state.route != null && state.state !in setOf(IncomingCallState.Ended)
                    lockScreenController.setShowingForCall(this@MainActivity, visible)
                    val activeMedia = state.state == IncomingCallState.WaitingForMedia
                    pipController.setCallActive(this@MainActivity, activeMedia, if (state.route?.callType == "video") 9 else 1, if (state.route?.callType == "video") 16 else 1)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallIntent(intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        authViewModel.recordUserActivity()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.onUserLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        consumePendingCallAction()
        Thread { runCatching { PushTokenRegistrar(applicationContext).syncCurrentToken() } }.start()
    }

    override fun onDestroy() {
        lockScreenController.release()
        super.onDestroy()
    }

    private fun consumeCallIntent(intent: android.content.Intent?) {
        parseIncomingCallRoute(intent?.data)?.let(incomingCallViewModel::route)
    }

    private fun consumePendingCallAction() {
        val pending = PendingCallActionStore.read(applicationContext) ?: return
        val current = incomingCallViewModel.ui.value.route ?: return
        if (pending["callId"] != current.callId.toString() || pending["conversationId"] != current.conversationId.toString()) return
        when (pending["action"]) {
            "answer" -> incomingCallViewModel.answer()
            "decline" -> incomingCallViewModel.decline()
        }
        PendingCallActionStore.clear(applicationContext)
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

    private fun requestPendingAttendanceAction() {
        attendanceViewModel.resumePending(
            onPermissionRequired = { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onBiometricRequired = ::requestAttendanceBiometric,
        )
    }

    private fun requestAttendanceBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    attendanceViewModel.submit(fingerprintVerified = true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        attendanceViewModel.reportError(errString.toString())
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify attendance")
                .setSubtitle("Confirm your identity from the office")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
        )
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
