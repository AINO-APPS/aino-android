package app.aino.mobile

import android.os.Bundle
import android.Manifest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.aino.mobile.feature.attendance.LOCATION_DISABLED_CODE
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
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
import app.aino.mobile.core.update.createAppUpdater
import app.aino.mobile.feature.home.DashboardViewModel
import app.aino.mobile.feature.attendance.AttendanceViewModel
import app.aino.mobile.feature.attendance.DeviceCredentialProof
import app.aino.mobile.feature.attendance.VerifyFix
import app.aino.mobile.core.navigation.AttendanceSystemActions
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
    private val appUpdater by lazy { createAppUpdater() }
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
        when {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true -> requestPendingAttendanceAction()
            // Android 12+: the user picked "Approximate" — ask for precise again.
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                attendanceViewModel.locationPermissionDenied(approximateOnly = true)
            // No rationale after a denial means "Don't ask again": only Settings can fix it.
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                attendanceViewModel.locationPermissionDenied(permanentlyDenied = true)
            else -> attendanceViewModel.locationPermissionDenied()
        }
    }
    private fun requestLocationPermission() = locationPermission.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
    private val chatDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(chatViewModel::stageAttachment)
    }
    private val locationResolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) resumeAfterLocationEnabled(locationEnabled = true)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.aino.mobile.core.common.CrashReporting.init(applicationContext)
        enableEdgeToEdge()
        PushNotifications.createChannels(applicationContext)
        // A ring left behind by a process that died is cleared once it expired.
        app.aino.mobile.core.call.CallReconciler.clearStaleRing(applicationContext)
        setContent {
            val themeStore = ThemePreferenceStore(applicationContext)
            val isDark by themeStore.isDark.collectAsState(initial = true)
            val themeScope = rememberCoroutineScope()
            // P10.4: the org accent repaints the palette (web BrandingContext `--primary`).
            val branding by app.aino.mobile.core.AppContainer.get(applicationContext).branding.state.collectAsState()
            WebTheme(darkTheme = isDark, accent = branding.accentColor) {
                AinoApp(
                    authViewModel,
                    appUpdater,
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
                    onBiometricEnroll = { requestBiometricEnrollment() },
                    onAttendanceLocationPermission = { requestLocationPermission() },
                    onAttendanceBiometric = ::requestAttendanceBiometric,
                    attendanceSystemActions = AttendanceSystemActions(
                        openAppSettings = ::openAppSettings,
                        enableLocation = ::enableLocation,
                        openSecuritySettings = ::openSecuritySettings,
                        enableFingerprintForAttendance = { requestBiometricEnrollment(thenClockAction = true) },
                    ),
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
                // Location turned on from Settings / Quick Settings while the sheet waits on it.
                launch {
                    attendanceViewModel.ui
                        .map { it.verifySession?.submitError?.code == LOCATION_DISABLED_CODE }
                        .distinctUntilChanged()
                        .collectLatest { waiting -> if (waiting) locationProviderChanges().collect { resumeAfterLocationEnabled() } }
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
        // A missed-call notification / "Call back": clear it and queue the call for its thread.
        app.aino.mobile.core.call.MissedCallNotifier.consumeIntent(applicationContext, intent)
        app.aino.mobile.core.push.parsePushTap(intent)?.let(app.aino.mobile.core.push.PendingPushTap::set) ?: return
        intent?.removeExtra("push_type")
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
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
        resumeAfterLocationEnabled()
        appUpdater.check(this)
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
            "answer" -> incomingCallViewModel.answerFromNotification()
            "decline" -> incomingCallViewModel.decline()
        }
        PendingCallActionStore.clear(applicationContext)
    }

    // ── Fingerprint / PIN: one enrolled credential for sign-in and clock-in ──

    /** Fingerprint, secure face unlock, or the device screen lock (PIN/pattern/password). */
    private val credentialAuthenticators: Int
        get() = if (android.os.Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // API 26–29 cannot combine DEVICE_CREDENTIAL with BIOMETRIC_STRONG.
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    /** A fingerprint, face unlock or screen lock is set up, so the credential can be used. */
    private fun biometricAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(credentialAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private fun requestBiometricEnrollment(thenClockAction: Boolean = false) {
        if (!biometricAvailable()) {
            val message = "Set up a fingerprint or a screen lock (PIN/pattern) on this phone first."
            if (thenClockAction) attendanceViewModel.reportVerifyError(message, VerifyFix.SetUpScreenLock, "Screen Lock Needed")
            else authViewModel.reportBiometricError(message)
            return
        }
        // API 30+: the per-use key needs its cipher before the prompt. API 26–29:
        // the time-bound key can only be initialised after the prompt, so the
        // cipher is built inside authenticateForCipher instead.
        val cipher = if (authViewModel.biometricUsesCryptoObject()) {
            runCatching(authViewModel::encryptionCipher).getOrElse {
                val message = "Secure storage is unavailable on this device."
                if (thenClockAction) attendanceViewModel.reportVerifyError(message) else authViewModel.reportBiometricError(message)
                return
            }
        } else null
        if (thenClockAction) attendanceViewModel.identityPromptShown()
        authenticateForCipher(
            title = "Enable fingerprint for AINO",
            subtitle = "Used to sign in and to clock in / out",
            cipher = cipher,
            onCipher = { authenticated ->
                authViewModel.enrollBiometric(authenticated, deviceLabel()) {
                    // Continue the clock action straight away with the new credential.
                    if (thenClockAction) attendanceViewModel.requestIdentityPrompt()
                }
            },
            onError = { message, _ ->
                if (thenClockAction) {
                    attendanceViewModel.reportVerifyError(message ?: "Fingerprint setup cancelled.", VerifyFix.EnableFingerprint, "Fingerprint Not Enabled")
                } else {
                    message?.let(authViewModel::reportBiometricError)
                }
            },
        )
    }

    private fun requestBiometricLogin() {
        if (authViewModel.biometricNeedsUpgrade()) {
            authViewModel.dropLegacyCredential()
            authViewModel.reportBiometricError("Fingerprint sign-in was upgraded to also accept your PIN. Sign in with your password once, then enable it again.")
            return
        }
        withDecryptionCipher(
            title = "Sign in to AINO",
            subtitle = "Use your fingerprint or screen lock",
            onCipher = authViewModel::biometricLogin,
            onError = { message, _ -> message?.let(authViewModel::reportBiometricError) },
        )
    }

    private fun requestPendingAttendanceAction() {
        attendanceViewModel.resumePending(onPermissionRequired = { requestLocationPermission() })
    }

    /**
     * Attendance identity step: unlock the enrolled device credential with the
     * fingerprint / PIN prompt and submit it. Not enrolled yet → enroll inline
     * and continue, so the first clock-in never dead-ends.
     */
    private fun requestAttendanceBiometric() {
        if (!biometricAvailable()) {
            attendanceViewModel.reportVerifyError(
                "Set up a fingerprint or a screen lock (PIN/pattern) on this phone to clock in and out.",
                VerifyFix.SetUpScreenLock, "Screen Lock Needed",
            )
            return
        }
        authViewModel.dropLegacyCredential()
        if (!authViewModel.ui.value.biometricEnrolled) {
            requestBiometricEnrollment(thenClockAction = true)
            return
        }
        attendanceViewModel.identityPromptShown()
        withDecryptionCipher(
            title = "Verify attendance",
            subtitle = "Use your fingerprint or screen lock",
            onCipher = { cipher ->
                val credential = authViewModel.unlockCredential(cipher)
                if (credential == null) {
                    attendanceViewModel.reportVerifyError(
                        "Fingerprint on this device needs to be enabled again.", VerifyFix.EnableFingerprint, "Fingerprint Reset",
                    )
                } else {
                    attendanceViewModel.submitWithCredential(DeviceCredentialProof(credential.credentialId, credential.deviceSecret))
                }
            },
            onError = { message, cancelled ->
                if (message == null && !cancelled) return@withDecryptionCipher
                attendanceViewModel.reportVerifyError(
                    if (cancelled) "Verification cancelled. Tap the fingerprint to try again." else message ?: "Verification failed.",
                    if (message?.contains("enable it again", ignoreCase = true) == true) VerifyFix.EnableFingerprint else VerifyFix.Retry,
                    if (cancelled) "Cancelled" else "Verification Failed",
                )
            },
        )
    }

    /**
     * Prompt, then hand back a cipher able to decrypt the stored credential.
     * A missing or invalidated credential (e.g. a new fingerprint was added)
     * is reported so the caller can offer re-enrollment.
     */
    private fun withDecryptionCipher(
        title: String,
        subtitle: String,
        onCipher: (Cipher) -> Unit,
        onError: (String?, Boolean) -> Unit,
    ) {
        val invalid = "Fingerprint is not enabled on this device (a new fingerprint may have been added). Please enable it again."
        if (authViewModel.biometricUsesCryptoObject()) {
            val cipher = runCatching { authViewModel.decryptionCipher() }.getOrNull()
            if (cipher == null) {
                authViewModel.syncBiometricEnrollment()
                onError(invalid, false)
                return
            }
            authenticateForCipher(title, subtitle, cipher, onCipher, onError)
        } else {
            // API 26–29: the key is time-bound, so authenticate first, then init the cipher.
            authenticate(title, subtitle, onSuccess = {
                val cipher = runCatching { authViewModel.decryptionCipher() }.getOrNull()
                if (cipher == null) {
                    authViewModel.syncBiometricEnrollment()
                    onError(invalid, false)
                } else {
                    onCipher(cipher)
                }
            }, onError = onError)
        }
    }

    /**
     * Prompt for an encryption/decryption cipher. API 30+ binds the cipher to
     * the prompt (CryptoObject). API 26–29 uses the time-bound key: prompt
     * first, then rebuild the encryption cipher inside the validity window.
     */
    private fun authenticateForCipher(
        title: String,
        subtitle: String,
        cipher: Cipher?,
        onCipher: (Cipher) -> Unit,
        onError: (String?, Boolean) -> Unit,
    ) {
        if (cipher == null || !authViewModel.biometricUsesCryptoObject()) {
            // Encryption on API 26–29: the cipher above was created before auth;
            // rebuild it inside the validity window once the prompt succeeds.
            authenticate(title, subtitle, onSuccess = {
                runCatching(authViewModel::encryptionCipher).fold(
                    onSuccess = onCipher,
                    onFailure = { onError("Could not unlock secure storage. Try again.", false) },
                )
            }, onError = onError)
            return
        }
        showPrompt(title, subtitle, BiometricPrompt.CryptoObject(cipher), onSuccess = { result ->
            result.cryptoObject?.cipher?.let(onCipher)
                ?: onError("Authentication did not unlock the credential. Try again.", false)
        }, onError = onError)
    }

    /** Prompt with no CryptoObject (API 26–29 time-bound key path). */
    private fun authenticate(title: String, subtitle: String, onSuccess: () -> Unit, onError: (String?, Boolean) -> Unit) {
        showPrompt(title, subtitle, null, onSuccess = { onSuccess() }, onError = onError)
    }

    private fun showPrompt(
        title: String,
        subtitle: String,
        crypto: BiometricPrompt.CryptoObject?,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (String?, Boolean) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess(result)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    onError(if (cancelled) null else errString.toString(), cancelled)
                }
            },
        )
        // DEVICE_CREDENTIAL in the authenticator set supplies the "Use PIN"
        // button itself, so no negative button text may be set.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(credentialAuthenticators)
            .setConfirmationRequired(false)
            .build()
        runCatching {
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        }.onFailure { onError(it.message ?: "Could not start fingerprint verification.", false) }
    }

    private fun deviceLabel(): String =
        listOfNotNull(android.os.Build.MANUFACTURER?.replaceFirstChar(Char::titlecase), android.os.Build.MODEL)
            .joinToString(" ").ifBlank { "Android device" }.take(100)

    // ── One-tap fixes from the verify sheet ──

    private fun openAppSettings() {
        runCatching {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun openLocationSettings() {
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    /** Google's in-app "Turn on location" dialog; Settings when Play services can't resolve it. */
    private fun enableLocation() {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build())
            .setAlwaysShow(true)
            .build()
        val check = runCatching { LocationServices.getSettingsClient(this).checkLocationSettings(request) }
            .getOrElse { openLocationSettings(); return }
        check.addOnSuccessListener(this) { resumeAfterLocationEnabled(locationEnabled = true) }
        check.addOnFailureListener(this) { error ->
            val resolvable = (error as? ResolvableApiException)
                ?.takeIf { it.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED }
            val launched = resolvable != null && runCatching {
                locationResolution.launch(IntentSenderRequest.Builder(resolvable.resolution).build())
            }.isSuccess
            if (!launched) openLocationSettings()
        }
    }

    private fun resumeAfterLocationEnabled(
        locationEnabled: Boolean = LocationManagerCompat.isLocationEnabled(getSystemService(android.location.LocationManager::class.java)),
    ) = attendanceViewModel.onLocationSettingsChanged(onPermissionRequired = { requestLocationPermission() }, locationEnabled = locationEnabled)

    private fun locationProviderChanges() = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            this@MainActivity,
            receiver,
            android.content.IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { runCatching { unregisterReceiver(receiver) } }
    }

    /** Fingerprint / screen-lock enrollment (Android 11+ has a direct enroll screen). */
    private fun openSecuritySettings() {
        val enroll = if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, credentialAuthenticators)
        } else {
            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { startActivity(enroll) }
            .onFailure { runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } }
    }
}
