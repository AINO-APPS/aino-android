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
import app.aino.mobile.core.designsystem.tokens.ThemePreferenceStore
import app.aino.mobile.core.designsystem.tokens.WebTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import app.aino.mobile.core.navigation.AinoApp
import app.aino.mobile.core.realtime.RealtimeViewModel
import app.aino.mobile.core.update.UpdateViewModel
import app.aino.mobile.feature.home.DashboardViewModel
import app.aino.mobile.feature.attendance.AttendanceViewModel
import app.aino.mobile.feature.tasks.TaskViewModel
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
    private val profileViewModel by viewModels<ProfileViewModel> { ProfileViewModel.factory(applicationContext) }
    private val chatViewModel by viewModels<ChatViewModel> { ChatViewModel.factory(applicationContext) }
    private val incomingCallViewModel by viewModels<IncomingCallViewModel> { IncomingCallViewModel.factory(applicationContext) }
    // Android 12+ silently ignores a request for FINE alone; it must be asked
    // together with COARSE. Only a precise grant satisfies the office geofence.
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) requestPendingAttendanceAction()
        else attendanceViewModel.locationPermissionDenied()
    }
    private fun requestLocationPermission() = locationPermission.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
    private val chatDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(chatViewModel::stageAttachment)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PushNotifications.createChannels(applicationContext)
        setContent {
            val themeStore = ThemePreferenceStore(applicationContext)
            val isDark by themeStore.isDark.collectAsState(initial = true)
            val themeScope = rememberCoroutineScope()
            WebTheme(darkTheme = isDark) {
                AinoApp(
                    authViewModel,
                    updateViewModel,
                    realtimeViewModel,
                    dashboardViewModel,
                    attendanceViewModel,
                    taskViewModel,
                    profileViewModel,
                    chatViewModel,
                    incomingCallViewModel,
                    isDark = isDark,
                    onSetDark = { dark -> themeScope.launch { themeStore.setDark(dark) } },
                    biometricAvailable = biometricAvailable(),
                    onBiometricLogin = ::requestBiometricLogin,
                    onBiometricEnroll = ::requestBiometricEnrollment,
                    onAttendanceLocationPermission = { requestLocationPermission() },
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
        if (savedInstanceState == null) consumePushTap(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    incomingCallViewModel.ui.collect { state ->
                        val visible = state.route != null && state.state !in setOf(IncomingCallState.Ended)
                        lockScreenController.setShowingForCall(this@MainActivity, visible)
                    }
                }
                // Leaving the app during a connected call or a meeting enters system PiP.
                val activeCall = app.aino.mobile.core.call.ActiveCallRuntime.get(applicationContext).ui
                val meeting = app.aino.mobile.feature.meeting.MeetingRuntime.get(applicationContext).state
                kotlinx.coroutines.flow.combine(activeCall, meeting) { call, live ->
                    when {
                        call.visible && call.connectedAt != null -> if (call.isVideo) 9 to 16 else 1 to 1
                        live != null -> 9 to 16
                        else -> null
                    }
                }.collect { ratio ->
                    pipController.setCallActive(this@MainActivity, ratio != null, ratio?.first ?: 1, ratio?.second ?: 1)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallIntent(intent)
        consumePushTap(intent)
    }

    /** A tapped notification is routed by the signed-in shell (see `PendingPushTap`). */
    private fun consumePushTap(intent: android.content.Intent?) {
        app.aino.mobile.core.push.parsePushTap(intent)?.let(app.aino.mobile.core.push.PendingPushTap::set) ?: return
        intent?.removeExtra("push_type")
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        authViewModel.recordUserActivity()
        profileViewModel.recordActivity()
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
        app.aino.mobile.core.call.PipState.inPip.value = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        app.aino.mobile.core.notifications.NotificationSoundPrefs.appVisible = true
    }

    override fun onStop() {
        app.aino.mobile.core.notifications.NotificationSoundPrefs.appVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        consumePendingCallAction()
        Thread { runCatching { PushTokenRegistrar(applicationContext).syncCurrentToken() } }.start()
    }

    override fun onDestroy() {
        lockScreenController.release()
        if (isFinishing) {
            // The realtime socket is Activity-scoped and closes with it; don't
            // leave call/meeting media running with no signaling (swiped task).
            app.aino.mobile.feature.meeting.MeetingRuntime.get(applicationContext).leave()
            app.aino.mobile.core.call.ActiveCallRuntime.get(applicationContext).let { if (it.ui.value.visible) it.hangUp() }
        }
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
            onPermissionRequired = { requestLocationPermission() },
        )
    }

    private fun requestAttendanceBiometric() {
        // The server's fingerprint path accepts any OS-level identity check once
        // office presence is proven, so allow the screen lock as a fallback;
        // BIOMETRIC_STRONG alone failed silently on devices without a strong sensor.
        val authenticators = if (android.os.Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            attendanceViewModel.reportVerifyError(
                "Set up a fingerprint, face unlock or screen lock on this device to verify attendance.",
            )
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    attendanceViewModel.submit(fingerprintVerified = true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    attendanceViewModel.reportVerifyError(if (cancelled) "Identity check cancelled. Tap verify to try again." else errString.toString())
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify attendance")
                .setSubtitle("Confirm your identity from the office")
                .setAllowedAuthenticators(authenticators)
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
