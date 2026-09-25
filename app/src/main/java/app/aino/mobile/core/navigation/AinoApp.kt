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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
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
import app.aino.mobile.feature.attendance.AttendanceAction
import app.aino.mobile.feature.attendance.WorkMode
import app.aino.mobile.feature.attendance.verify.ClockInVerifySheet
import app.aino.mobile.feature.tasks.TasksScreen
import app.aino.mobile.feature.tasks.TaskViewModel
import app.aino.mobile.feature.profile.ProfileScreen
import app.aino.mobile.feature.profile.ProfileViewModel
import app.aino.mobile.feature.chat.ChatScreen
import app.aino.mobile.feature.chat.ChatViewModel
import app.aino.mobile.core.update.UpdateViewModel
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
import app.aino.mobile.core.call.IncomingCallViewModel
import app.aino.mobile.core.call.CallRealtimeEvent
import app.aino.mobile.core.call.CallRealtimeRouter
import app.aino.mobile.core.call.CallSessionRuntime
import app.aino.mobile.core.call.toRoute
import app.aino.mobile.core.call.IncomingCallScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AinoApp(
    auth: AuthViewModel,
    updates: UpdateViewModel,
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
    onPickChatDocument: () -> Unit,
    onAuthenticatedForPush: () -> Unit,
) {
    val ui by auth.ui.collectAsStateWithLifecycle()
    val incomingCallUi by incomingCall.ui.collectAsStateWithLifecycle()
    val realtimeState by realtime.state.collectAsStateWithLifecycle()
    val tenantAuthenticated = (ui.state as? AuthState.Authenticated)?.user?.tenantId != null
    LaunchedEffect(tenantAuthenticated) { realtime.setAuthenticatedTenant(tenantAuthenticated) }
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
    }
    // A new socket rebuilds the meeting mesh and replays its chat (web WS `open`).
    LaunchedEffect(realtimeState) { meetingSession.onRealtimeState(realtimeState == RealtimeState.Connected) }
    LaunchedEffect(tenantAuthenticated) { if (!tenantAuthenticated) meetingSession.leave() }
    // P7.4: the bell's notifications live for the session (web NotificationBell polls every 30s).
    val notifications = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.notifications.NotificationsViewModel>(
        factory = app.aino.mobile.feature.notifications.NotificationsViewModel.factory(appContext),
    )
    LaunchedEffect(tenantAuthenticated) { if (!tenantAuthenticated) notifications.reset() }
    androidx.lifecycle.compose.LifecycleResumeEffect(tenantAuthenticated) {
        if (tenantAuthenticated) notifications.start()
        onPauseOrDispose { notifications.stop() }
    }
    // P7.2: one notebook per user for the session; unsaved edits flush when the app stops.
    val notes = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.notes.NotesViewModel>(
        factory = app.aino.mobile.feature.notes.NotesViewModel.factory(appContext),
    )
    LaunchedEffect(tenantAuthenticated) { if (!tenantAuthenticated) notes.reset() }
    androidx.lifecycle.compose.LifecycleStartEffect(notes) { onStopOrDispose { notes.flush() } }
    val setDark by androidx.compose.runtime.rememberUpdatedState(onSetDark)
    // Web StatusProvider / ThemeProvider / NotificationPrefsProvider start on tenant sign-in.
    LaunchedEffect(tenantAuthenticated, authenticatedUser?.id) {
        val id = authenticatedUser?.id
        if (tenantAuthenticated && id != null) profile.start(id) { dark -> setDark(dark) } else profile.stop()
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
                    }
                    null -> Unit
                    else -> activeCall.onEvent(callEvent)
                }
                meetingSession.onRealtimeEvent(event)
                if (event.type in DASHBOARD_REFRESH_EVENTS) dashboard.refresh()
                if (event.type in TASK_REFRESH_EVENTS) tasks.onTaskEvent()
                when (event.type) {
                    "user_status" -> profile.onStatusEvent(event.data)
                    // Multi-device theme sync (web ThemeContext).
                    "theme_changed" -> themeFromEvent(event.data)?.let { dark -> setDark(dark) }
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
            notifications = notifications,
            notes = notes,
        )
    }
    app.aino.mobile.core.call.ActiveCallScreen(activeCall)
    if (incomingCallUi.route != null) {
        IncomingCallScreen(incomingCall) { incomingCall.clear() }
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
    updates: UpdateViewModel,
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
    onPickChatDocument: () -> Unit,
    onRetryFeatures: () -> Unit,
    onSignOut: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    /** Raw WS events for per-route ViewModels (Calendar, …) to refetch on. */
    realtimeEvents: kotlinx.coroutines.flow.Flow<app.aino.mobile.core.realtime.RealtimeEnvelope>,
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
    val moreActive = current != null && current in moreRoutes
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
    // A tapped system notification: chat messages open their thread, everything
    // else opens the notifications page; the outcome feeds the routing metrics.
    val pendingTap by app.aino.mobile.core.push.PendingPushTap.tap.collectAsStateWithLifecycle()
    val shellContext = LocalContext.current
    LaunchedEffect(pendingTap) {
        val tap = app.aino.mobile.core.push.PendingPushTap.consume() ?: return@LaunchedEffect
        val route = if (tap.type == "chat_message" && tap.conversationId != null) chatThreadRoute(tap.conversationId)
        else AinoDestination.Notifications.route
        val routed = runCatching { nav.navigate(route) { launchSingleTop = true } }.isSuccess
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
    Box(Modifier.fillMaxSize()) {
    AinoScaffold(
        topBar = {
            if (!fullScreen) {
                AinoShellTopBar(
                    user = user,
                    statusVisual = statusVisual,
                    unreadNotifications = unreadNotifications,
                    onSearch = { moreOpen = false; nav.navigate(SEARCH_ROUTE) { launchSingleTop = true } },
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
        NavHost(nav, startDestination = AinoDestination.Dashboard.route, modifier = Modifier.fillMaxSize()) {
            composable(AinoDestination.Dashboard.route) {
                HomeScreen(
                    user,
                    dashboard,
                    attendanceUi.status,
                    attendanceUi.clockBusy,
                    attendanceUi.workMode.name.lowercase(),
                    onAttendanceWorkMode = { mode ->
                        attendance.setWorkMode(WorkMode.entries.first { it.name.equals(mode, ignoreCase = true) })
                    },
                    onAttendanceAction = { action ->
                        attendance.prepare(
                            if (action == "clock_in") AttendanceAction.ClockIn else AttendanceAction.ClockOut,
                            onAttendanceLocationPermission,
                        )
                    },
                    onAttendanceBreak = attendance::breakAction,
                    onCalendar = { navigate(AinoDestination.Calendar) },
                    onTasks = { navigate(AinoDestination.Tasks) },
                    attendanceNotice = attendanceUi.error ?: attendanceUi.message,
                    attendanceNoticeIsError = attendanceUi.error != null,
                    onDismissAttendanceNotice = attendance::clearNotice,
                )
            }
            composable(AinoDestination.Tasks.route) {
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
            composable(AinoDestination.Chat.route) {
                ChatScreen(
                    viewModel = chat,
                    onPickDocument = onPickChatDocument,
                    conversationId = null,
                    meetingsEnabled = user.tenantFeatures["meetings"] == true,
                    onOpenConversation = { conversationId ->
                        nav.navigate(chatThreadRoute(conversationId)) { launchSingleTop = true }
                    },
                    onNavigateBack = { nav.popBackStack() },
                )
            }
            composable(
                route = AinoDestination.ChatThread.route,
                arguments = listOf(navArgument(CHAT_CONVERSATION_ARGUMENT) { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = CHAT_DEEP_LINK_PATTERN }),
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getLong(CHAT_CONVERSATION_ARGUMENT)
                    ?: return@composable
                val navigateBack = {
                    chat.closeConversation()
                    nav.popBackStack()
                    Unit
                }
                BackHandler(onBack = navigateBack)
                ChatScreen(
                    viewModel = chat,
                    onPickDocument = onPickChatDocument,
                    conversationId = conversationId,
                    meetingsEnabled = user.tenantFeatures["meetings"] == true,
                    onOpenConversation = { nextConversationId ->
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
                RefetchOnResume(calendar::refresh)
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
                route = AinoDestination.Attendance.route + "?tab={tab}",
                arguments = listOf(androidx.navigation.navArgument("tab") { defaultValue = "" }),
            ) { backStackEntry ->
                AttendanceScreen(
                    viewModel = attendance,
                    userRole = role,
                    initialTab = AttendanceTab.fromHash(backStackEntry.arguments?.getString("tab")),
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
                RefetchOnResume(organization::refresh)
                app.aino.mobile.feature.organization.OrganizationScreen(
                    viewModel = organization,
                    userRole = role,
                    userId = user.id,
                    // Web `updateUser({ org_id, role: "super_admin" })` after CreateOrgView.
                    onOrgCreated = { orgId -> auth.updateUser { it.copy(orgId = orgId, role = "super_admin") } },
                )
            }
            // P8: the web Manager Dashboard (Approvals / Team Attendance / Analytics / My Requests).
            composable(AinoDestination.Manager.route) {
                val context = LocalContext.current
                val manager = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.manager.ManagerViewModel>(
                    factory = app.aino.mobile.feature.manager.ManagerViewModel.factory(context),
                )
                RefetchOnResume(manager::refresh)
                app.aino.mobile.feature.manager.ManagerScreen(
                    viewModel = manager,
                    userRole = role,
                    onOpenMember = { userId -> nav.navigate(managerMemberRoute(userId)) { launchSingleTop = true } },
                )
            }
            composable(MANAGER_MEMBER_ROUTE, arguments = listOf(navArgument("userId") { type = NavType.LongType })) { entry ->
                val userId = entry.arguments?.getLong("userId") ?: return@composable
                val context = LocalContext.current
                val manager = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.manager.ManagerViewModel>(
                    factory = app.aino.mobile.feature.manager.ManagerViewModel.factory(context),
                    viewModelStoreOwner = androidx.compose.runtime.remember(entry) { nav.getBackStackEntry(AinoDestination.Manager.route) },
                )
                RefetchOnResume(manager::refreshMemberDetail)
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
            // P6.5/P6.6: Admin lists the sections Android implements (web SECTIONS
            // "Structure" → Agile Config, Projects); each opens full screen.
            val adminSections = app.aino.mobile.feature.tasks.allowedAdminSections(
                role, user.orgId, user.tenantFeatures,
                ungatedPlatformAdmin = user.role == "platform_admin" && user.tenantId == null,
            )
            if (AinoDestination.Admin in availableMoreDestinations(role, hasReports, user.tenantFeatures, user.orgId)) {
                composable(AinoDestination.Admin.route) {
                    app.aino.mobile.feature.tasks.AdminScreen(role, adminSections) { section ->
                        val route = if (section.key == "agile") ADMIN_AGILE_ROUTE else ADMIN_PROJECTS_ROUTE
                        nav.navigate(route) { launchSingleTop = true }
                    }
                }
            }
            if (adminSections.any { it.key == "agile" }) {
                composable(ADMIN_AGILE_ROUTE) {
                    val context = LocalContext.current
                    val agile = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.tasks.AgileSettingsViewModel>(
                        factory = app.aino.mobile.feature.tasks.AgileSettingsViewModel.factory(context),
                    )
                    RefetchOnResume(agile::refresh)
                    app.aino.mobile.feature.tasks.AgileSettingsScreen(
                        viewModel = agile,
                        onBack = { nav.popBackStack() },
                        // Web `refreshConfig()`: the Tasks page's cached agile config / labels.
                        onChanged = { tasks.refresh() },
                    )
                }
            }
            if (adminSections.any { it.key == "projects" }) {
                composable(ADMIN_PROJECTS_ROUTE) {
                    val context = LocalContext.current
                    val projects = androidx.lifecycle.viewmodel.compose.viewModel<app.aino.mobile.feature.tasks.ProjectsViewModel>(
                        factory = app.aino.mobile.feature.tasks.ProjectsViewModel.factory(context, role),
                    )
                    LaunchedEffect(role) { projects.setRole(role) }
                    RefetchOnResume(projects::refresh)
                    app.aino.mobile.feature.tasks.ProjectsScreen(
                        viewModel = projects,
                        onBack = { nav.popBackStack() },
                        onOpenTask = { id -> openWebLink("/tasks?task=$id") },
                        onChanged = { tasks.refresh() },
                    )
                }
            }
            // Remaining More-sheet destinations render placeholders until their phases land.
            availableMoreDestinations(role, hasReports, user.tenantFeatures, user.orgId)
                // Screens with real routes above must never be shadowed by a placeholder.
                .filter {
                    it != AinoDestination.Attendance && it != AinoDestination.Leaves &&
                        it != AinoDestination.Organization && it != AinoDestination.Notes &&
                        it != AinoDestination.Admin && it != AinoDestination.Manager
                }
                .forEach { destination ->
                    composable(destination.route) { PlaceholderScreen(destination.label) }
                }
        }
        }
        // P3.7: the clock verification sheet (ClockInVerifyModal equivalent) is
        // hosted globally so it works from the dashboard timer card too.
        attendanceUi.verifySession?.let { session ->
            ClockInVerifySheet(
                session = session,
                onVerifyIdentity = onAttendanceBiometric,
                onRetryLocation = { attendance.resumePending(onAttendanceLocationPermission) },
                onEnrollFace = { attendance.dismissVerify(); nav.navigate(PROFILE_FACE_ROUTE) { launchSingleTop = true } },
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
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    // §2 top bar (Navbar ≤768px): 56dp min-height, 8/12dp padding, logo mark
    // only, bell, profile trigger (avatar + status dot). `.brand-text` /
    // `.profile-name` / `.search-trigger` are hidden. Background matches the
    // page body and the bottom tab bar (no border) so the chrome reads as one
    // continuous surface, Signal-style.
    val colors = LocalWebColors.current
    Surface(color = colors.bg, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.aino_icon),
                contentDescription = "AINO",
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Search, "Search", Modifier.size(22.dp), tint = colors.textSecondary) }
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNotifications),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.NotificationsNone, "Notifications", Modifier.size(22.dp), tint = colors.textSecondary)
                app.aino.mobile.feature.notifications.unreadBadgeLabel(unreadNotifications)?.let { label ->
                    // `.chatBadge`-style count: danger fill, white text, ringed with the bar colour.
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp)
                            .background(colors.danger, CircleShape).border(2.dp, colors.bg, CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = androidx.compose.ui.graphics.Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
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

/** `theme_changed` WS payload `{ theme: "dark" | "light" }` → dark flag. */
private fun themeFromEvent(data: kotlinx.serialization.json.JsonElement?): Boolean? {
    val theme = (data as? kotlinx.serialization.json.JsonObject)?.get("theme")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return when (theme) { "dark" -> true; "light" -> false; else -> null }
}

private val DASHBOARD_REFRESH_EVENTS = setOf(
    "task_assigned",
    "task_updated",
    "calendar_refresh",
    "meeting_updated",
    "meeting_cancelled",
)

/** Task-scoped realtime events; the planner reloads rather than patching a row. */
private val TASK_REFRESH_EVENTS = setOf("task_assigned", "task_updated")

@Composable
private fun PlaceholderScreen(title: String) {
    AinoAtmosphere {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val destination = destinationFor(title)
                    destination?.let { Icon(it.icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary) }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    Text("This feature module is next in the native rollout.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Calendar.tsx refetches on these WS events. */
private val CALENDAR_REFRESH_EVENTS = setOf("calendar_refresh", "meeting_updated", "meeting_cancelled")

/** Web focus/visibility refetch for a per-route ViewModel (skips the first resume, which is the initial load). */
@Composable
private fun RefetchOnResume(refetch: () -> Unit) {
    var resumedOnce by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (resumedOnce) refetch()
        resumedOnce = true
        onPauseOrDispose { }
    }
}
