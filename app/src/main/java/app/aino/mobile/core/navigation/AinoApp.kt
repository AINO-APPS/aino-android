package app.aino.mobile.core.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.aino.mobile.R
import app.aino.mobile.core.designsystem.theme.AinoDanger
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.aino.mobile.core.auth.AuthState
import app.aino.mobile.core.auth.AuthViewModel
import app.aino.mobile.feature.auth.ChangePasswordScreen
import app.aino.mobile.feature.auth.LoginScreen
import app.aino.mobile.feature.auth.RealmChoiceScreen
import app.aino.mobile.feature.home.HomeScreen
import app.aino.mobile.feature.home.DashboardViewModel
import app.aino.mobile.feature.attendance.AttendanceScreen
import app.aino.mobile.feature.attendance.AttendanceTab
import app.aino.mobile.feature.attendance.AttendanceViewModel
import app.aino.mobile.feature.attendance.verify.ClockInVerifySheet
import app.aino.mobile.feature.attendance.verify.VerifyActions
import app.aino.mobile.feature.tasks.TasksScreen
import app.aino.mobile.feature.tasks.TaskViewModel
import app.aino.mobile.feature.profile.ProfileScreen
import app.aino.mobile.feature.profile.ProfileViewModel
import app.aino.mobile.feature.chat.ChatScreen
import app.aino.mobile.feature.chat.ChatViewModel
import app.aino.mobile.core.update.AppUpdater
import app.aino.mobile.core.realtime.RealtimeState
import app.aino.mobile.core.realtime.RealtimeDomain
import app.aino.mobile.core.realtime.RealtimeViewModel
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.designsystem.AinoNavigationBar
import app.aino.mobile.core.designsystem.AinoNavigationItem
import app.aino.mobile.core.designsystem.AinoScaffold
import app.aino.mobile.core.designsystem.component.WebTabBar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.profile.EditProfileScreen
import app.aino.mobile.feature.profile.FaceEnrollmentScreen
import app.aino.mobile.feature.profile.NotificationSoundsScreen
import androidx.compose.foundation.layout.offset
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.OutboxWorker
import app.aino.mobile.core.db.shouldWakeOutboxOnReconnect
import app.aino.mobile.core.push.PushTokenRegistrar
import app.aino.mobile.feature.notifications.unreadTruthWindow
import app.aino.mobile.core.call.IncomingCallViewModel
import app.aino.mobile.core.call.CallRealtimeEvent
import app.aino.mobile.core.call.CallRealtimeRouter
import app.aino.mobile.core.call.CallSessionRuntime
import app.aino.mobile.core.call.toRoute
import app.aino.mobile.core.call.IncomingCallScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.aino.mobile.core.designsystem.icons.HeroIcons

@Composable
fun AinoApp(
    auth: AuthViewModel,
    updates: AppUpdater,
    realtime: RealtimeViewModel,
    dashboard: DashboardViewModel,
    attendance: AttendanceViewModel,
    tasks: TaskViewModel,
    profile: ProfileViewModel,
    chat: ChatViewModel,
    incomingCall: IncomingCallViewModel,
    isDark: Boolean,
    onSetDark: (Boolean) -> Unit,
    biometricAvailable: Boolean,
    onBiometricLogin: () -> Unit,
    onBiometricEnroll: () -> Unit,
    onAttendanceLocationPermission: () -> Unit,
    onAttendanceBiometric: () -> Unit,
    attendanceSystemActions: AttendanceSystemActions,
    onPickChatDocument: () -> Unit,
    onAuthenticatedForPush: () -> Unit,
) {
    val ui by auth.ui.collectAsStateWithLifecycle()
    val incomingCallUi by incomingCall.ui.collectAsStateWithLifecycle()
    val realtimeState by realtime.state.collectAsStateWithLifecycle()
    val tenantAuthenticated = (ui.state as? AuthState.Authenticated)?.user?.tenantId != null
    LaunchedEffect(tenantAuthenticated) { realtime.setAuthenticatedTenant(tenantAuthenticated) }
    // Foreground: skip any pending backoff and replace a socket that went stale while asleep.
    androidx.lifecycle.compose.LifecycleResumeEffect(realtime, tenantAuthenticated) {
        // A socket stopped by a 4001 we could not verify (offline at the time) gets one more try.
        if (tenantAuthenticated && realtime.state.value is RealtimeState.Stopped) realtime.setAuthenticatedTenant(true)
        else realtime.reconnectNow()
        onPauseOrDispose { }
    }
    // A terminal socket close is the server ending this session (sign-out elsewhere, password change, removal).
    LaunchedEffect(realtimeState) {
        val stopped = realtimeState as? RealtimeState.Stopped ?: return@LaunchedEffect
        if (!tenantAuthenticated) return@LaunchedEffect
        // 4001 with a live session = the socket's token aged out; the API check refreshed it.
        if (auth.verifySessionStillActive() && stopped.code == 4001) {
            kotlinx.coroutines.delay(5_000)
            realtime.setAuthenticatedTenant(true)
        }
    }
    val authenticatedUser = (ui.state as? AuthState.Authenticated)?.user
    val appContext = LocalContext.current.applicationContext
    val callSession = CallSessionRuntime.get(appContext)
    val activeCall = androidx.compose.runtime.remember { app.aino.mobile.core.call.ActiveCallRuntime.get(appContext) }
    val meetingSession = androidx.compose.runtime.remember { app.aino.mobile.feature.meeting.MeetingRuntime.get(appContext) }
    LaunchedEffect(chat, realtime) {
        chat.setRealtimeSender(realtime::send)
        activeCall.send = realtime::send
        meetingSession.send = realtime::send
        incomingCall.realtimeSend = realtime::send
        app.aino.mobile.core.call.CallRealtimeLink.send = realtime::send
    }
    // Calls: an open socket carries ring acks; a (re)connect or a return to the
    // foreground asks the server whether a call still shown here already ended.
    val callScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(realtimeState, tenantAuthenticated) {
        val connected = realtimeState == RealtimeState.Connected
        app.aino.mobile.core.call.CallRealtimeLink.setConnected(connected)
        if (connected && tenantAuthenticated) app.aino.mobile.core.call.CallReconciler.reconcile(appContext)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { app.aino.mobile.core.call.CallRealtimeLink.setConnected(false) }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(tenantAuthenticated) {
        if (tenantAuthenticated) callScope.launch { app.aino.mobile.core.call.CallReconciler.reconcile(appContext) }
        onPauseOrDispose { }
    }
    // A new socket rebuilds the meeting mesh and replays its chat (web WS `open`).
    LaunchedEffect(realtimeState) { meetingSession.onRealtimeState(realtimeState == RealtimeState.Connected) }
    LaunchedEffect(tenantAuthenticated) { if (!tenantAuthenticated) meetingSession.leave() }
    // P7.4: the bell's notifications live for the session (web NotificationBell polls every 30s).
    val notifications = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.notifications.NotificationsViewModel>(
        factory = app.aino.mobile.feature.notifications.NotificationsViewModel.factory(appContext),
    )
    LaunchedEffect(tenantAuthenticated) { if (!tenantAuthenticated) notifications.reset() }
    // The dashboard + attendance ViewModels outlive sign-in (MainActivity
    // scope). Load them only once a tenant token exists — a token-less
    // `tracker/status` is rejected with HTTP 400 by the server's requireTenant
    // and that stale error used to surface on the home screen after login.
    LaunchedEffect(tenantAuthenticated, authenticatedUser?.id) {
        if (tenantAuthenticated && authenticatedUser != null) {
            dashboard.refresh()
            attendance.refresh()
        } else {
            dashboard.reset()
            attendance.reset()
        }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(tenantAuthenticated) {
        if (tenantAuthenticated) {
            notifications.start()
            // Reads made on the web / desktop / another phone send this device
            // no event; the fresh list lets the tray (and launcher badge) catch up.
            chat.refresh()
        }
        onPauseOrDispose { notifications.stop() }
    }
    // Keep the tray in step with the server's unread state. Samsung One UI
    // badges the launcher icon with the tray's AINO notifications, so leftovers
    // for messages / alerts already read elsewhere showed as a false count.
    val trayChatUi by chat.ui.collectAsStateWithLifecycle()
    val trayBellUi by notifications.ui.collectAsStateWithLifecycle()
    val unreadChatIds = trayChatUi.conversations.filter { it.unreadCount > 0 }.map { it.id }.toSet()
    val (unreadAlertIds, alertWindowMinId) = trayBellUi.unreadTruthWindow()
    LaunchedEffect(tenantAuthenticated, unreadChatIds, trayChatUi.syncedAtMs, unreadAlertIds, alertWindowMinId, trayBellUi.syncedAtMs) {
        if (!tenantAuthenticated) return@LaunchedEffect
        val truth = app.aino.mobile.core.push.UnreadTruth(
            unreadConversationIds = unreadChatIds,
            chatSyncedAtMs = trayChatUi.syncedAtMs,
            unreadAlertIds = unreadAlertIds,
            alertWindowMinId = alertWindowMinId,
            alertsSyncedAtMs = trayBellUi.syncedAtMs,
        )
        withContext(Dispatchers.Default) { app.aino.mobile.core.push.NotificationReconciler.reconcile(appContext, truth) }
    }
    // P7.2: one notebook per user for the session; unsaved edits flush when the app stops.
    val notes = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.notes.NotesViewModel>(
        factory = app.aino.mobile.feature.notes.NotesViewModel.factory(appContext),
    )
    LaunchedEffect(tenantAuthenticated) { if (!tenantAuthenticated) notes.reset() }
    // P10.4 web BrandingContext: org branding per signed-in user, public branding when signed out.
    val brandingStore = androidx.compose.runtime.remember { app.aino.mobile.core.AppContainer.get(appContext).branding }
    val branding by brandingStore.state.collectAsStateWithLifecycle()
    LaunchedEffect(tenantAuthenticated, authenticatedUser?.id) {
        if (tenantAuthenticated && authenticatedUser != null) brandingStore.refresh() else brandingStore.refreshPublic()
    }
    androidx.lifecycle.compose.LifecycleStartEffect(notes) { onStopOrDispose { notes.flush() } }
    val setDark by androidx.compose.runtime.rememberUpdatedState(onSetDark)
    // Web StatusProvider / ThemeProvider / NotificationPrefsProvider start on tenant sign-in.
    LaunchedEffect(tenantAuthenticated, authenticatedUser?.id) {
        val id = authenticatedUser?.id
        if (tenantAuthenticated && id != null) profile.start(id) { dark -> setDark(dark) } else profile.stop()
    }
    LaunchedEffect(authenticatedUser?.tenantFeatures?.get("calls")) {
        chat.setCallsEnabled(authenticatedUser?.tenantFeatures?.get("calls") == true)
    }
    LaunchedEffect(authenticatedUser?.tenantId, authenticatedUser?.id) {
        chat.setScope(authenticatedUser?.tenantId, authenticatedUser?.id)
        if (authenticatedUser?.tenantId != null) {
            onAuthenticatedForPush()
            withContext(Dispatchers.IO) {
                PushTokenRegistrar(appContext).syncCurrentToken()
            }
        }
    }
    LaunchedEffect(realtimeState, authenticatedUser?.tenantId, authenticatedUser?.id) {
        val tenantId = authenticatedUser?.tenantId
        val userId = authenticatedUser?.id
        val reconnectScope = if (tenantId != null && userId != null) CacheScope(tenantId, userId) else null
        if (shouldWakeOutboxOnReconnect(realtimeState == RealtimeState.Connected, reconnectScope)) {
            // A reconnect is the earliest reliable signal that network access
            // returned. Wake the exact scoped durable outbox immediately rather
            // than waiting for WorkManager's next backoff window.
            OutboxWorker.enqueue(appContext, reconnectScope!!)
        }
    }
    LaunchedEffect(tenantAuthenticated) {
        if (tenantAuthenticated) {
            realtime.events.collect { event ->
                when (val callEvent = CallRealtimeRouter.decode(event)) {
                    is CallRealtimeEvent.Incoming -> if (incomingCall.route(callEvent.toRoute())) {
                        app.aino.mobile.core.call.CallRingService.start(
                            appContext,
                            app.aino.mobile.core.call.socketCallRingExtras(callEvent, app.aino.mobile.core.AppContainer.get(appContext).tokens.getToken()),
                        )
                        // Tell the caller this phone is ringing (once per call, socket or push).
                        app.aino.mobile.core.call.CallRingingAck.acknowledge(appContext, callEvent.callId, callEvent.conversationId, callEvent.meetingCode)
                    }
                    null -> Unit
                    else -> {
                        activeCall.onEvent(callEvent)
                        // Call history (Calls tab / conversation info) gains a row when a call ends.
                        if (callEvent is CallRealtimeEvent.Ended || callEvent is CallRealtimeEvent.Rejected) chat.onCallActivity()
                    }
                }
                meetingSession.onRealtimeEvent(event)
                if (event.type in DASHBOARD_REFRESH_EVENTS) dashboard.refresh()
                if (event.type in TASK_REFRESH_EVENTS) tasks.onTaskEvent(event.type, event.data)
                when (event.type) {
                    // Clock/break taken on the web, desktop or another phone, or a
                    // manual entry filed/deleted: timer, status, calendar and loaded tabs.
                    "attendance_update" -> { dashboard.refresh(); attendance.onRemoteAttendanceChange() }
                    // A manual-entry / overtime / leave request was decided (or acted on elsewhere).
                    "approval_update" -> attendance.onRemoteApprovalChange()
                    // HR edited holidays / leave policies / balances.
                    "leave_policy_changed" -> {
                        dashboard.refresh()
                        attendance.onRemoteLeavePolicyChange(leavePolicyScope(event.data))
                    }
                    "user_status" -> profile.onStatusEvent(event.data)
                    // Multi-device theme sync (web ThemeContext).
                    "theme_changed" -> themeFromEvent(event.data)?.let { dark -> setDark(dark) }
                    // Live accent / logo sync (web BrandingContext).
                    "branding_changed" -> brandingStore.refresh()
                    // Web FeaturesContext: patch the effective feature map so gates react immediately.
                    "tenant_features_changed" -> featuresFromEvent(event.data)?.let { features ->
                        auth.updateUser { it.copy(tenantFeatures = features) }
                    }
                    // A leave applied / approved / rejected elsewhere.
                    "leave_update" -> attendance.onRemoteLeaveChange()
                    // Names / avatars shown in the conversation list (web useChatState).
                    "user_profile_updated" -> chat.refresh()
                }
                notifications.onRealtimeEvent(event.type, event.data)
            }
        }
    }
    LaunchedEffect(tenantAuthenticated) {
        if (tenantAuthenticated) {
            realtime.domain(RealtimeDomain.Chat).collect(chat::onRealtimeEvent)
        }
    }
    // Catch-up after a reconnect (or a dropped event): the server keeps no
    // replay log, so every session-scoped screen revalidates silently.
    val resync by realtime.resync.collectAsStateWithLifecycle()
    LaunchedEffect(resync) {
        if (resync == 0L || !tenantAuthenticated) return@LaunchedEffect
        chat.refresh()
        chat.refreshThread()
        dashboard.refresh()
        attendance.refresh()
        tasks.onTaskEvent()
        notifications.refresh()
        profile.refresh()
    }
    // Call surfaces overlay the shell rather than replacing it, so the NavHost
    // (and a minimised meeting) survive a ring or a 1:1 call.
    Box(Modifier.fillMaxSize()) {
    when (val state = ui.state) {
        AuthState.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AuthState.SignedOut -> LoginScreen(
            ui.loading,
            ui.error,
            ui.message,
            biometricAvailable,
            ui.biometricEnrolled,
            auth::login,
            onBiometricLogin,
            orgName = branding.orgName,
            logoUrl = if (branding.logoUrl.isNullOrBlank()) null else brandingStore.publicLogoUrl(),
        )
        is AuthState.ChoosingRealm -> RealmChoiceScreen(
            state.realms,
            ui.loading,
            ui.error,
            auth::chooseRealm,
            auth::cancelRealmChoice,
        )
        is AuthState.PasswordChangeRequired -> ChangePasswordScreen(
            state.user.fullName ?: state.user.username,
            ui.loading,
            ui.error,
            auth::changePassword,
        )
        is AuthState.Authenticated -> AuthenticatedShell(
            auth,
            ui.message,
            ui.error,
            state.user.role,
            state.user.hasReports,
            state.user,
            state.featuresDegraded,
            updates,
            realtimeState,
            dashboard,
            attendance,
            tasks,
            profile,
            chat,
            biometricAvailable,
            ui.biometricEnrolled,
            onBiometricEnroll,
            onAttendanceLocationPermission,
            onAttendanceBiometric,
            attendanceSystemActions,
            onPickChatDocument,
            auth::retryFeatureHydration,
            auth::logout,
            isDark,
            onToggleTheme = {
                val next = !isDark
                onSetDark(next)
                profile.pushTheme(next)
            },
            realtimeEvents = realtime.events,
            resync = realtime.resync,
            notifications = notifications,
            notes = notes,
        )
    }
    app.aino.mobile.core.call.ActiveCallScreen(activeCall)
    if (incomingCallUi.route != null) {
        IncomingCallScreen(incomingCall) { incomingCall.clear() }
    } else if (tenantAuthenticated) {
        // Ask once for the settings that let calls ring visibly (call channel, full-screen intent).
        app.aino.mobile.core.call.CallAlertSetupPrompt()
    }
    }
}

@Composable
private fun AuthenticatedShell(
    auth: AuthViewModel,
    authMessage: String?,
    authError: String?,
    role: String,
    hasReports: Boolean,
    user: app.aino.mobile.core.auth.AinoUser,
    featuresDegraded: Boolean,
    updates: AppUpdater,
    realtimeState: RealtimeState,
    dashboard: DashboardViewModel,
    attendance: AttendanceViewModel,
    tasks: TaskViewModel,
    profile: ProfileViewModel,
    chat: ChatViewModel,
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    onBiometricEnroll: () -> Unit,
    onAttendanceLocationPermission: () -> Unit,
    onAttendanceBiometric: () -> Unit,
    attendanceSystemActions: AttendanceSystemActions,
    onPickChatDocument: () -> Unit,
    onRetryFeatures: () -> Unit,
    onSignOut: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    /** Raw WS events for per-route ViewModels (Calendar, …) to refetch on. */
    realtimeEvents: kotlinx.coroutines.flow.Flow<app.aino.mobile.core.realtime.RealtimeEnvelope>,
    /** Reconnect catch-up signal for per-route ViewModels. */
    resync: kotlinx.coroutines.flow.StateFlow<Long>,
    notifications: app.aino.mobile.feature.notifications.NotificationsViewModel,
    notes: app.aino.mobile.feature.notes.NotesViewModel,
) {
    val nav = rememberNavController()
    // Tasks read the signed-in context the web takes from AuthContext / FeaturesContext.
    LaunchedEffect(user.id, user.role, user.teamName, user.tenantFeatures) {
        val ungated = user.role == "platform_admin" && user.tenantId == null
        tasks.bind(
            user.id, user.role, user.teamName,
            agileEnabled = ungated || user.tenantFeatures["agile"] == true,
            customFieldsEnabled = ungated || user.tenantFeatures["custom_fields"] == true,
        )
    }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val isChatThread = current == AinoDestination.ChatThread.route
    val fullScreen = isFullScreenRoute(current)
    val currentBottomRoute = bottomBarRoute(current)
    val chatUi by chat.ui.collectAsStateWithLifecycle()
    val attendanceUi by attendance.ui.collectAsStateWithLifecycle()
    // A clock/break action changed the tracker state: the dashboard timer's live
    // durations come from its own snapshot, so reload it (web invalidates the same query).
    LaunchedEffect(attendanceUi.statusVersion) { if (attendanceUi.statusVersion > 0) dashboard.refresh() }
    val profileUi by profile.ui.collectAsStateWithLifecycle()
    val unreadNotifications by notifications.unreadCount.collectAsStateWithLifecycle()
    // Web WorkStateContext: the tracker state/mode drive the "Working" status label and the sign-out guard.
    val workState = attendanceUi.status?.state
    val workMode = attendanceUi.status?.workMode
    val statusVisual = app.aino.mobile.core.designsystem.component.profileStatusVisual(profileUi.status?.effective, workState, workMode)
    val tabs = visibleBottomDestinations(
        user.tenantFeatures,
        ungatedPlatformAdmin = user.role == "platform_admin" && user.tenantId == null,
    )
    val moreItems = availableMoreDestinations(role, hasReports, user.tenantFeatures, user.orgId) +
        if (app.aino.mobile.BuildConfig.DEBUG) listOf(AinoDestination.ApiProbe) else emptyList()
    var moreOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
    BackHandler(enabled = moreOpen) { moreOpen = false }
    val moreRoutes = moreItems.map { it.route }.toSet()
    val moreActive = currentBottomRoute != null && currentBottomRoute in moreRoutes
    fun navigate(destination: AinoDestination) {
        if (current == AinoDestination.ChatThread.route) chat.closeConversation()
        nav.navigate(destination.route) {
            launchSingleTop = true
            if (destination.inBottomBar) popUpTo(AinoDestination.Dashboard.route)
        }
    }
    /** Notification / search / note links carry web routes; unknown or ungated ones are ignored. */
    fun openWebLink(link: String) {
        val route = webLinkToRoute(link) ?: return
        runCatching { nav.navigate(route) { launchSingleTop = true } }
    }
    // A tapped system notification: chat messages open their thread; other
    // alerts follow the server link / linked task / type fallback (see
    // `pushTapRoute`) and are marked read. The outcome feeds the routing metrics.
    val pendingTap by app.aino.mobile.core.push.PendingPushTap.tap.collectAsStateWithLifecycle()
    val shellContext = LocalContext.current
    LaunchedEffect(pendingTap) {
        val tap = app.aino.mobile.core.push.PendingPushTap.consume() ?: return@LaunchedEffect
        val route = pushTapRoute(tap)
        // Publish the thread before the transition: it opens from stored rows, never waiting on the list.
        if (tap.conversationId != null && route == chatThreadRoute(tap.conversationId)) {
            chat.openConversationById(
                tap.conversationId,
                app.aino.mobile.feature.chat.ConversationHint(tap.title, tap.avatar, tap.isGroup, tap.unreadCount),
            )
        }
        val routed = runCatching { nav.navigate(route) { launchSingleTop = true } }.isSuccess ||
            // A target this build/user cannot open still lands on the notifications list.
            (route != AinoDestination.Notifications.route &&
                runCatching { nav.navigate(AinoDestination.Notifications.route) { launchSingleTop = true } }.isSuccess)
        tap.notificationId?.let(notifications::markReadById)
        val events = app.aino.mobile.core.push.routingEvents(tap, routed, System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            app.aino.mobile.core.push.reportNotificationMetrics(app.aino.mobile.core.AppContainer.get(shellContext).api, events)
        }
    }
    // Web refetches on window focus/visibility; mirror that on app resume for
    // the visible route (the first resume is the initial load, already done).
    var resumedOnce by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (resumedOnce) {
            chat.refresh()
            when (nav.currentDestination?.route) {
                AinoDestination.Dashboard.route -> dashboard.refresh()
                AinoDestination.ChatThread.route -> chat.refreshThread()
                AinoDestination.Tasks.route -> tasks.refresh()
                TASK_DETAIL_ROUTE -> tasks.refreshDetail()
                AinoDestination.Profile.route -> profile.refresh()
                else -> if (nav.currentDestination?.route?.startsWith(AinoDestination.Attendance.route) == true) attendance.refresh()
            }
        }
        resumedOnce = true
        onPauseOrDispose { }
    }
    // Signal-style in-place search: the top bar becomes the field and results cover the page.
    val shellSearch = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.search.SearchViewModel>(
        key = "shell-search",
        factory = app.aino.mobile.feature.search.SearchViewModel.factory(shellContext, role),
    )
    LaunchedEffect(role) { shellSearch.setRole(role) }
    var searchActive by androidx.compose.runtime.remember { mutableStateOf(false) }
    fun closeSearch() {
        searchActive = false
        shellSearch.clear()
    }
    LaunchedEffect(current) { if (searchActive) closeSearch() }
    Box(Modifier.fillMaxSize()) {
    AinoScaffold(
        topBar = {
            if (!fullScreen) {
                AinoShellTopBar(
                    user = user,
                    statusVisual = statusVisual,
                    unreadNotifications = unreadNotifications,
                    onSearch = { moreOpen = false; searchActive = true },
                    searchActive = searchActive,
                    search = shellSearch,
                    onCloseSearch = ::closeSearch,
                    onNotifications = { moreOpen = false; navigate(AinoDestination.Notifications) },
                    onProfile = { moreOpen = false; navigate(AinoDestination.Profile) },
                )
            }
        },
        bottomBar = {
            if (!fullScreen) {
                // The More popup is drawn in the overlay layer below, never inside
                // this slot: anything placed here changes the measured bar height.
                WebTabBar(
                    destinations = tabs,
                    currentRoute = currentBottomRoute,
                    chatUnread = chatUi.unread,
                    moreOpen = moreOpen,
                    moreActive = moreActive,
                    onSelect = { destination -> moreOpen = false; navigate(destination) },
                    onToggleMore = { moreOpen = !moreOpen },
                )
            }
        },
    ) { padding ->
        // P0.2: never render a silently truncated tab bar. When feature gates
        // could not be hydrated (or are unknown for a non-platform-admin),
        // surface a dismissible warning with a Retry action.
        var bannerDismissed by androidx.compose.runtime.saveable.rememberSaveable(user.id) { mutableStateOf(false) }
        val showGateWarning = !bannerDismissed &&
            (featuresDegraded || (user.tenantFeatures.isEmpty() && role != "platform_admin"))
        val contentModifier = if (fullScreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().padding(padding)
        }
        Column(contentModifier) {
            if (showGateWarning) {
                FeatureGateWarningBanner(
                    onRetry = {
                        bannerDismissed = false
                        onRetryFeatures()
                    },
                    onDismiss = { bannerDismissed = true },
                )
            }
            val updateUi by updates.ui.collectAsStateWithLifecycle()
            val activity = androidx.activity.compose.LocalActivity.current
            if (!fullScreen) {
                app.aino.mobile.core.update.UpdateBanner(
                    state = updateUi,
                    onInstall = { activity?.let(updates::install) },
                    onDismiss = updates::dismiss,
                )
            }
        NavHost(nav, startDestination = AinoDestination.Dashboard.route, modifier = Modifier.fillMaxSize()) {
            composable(AinoDestination.Dashboard.route) {
                HomeScreen(
                    user,
                    dashboard,
                    onCalendar = { navigate(AinoDestination.Calendar) },
                    onTasks = { navigate(AinoDestination.Tasks) },
                )
            }
            composable(AinoDestination.Tasks.route) {
                ForegroundPoll(TASKS_POLL_MS, tasks::pollVisible)
                TasksScreen(
                    viewModel = tasks,
                    onOpenDetail = { nav.navigate(TASK_DETAIL_ROUTE) { launchSingleTop = true } },
                    onOpenInsights = { nav.navigate(SPRINT_INSIGHTS_ROUTE) { launchSingleTop = true } },
                    serviceDesk = { app.aino.mobile.feature.tasks.ServiceDeskTab(tasks, user.role) },
                )
            }
            composable(TASK_DETAIL_ROUTE) {
                val closeDetail = {
                    tasks.closeDetail()
                    Unit
                }
                BackHandler(onBack = closeDetail)
                app.aino.mobile.feature.tasks.TaskDetailScreen(tasks, onClose = { nav.popBackStack() })
            }
            composable(
                TASK_LINK_ROUTE,
                arguments = listOf("task", "tab", "sprint_id").map { name -> navArgument(name) { defaultValue = "" } },
            ) { entry ->
                val taskId = entry.arguments?.getString("task")?.toLongOrNull()
                val tab = entry.arguments?.getString("tab")?.ifEmpty { null }
                val sprintId = entry.arguments?.getString("sprint_id")?.toLongOrNull()
                LaunchedEffect(Unit) {
                    tasks.applyLink(tab, sprintId)
                    nav.navigate(AinoDestination.Tasks.route) {
                        launchSingleTop = true
                        popUpTo(TASK_LINK_ROUTE) { inclusive = true }
                    }
                    if (taskId != null) {
                        tasks.openDetailById(taskId)
                        nav.navigate(TASK_DETAIL_ROUTE) { launchSingleTop = true }
                    }
                }
            }
            composable(SPRINT_INSIGHTS_ROUTE) {
                app.aino.mobile.feature.tasks.SprintInsightsScreen(
                    tasks,
                    onBack = { nav.popBackStack() },
                    onOpenTask = { id ->
                        tasks.openDetailById(id)
                        nav.navigate(TASK_DETAIL_ROUTE) { launchSingleTop = true }
                    },
                )
            }
            composable(
                AinoDestination.Chat.route,
                // Chat list side of the Signal transition (the thread supplies the slide).
                exitTransition = {
                    if (targetState.destination.route == AinoDestination.ChatThread.route) signalFadeScaleOut()
                    else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
                },
                popEnterTransition = {
                    if (initialState.destination.route == AinoDestination.ChatThread.route) signalFadeScaleIn()
                    else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200))
                },
            ) {
                ChatScreen(
                    viewModel = chat,
                    onPickDocument = onPickChatDocument,
                    conversationId = null,
                    meetingsEnabled = user.tenantFeatures["meetings"] == true,
                    onOpenConversation = { conversationId ->
                        chat.prepareConversation(conversationId)
                        nav.navigate(chatThreadRoute(conversationId)) { launchSingleTop = true }
                    },
                    onNavigateBack = { nav.popBackStack() },
                )
            }
            composable(
                route = AinoDestination.ChatThread.route,
                arguments = listOf(navArgument(CHAT_CONVERSATION_ARGUMENT) { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = CHAT_DEEP_LINK_PATTERN }),
                // Signal conversation transition: the thread slides in from the end over
                // the shrinking, dimming list; back (and predictive back) reverses it.
                enterTransition = { signalSlideFromEnd() },
                exitTransition = { signalFadeScaleOut() },
                popEnterTransition = { signalFadeScaleIn() },
                popExitTransition = { signalSlideToEnd() },
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getLong(CHAT_CONVERSATION_ARGUMENT)
                    ?: return@composable
                // No BackHandler here: the NavHost owns back so the predictive gesture can
                // drive the transition. The thread closes once its entry is really popped.
                val navigateBack = {
                    nav.popBackStack()
                    Unit
                }
                androidx.compose.runtime.DisposableEffect(backStackEntry) {
                    onDispose {
                        // Disposal waits for the exit animation (and also happens when a screen is
                        // pushed on top), so check the back stack: a reopen of the same chat meanwhile
                        // pushes a new entry for it, which must keep the thread open.
                        // No public API exposes the back stack synchronously; this read is stable.
                        @android.annotation.SuppressLint("RestrictedApi")
                        val stillOpen = nav.currentBackStack.value.any {
                            it.destination.route == AinoDestination.ChatThread.route &&
                                it.arguments?.getLong(CHAT_CONVERSATION_ARGUMENT) == conversationId
                        }
                        if (!stillOpen) chat.closeConversation(conversationId)
                    }
                }
                ChatScreen(
                    viewModel = chat,
                    onPickDocument = onPickChatDocument,
                    conversationId = conversationId,
                    meetingsEnabled = user.tenantFeatures["meetings"] == true,
                    onOpenConversation = { nextConversationId ->
                        chat.prepareConversation(nextConversationId)
                        nav.navigate(chatThreadRoute(nextConversationId)) { launchSingleTop = true }
                    },
                    onNavigateBack = navigateBack,
                )
            }
            // P7.1: the web Calendar page (day / week / month + event form).
            composable(AinoDestination.Calendar.route) {
                val context = LocalContext.current
                val calendar = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.calendar.CalendarViewModel>(
                    factory = app.aino.mobile.feature.calendar.CalendarViewModel.factory(context),
                )
                LaunchedEffect(calendar) {
                    realtimeEvents.collect { event -> if (event.type in CALENDAR_REFRESH_EVENTS) calendar.refresh() }
                }
                RefetchOnResume(calendar::refresh, resync)
                app.aino.mobile.feature.calendar.CalendarScreen(
                    viewModel = calendar,
                    userId = user.id,
                    onOpenEditor = { nav.navigate(CALENDAR_EVENT_ROUTE) { launchSingleTop = true } },
                )
            }
            // New / Edit Event: full screen, sharing the Calendar entry's ViewModel.
            composable(CALENDAR_EVENT_ROUTE) { entry ->
                val context = LocalContext.current
                val parent = androidx.compose.runtime.remember(entry) { nav.getBackStackEntry(AinoDestination.Calendar.route) }
                val calendar = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.calendar.CalendarViewModel>(
                    viewModelStoreOwner = parent,
                    factory = app.aino.mobile.feature.calendar.CalendarViewModel.factory(context),
                )
                val calendarUi by calendar.ui.collectAsStateWithLifecycle()
                val editor = calendarUi.editor
                // Save, delete and close all end the draft; the form then leaves.
                LaunchedEffect(editor == null) { if (editor == null) nav.popBackStack() }
                if (editor != null) {
                    app.aino.mobile.feature.calendar.EventFormScreen(
                        editor = editor,
                        tasks = calendarUi.tasks,
                        meetingsEnabled = user.tenantFeatures["meetings"] == true,
                        isOrganizer = calendar.isOrganizer(editor),
                        viewModel = calendar,
                        onJoinMeeting = { code ->
                            nav.popBackStack()
                            calendar.closeEditor()
                            nav.navigate(meetingRoute(code)) { launchSingleTop = true }
                        },
                    )
                }
            }
            // P9: web MeetingJoin lobby → MeetingRoom; HuddleAutoJoin for group calls.
            composable(MEETING_ROUTE) { backStackEntry ->
                val code = backStackEntry.arguments?.getString("code").orEmpty()
                app.aino.mobile.feature.meeting.MeetingJoinScreen(
                    code = code,
                    user = user,
                    onBack = { nav.popBackStack() },
                    onJoined = { joined ->
                        nav.navigate(meetingRoomRoute(joined)) {
                            launchSingleTop = true
                            popUpTo(MEETING_ROUTE) { inclusive = true }
                        }
                    },
                )
            }
            composable(MEETING_ROOM_ROUTE) { backStackEntry ->
                val code = backStackEntry.arguments?.getString("code").orEmpty()
                app.aino.mobile.feature.meeting.MeetingRoomScreen(
                    code = code,
                    user = user,
                    online = realtimeState == RealtimeState.Connected,
                    // Web: minimise navigates to `/`; the meeting keeps running in the floating widget.
                    onMinimize = { nav.navigate(AinoDestination.Dashboard.route) { launchSingleTop = true; popUpTo(AinoDestination.Dashboard.route) } },
                    onLeft = { nav.navigate(AinoDestination.Dashboard.route) { launchSingleTop = true; popUpTo(AinoDestination.Dashboard.route) } },
                )
            }
            composable(HUDDLE_ROUTE) { backStackEntry ->
                val code = backStackEntry.arguments?.getString("code").orEmpty()
                app.aino.mobile.feature.meeting.HuddleAutoJoinScreen(
                    code = code,
                    user = user,
                    // Replace, not push: Back must leave the call cleanly.
                    onJoined = { joined ->
                        nav.navigate(meetingRoomRoute(joined)) {
                            launchSingleTop = true
                            popUpTo(HUDDLE_ROUTE) { inclusive = true }
                        }
                    },
                    onBackToChat = { nav.navigate(AinoDestination.Chat.route) { launchSingleTop = true; popUpTo(AinoDestination.Dashboard.route) } },
                )
            }
            // Profile is a full page (product decision 2026-09-25) with sub-pages
            // for the web's Edit Profile / Notification Sounds modals and /profile/face.
            composable(AinoDestination.Profile.route) {
                ProfileScreen(
                    viewModel = profile,
                    authUser = user,
                    workState = workState,
                    workMode = workMode,
                    isDark = isDark,
                    onBack = { nav.popBackStack() },
                    onEditProfile = { nav.navigate(PROFILE_EDIT_ROUTE) { launchSingleTop = true } },
                    onNotificationSounds = { nav.navigate(PROFILE_SOUNDS_ROUTE) { launchSingleTop = true } },
                    onFaceEnrollment = { nav.navigate(PROFILE_FACE_ROUTE) { launchSingleTop = true } },
                    onToggleTheme = onToggleTheme,
                    onAvatarChanged = { avatar -> auth.updateUser { it.copy(avatar = avatar) } },
                    onSignOut = onSignOut,
                )
            }
            composable(PROFILE_EDIT_ROUTE) {
                EditProfileScreen(
                    viewModel = profile,
                    // Web parity: the org's "Allow biometric login" hides new enrollment for sign-in.
                    biometricLoginAllowed = attendanceUi.policy?.biometricLoginEnabled != false,
                    attendanceVerificationOn = attendanceUi.policy?.verificationEnabled == true,
                    biometricAvailable = biometricAvailable,
                    biometricEnrolled = biometricEnrolled,
                    biometricMessage = authMessage,
                    biometricError = authError,
                    thisDeviceCredentialId = auth.biometricCredentialId(),
                    onEnableBiometric = onBiometricEnroll,
                    onThisDeviceRevoked = auth::disableBiometric,
                    onBack = { nav.popBackStack() },
                    onProfileChanged = { updated -> auth.updateUser { it.copy(fullName = updated.fullName, username = updated.username) } },
                    onEmailChanged = { email -> auth.updateUser { it.copy(email = email) } },
                    onAccountDeleted = onSignOut,
                )
            }
            composable(PROFILE_SOUNDS_ROUTE) {
                NotificationSoundsScreen(profile, onBack = { nav.popBackStack() })
            }
            composable(PROFILE_FACE_ROUTE) {
                FaceEnrollmentScreen(profile, onBack = { nav.popBackStack() })
            }
            // P3.1/P3.8: the Attendance page hosts its tabs; `?tab=` carries the
            // web hash deep links (#leaves, #manual-entry, #analytics).
            composable(
                route = ATTENDANCE_ROUTE_PATTERN,
                arguments = listOf(androidx.navigation.navArgument("tab") { defaultValue = "" }),
            ) { backStackEntry ->
                AttendanceScreen(
                    viewModel = attendance,
                    userRole = role,
                    initialTab = AttendanceTab.fromHash(backStackEntry.arguments?.getString("tab")),
                    // The only clock entry point: permission prompt + global verify sheet.
                    onLocationPermission = onAttendanceLocationPermission,
                )
            }
            // The web redirects /leaves to /attendance#leaves.
            composable(AinoDestination.Leaves.route) {
                LaunchedEffect(Unit) {
                    nav.navigate(AinoDestination.Attendance.route + "?tab=leaves") {
                        launchSingleTop = true
                        popUpTo(AinoDestination.Leaves.route) { inclusive = true }
                    }
                }
            }
            // Other web redirects (P3.8): /manual-entry, /analytics, /leave-policy.
            listOf("manual-entry" to "manual-entry", "analytics" to "analytics", "leave-policy" to "leaves")
                .forEach { (legacy, tab) ->
                    composable(legacy) {
                        LaunchedEffect(Unit) {
                            nav.navigate(AinoDestination.Attendance.route + "?tab=$tab") {
                                launchSingleTop = true
                                popUpTo(legacy) { inclusive = true }
                            }
                        }
                    }
                }
            // P7.2/P7.3: Notes home inside the shell; editor and history are full screen.
            composable(AinoDestination.Notes.route) {
                app.aino.mobile.feature.notes.NotesHomeScreen(
                    viewModel = notes,
                    userId = user.id,
                    userRole = role,
                    hasReports = hasReports,
                    onOpenPage = { pageId -> nav.navigate(noteEditorRoute(pageId)) { launchSingleTop = true } },
                )
            }
            composable(NOTE_EDITOR_ROUTE, arguments = listOf(navArgument("pageId") { type = NavType.StringType })) { entry ->
                val pageId = entry.arguments?.getString("pageId") ?: return@composable
                app.aino.mobile.feature.notes.NoteEditorScreen(
                    viewModel = notes,
                    pageId = pageId,
                    userId = user.id,
                    onBack = { notes.flush(); nav.popBackStack() },
                    onOpenPage = { next -> nav.navigate(noteEditorRoute(next)) { launchSingleTop = true } },
                    onOpenLink = { link -> openWebLink(link) },
                    onOpenHistory = { id -> nav.navigate(noteHistoryRoute(id)) { launchSingleTop = true } },
                )
            }
            composable(NOTE_HISTORY_ROUTE, arguments = listOf(navArgument("pageId") { type = NavType.StringType })) { entry ->
                val pageId = entry.arguments?.getString("pageId") ?: return@composable
                app.aino.mobile.feature.notes.NoteHistoryScreen(notes, pageId, onBack = { nav.popBackStack() })
            }
            composable(AinoDestination.Notifications.route) {
                app.aino.mobile.feature.notifications.NotificationsScreen(
                    viewModel = notifications,
                    onBack = { nav.popBackStack() },
                    onOpenLink = { link -> openWebLink(link) },
                )
            }
            // P7.6: full-screen global search (the web hides its navbar search at ≤768px).
            composable(SEARCH_ROUTE) {
                val context = LocalContext.current
                val search = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.search.SearchViewModel>(
                    factory = app.aino.mobile.feature.search.SearchViewModel.factory(context, role),
                )
                LaunchedEffect(role) { search.setRole(role) }
                app.aino.mobile.feature.search.SearchScreen(
                    viewModel = search,
                    onBack = { nav.popBackStack() },
                    onOpenLink = { link -> nav.popBackStack(); openWebLink(link) },
                )
            }
            // P7.5: the web Organization page (departments, teams, org chart, task labels).
            composable(AinoDestination.Organization.route) {
                val context = LocalContext.current
                val organization = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.organization.OrganizationViewModel>(
                    factory = app.aino.mobile.feature.organization.OrganizationViewModel.factory(context),
                )
                RefetchOnResume(organization::refresh, resync)
                app.aino.mobile.feature.organization.OrganizationScreen(
                    viewModel = organization,
                    userRole = role,
                    userId = user.id,
                    // Web `updateUser({ org_id, role: "super_admin" })` after CreateOrgView.
                    onOrgCreated = { orgId -> auth.updateUser { it.copy(orgId = orgId, role = "super_admin") } },
                    // P10.3: Salary Slips tab only with the payroll feature (product decision).
                    payroll = app.aino.mobile.feature.organization.salaryTabEnabled(
                        user.tenantFeatures,
                        ungatedPlatformAdmin = user.role == "platform_admin" && user.tenantId == null,
                    ),
                )
            }
            // P8: the web Manager Dashboard (Approvals / Team Attendance / Analytics / My Requests).
            // `?tab=&request=` carry web `/manager` deep links (notification / push targets).
            composable(
                route = MANAGER_ROUTE_PATTERN,
                arguments = listOf("tab", "request").map { name -> navArgument(name) { defaultValue = "" } },
            ) { backStackEntry ->
                val context = LocalContext.current
                val manager = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.manager.ManagerViewModel>(
                    factory = app.aino.mobile.feature.manager.ManagerViewModel.factory(context),
                )
                RefetchOnResume(manager::refresh, resync)
                LaunchedEffect(manager) {
                    realtimeEvents.collect { event -> if (event.type in MANAGER_REFRESH_EVENTS) manager.refresh() }
                }
                app.aino.mobile.feature.manager.ManagerScreen(
                    viewModel = manager,
                    userRole = role,
                    userId = user.id,
                    onOpenMember = { userId -> nav.navigate(managerMemberRoute(userId)) { launchSingleTop = true } },
                    initialTab = backStackEntry.arguments?.getString("tab")?.ifEmpty { null },
                    initialRequest = backStackEntry.arguments?.getString("request")?.ifEmpty { null },
                )
            }
            composable(MANAGER_MEMBER_ROUTE, arguments = listOf(navArgument("userId") { type = NavType.LongType })) { entry ->
                val userId = entry.arguments?.getLong("userId") ?: return@composable
                val context = LocalContext.current
                val manager = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.manager.ManagerViewModel>(
                    factory = app.aino.mobile.feature.manager.ManagerViewModel.factory(context),
                    viewModelStoreOwner = androidx.compose.runtime.remember(entry) { nav.getBackStackEntry(MANAGER_ROUTE_PATTERN) },
                )
                RefetchOnResume(manager::refreshMemberDetail, resync)
                app.aino.mobile.feature.manager.MemberDetailScreen(
                    viewModel = manager,
                    userId = userId,
                    onBack = { nav.popBackStack() },
                )
            }
            if (app.aino.mobile.BuildConfig.DEBUG) {
                composable(AinoDestination.ApiProbe.route) {
                    val probe = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.debug.ApiProbeViewModel>(
                        factory = app.aino.mobile.feature.debug.ApiProbeViewModel.factory(LocalContext.current),
                    )
                    app.aino.mobile.feature.debug.ApiProbeScreen(probe)
                }
            }
            // Remaining More-sheet destinations render placeholders until their phases land.
            availableMoreDestinations(role, hasReports, user.tenantFeatures, user.orgId)
                // Screens with real routes above must never be shadowed by a placeholder.
                .filter {
                    it != AinoDestination.Attendance && it != AinoDestination.Leaves &&
                        it != AinoDestination.Calendar &&
                        it != AinoDestination.Organization && it != AinoDestination.Notes &&
                        it != AinoDestination.Manager
                }
                .forEach { destination ->
                    composable(destination.route) { PlaceholderScreen(destination.label, placeholderMessage(destination)) }
                }
        }
        }
        if (!fullScreen) {
            app.aino.mobile.feature.search.SearchOverlay(
                visible = searchActive,
                viewModel = shellSearch,
                onOpenLink = { link -> closeSearch(); openWebLink(link) },
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // P3.7: the clock verification sheet (ClockInVerifyModal equivalent) is
        // hosted globally so a clock action started on Attendance survives navigation.
        attendanceUi.verifySession?.let { session ->
            ClockInVerifySheet(
                session = session,
                onLaunchIdentityPrompt = onAttendanceBiometric,
                onUseFingerprint = {
                    // Tapping the fingerprint re-arms the prompt; the sheet's
                    // LaunchedEffect launches it on the new token.
                    attendance.requestIdentityPrompt()
                },
                actions = VerifyActions(
                    onRetryLocation = { attendance.resumePending(onAttendanceLocationPermission) },
                    onOpenAppSettings = attendanceSystemActions.openAppSettings,
                    onEnableLocation = attendanceSystemActions.enableLocation,
                    onSetUpScreenLock = attendanceSystemActions.openSecuritySettings,
                    onEnableFingerprint = attendanceSystemActions.enableFingerprintForAttendance,
                ),
                onClose = attendance::dismissVerify,
            )
        }
    }
    // Shell overlay layer, above the Scaffold so it never affects bar measurement.
    // The scrim is composed *before* its sheet so the sheet receives its own taps.
    ShellPopup(
        visible = moreOpen,
        alignment = Alignment.BottomEnd,
        onDismiss = { moreOpen = false },
        fromTop = false,
        // `.mobile-more-popup { bottom: calc(100% + 10px); right: 4px }` above the 64dp bar.
        sheetModifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 64.dp + 10.dp, end = 4.dp),
    ) {
        MoreSheet(
            destinations = moreItems,
            currentRoute = current,
            onNavigate = { destination -> moreOpen = false; navigate(destination) },
        )
    }
    // P9: a minimised meeting floats over every other route (web MeetingPiP).
    val meetingSession = androidx.compose.runtime.remember { app.aino.mobile.feature.meeting.MeetingRuntime.get(shellContext) }
    val meeting by meetingSession.state.collectAsStateWithLifecycle()
    val inPip by app.aino.mobile.core.call.PipState.inPip.collectAsStateWithLifecycle()
    meeting?.let { live ->
        if (current != MEETING_ROOM_ROUTE && !inPip) {
            app.aino.mobile.feature.meeting.MeetingPipWidget(
                live,
                meetingSession,
                onOpen = { nav.navigate(meetingRoomRoute(live.code)) { launchSingleTop = true } },
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(end = 24.dp, bottom = 24.dp + if (fullScreen) 0.dp else 64.dp),
            )
        }
    }
    // Web GlobalMeetingNotification: `meeting_started` for a meeting you are not in.
    var announcement by androidx.compose.runtime.remember { mutableStateOf<app.aino.mobile.feature.meeting.MeetingAnnouncement?>(null) }
    LaunchedEffect(realtimeEvents) {
        realtimeEvents.collect { event ->
            if (event.type != "meeting_started") return@collect
            val next = (event.data as? kotlinx.serialization.json.JsonObject)?.let { app.aino.mobile.feature.meeting.meetingAnnouncement(it) } ?: return@collect
            if (meetingSession.state.value?.meeting?.id == next.meetingId) return@collect
            if (announcement?.meetingId == next.meetingId && !next.restarted) return@collect
            announcement = next
        }
    }
    announcement?.let { shown ->
        app.aino.mobile.feature.meeting.MeetingStartedCard(
            shown,
            onJoin = {
                announcement = null
                nav.navigate(meetingRoute(shown.code)) { launchSingleTop = true }
            },
            onDismiss = { announcement = null },
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, end = 12.dp),
        )
    }
    // Tasks snackbars live at shell level so Undo / View survive the detail screen closing.
    app.aino.mobile.feature.tasks.TaskNoticeHost(
        tasks,
        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = if (fullScreen) 8.dp else 72.dp),
    )
    }
    // Ring answers, chat meeting cards and group-call starts ask for routes from outside the NavHost.
    LaunchedEffect(nav) {
        RouteRequests.routes.collect { route -> runCatching { nav.navigate(route) { launchSingleTop = true } } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ShellPopup(
    visible: Boolean,
    alignment: Alignment,
    onDismiss: () -> Unit,
    fromTop: Boolean,
    sheetModifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (visible) {
        Box(
            Modifier.matchParentSize().clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(alignment).then(sheetModifier),
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(160)) { if (fromTop) -it / 8 else it / 8 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
    ) { content() }
}

@Composable
private fun FeatureGateWarningBanner(onRetry: () -> Unit, onDismiss: () -> Unit) {
    app.aino.mobile.core.designsystem.AinoAlert(
        text = "Workspace features could not be loaded. Some sections are hidden.",
        tone = app.aino.mobile.core.designsystem.AlertTone.Warning,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
        androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun AinoShellTopBar(
    user: app.aino.mobile.core.auth.AinoUser,
    statusVisual: app.aino.mobile.core.designsystem.component.StatusVisual,
    unreadNotifications: Int,
    onSearch: () -> Unit,
    searchActive: Boolean,
    search: app.aino.mobile.feature.search.SearchViewModel,
    onCloseSearch: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    // §2 top bar (Navbar ≤768px): 56dp min-height, 8/12dp padding, logo mark
    // only, bell, profile trigger (avatar + status dot). `.brand-text` /
    // `.profile-name` / `.search-trigger` are hidden. Background matches the
    // page body and the bottom tab bar (no border) so the chrome reads as one
    // continuous surface, Signal-style.
    val colors = LocalWebColors.current
    // P10.4 web Navbar `logoSrc`: the org logo when set, else the AINO mark.
    val shellContext = LocalContext.current
    val container = androidx.compose.runtime.remember { app.aino.mobile.core.AppContainer.get(shellContext) }
    val branding by container.branding.state.collectAsStateWithLifecycle()
    val orgLogo = branding.logoUrl?.takeIf(String::isNotBlank)?.let { app.aino.mobile.core.media.resolveServerMediaUrl(it) }
    Surface(color = colors.bg, tonalElevation = 0.dp) {
        androidx.compose.animation.AnimatedContent(
            targetState = searchActive,
            transitionSpec = {
                val enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(180)) { width -> if (targetState) width / 6 else -width / 6 }
                val exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                androidx.compose.animation.ContentTransform(enter, exit)
            },
            label = "shellTopBar",
        ) { active ->
            if (active) {
                ShellSearchBar(search, onCloseSearch)
            } else {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (orgLogo != null) {
                        coil3.compose.AsyncImage(
                            model = orgLogo,
                            imageLoader = container.imageLoader,
                            contentDescription = branding.orgName ?: "Logo",
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.height(36.dp).widthIn(max = 140.dp),
                        )
                    } else {
                        Image(
                            painterResource(R.drawable.aino_icon),
                            contentDescription = "AINO",
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSearch),
                        contentAlignment = Alignment.Center,
                    ) { Icon(HeroIcons.MagnifyingGlass, "Search", Modifier.size(22.dp), tint = colors.textSecondary) }
                    // Only the ripple target is clipped to a circle; the badge sits in
                    // the unclipped outer box (a clipped parent cut the pill's corner
                    // off and squashed it).
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.matchParentSize().clip(CircleShape).clickable(onClick = onNotifications),
                            contentAlignment = Alignment.Center,
                        ) { Icon(HeroIcons.Bell, "Notifications", Modifier.size(22.dp), tint = colors.textSecondary) }
                        // Anchored to the bell glyph's top-right corner (glyph is 22dp, centred).
                        app.aino.mobile.core.designsystem.component.CountBadge(
                            unreadNotifications,
                            Modifier.align(Alignment.Center).offset(x = 9.dp, y = (-9).dp),
                        )
                    }
                    // The touch target is a plain (unclipped) box: a CircleShape clip here
                    // would crop the status dot, which deliberately overhangs the avatar's
                    // bottom-right corner.
                    Box(
                        Modifier.padding(start = 8.dp).size(44.dp).clickable(onClickLabel = "Profile", onClick = onProfile),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(38.dp)) {
                            app.aino.mobile.core.designsystem.component.UserAvatar(user.fullName ?: user.username, user.avatar, 38.dp)
                            app.aino.mobile.core.designsystem.component.StatusDot(
                                statusVisual, 15.dp, colors.bg,
                                Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The search icon morphs the top bar into the field; back / the arrow collapses it. */
@Composable
private fun ShellSearchBar(search: app.aino.mobile.feature.search.SearchViewModel, onClose: () -> Unit) {
    val colors = LocalWebColors.current
    val ui by search.ui.collectAsStateWithLifecycle()
    // Composed on open, so it takes precedence over the NavHost's back callback.
    BackHandler(onBack = onClose)
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Close search", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(HeroIcons.ArrowLeft, "Close search", Modifier.size(22.dp), tint = colors.textSecondary) }
        app.aino.mobile.feature.search.SearchField(
            ui = ui,
            viewModel = search,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            showIcon = false,
        )
    }
}

/** `theme_changed` WS payload `{ theme: "dark" | "light" }` → dark flag. */
private fun themeFromEvent(data: kotlinx.serialization.json.JsonElement?): Boolean? {
    val theme = (data as? kotlinx.serialization.json.JsonObject)?.get("theme")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return when (theme) { "dark" -> true; "light" -> false; else -> null }
}

/** `tenant_features_changed` WS payload `{ features: { name: bool } }`. */
private fun featuresFromEvent(data: kotlinx.serialization.json.JsonElement?): Map<String, Boolean>? {
    val features = (data as? kotlinx.serialization.json.JsonObject)?.get("features") as? kotlinx.serialization.json.JsonObject ?: return null
    return features.mapNotNull { (key, value) ->
        (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { key to it }
    }.toMap()
}

/** `leave_policy_changed` WS payload `{ scope: "holidays" | "policies" | "balances" }`. */
private fun leavePolicyScope(data: kotlinx.serialization.json.JsonElement?): String? =
    ((data as? kotlinx.serialization.json.JsonObject)?.get("scope") as? kotlinx.serialization.json.JsonPrimitive)?.content

private val DASHBOARD_REFRESH_EVENTS = setOf(
    "task_assigned",
    "task_updated",
    "calendar_refresh",
    "meeting_updated",
    "meeting_cancelled",
    // Pending approvals card (managers).
    "approval_update",
    "leave_update",
)

/**
 * My Team reloads its visible tab on these (approvals, leaves, attendance);
 * `team_attendance_update` is a direct report's clock/break/manual entry.
 */
private val MANAGER_REFRESH_EVENTS = setOf("approval_update", "leave_update", "attendance_update", "team_attendance_update")

/** Task-scoped realtime events; Tasks patches the affected row from `{ taskId, action }`. */
private val TASK_REFRESH_EVENTS = setOf("task_assigned", "task_updated")

@Composable
private fun PlaceholderScreen(title: String, message: String) {
    AinoAtmosphere {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val destination = destinationFor(title)
                    destination?.let { Icon(it.icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary) }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

private const val TASKS_POLL_MS = 30_000L

/**
 * Silent refresh every [periodMs] while this route is composed and the app is
 * at least STARTED. Covers changes the server never pushes to this user
 * (teammates' edits on a shared board, sprint lifecycle, Service Desk).
 */
@Composable
private fun ForegroundPoll(periodMs: Long, refresh: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latest by androidx.compose.runtime.rememberUpdatedState(refresh)
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(periodMs)
                latest()
            }
        }
    }
}

/** Calendar.tsx refetches on these WS events. */
private val CALENDAR_REFRESH_EVENTS = setOf("calendar_refresh", "meeting_updated", "meeting_cancelled")

/** Web focus/visibility refetch for a per-route ViewModel (skips the first resume, which is the initial load). */
@Composable
private fun RefetchOnResume(refetch: () -> Unit, resync: kotlinx.coroutines.flow.StateFlow<Long>? = null) {
    var resumedOnce by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (resumedOnce) refetch()
        resumedOnce = true
        onPauseOrDispose { }
    }
    // Reconnect catch-up while this screen is open.
    if (resync != null) {
        val opened = androidx.compose.runtime.remember { resync.value }
        val current by resync.collectAsStateWithLifecycle()
        LaunchedEffect(current) { if (current != opened) refetch() }
    }
}
