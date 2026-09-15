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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.aino.mobile.core.auth.AuthState
import app.aino.mobile.core.auth.AuthViewModel
import app.aino.mobile.feature.auth.ChangePasswordScreen
import app.aino.mobile.feature.auth.LoginScreen
import app.aino.mobile.feature.auth.RealmChoiceScreen
import app.aino.mobile.feature.home.HomeScreen
import app.aino.mobile.feature.home.DashboardViewModel
import app.aino.mobile.feature.attendance.AttendanceScreen
import app.aino.mobile.feature.attendance.AttendanceViewModel
import app.aino.mobile.feature.tasks.TasksScreen
import app.aino.mobile.feature.tasks.TaskViewModel
import app.aino.mobile.feature.leaves.LeavesScreen
import app.aino.mobile.feature.leaves.LeaveViewModel
import app.aino.mobile.feature.profile.ProfileScreen
import app.aino.mobile.feature.profile.ProfileViewModel
import app.aino.mobile.feature.chat.ChatScreen
import app.aino.mobile.feature.chat.ChatViewModel
import app.aino.mobile.core.update.UpdateViewModel
import app.aino.mobile.core.realtime.RealtimeState
import app.aino.mobile.core.realtime.RealtimeViewModel
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.OutboxWorker
import app.aino.mobile.core.db.shouldWakeOutboxOnReconnect

@Composable
fun AinoApp(
    auth: AuthViewModel,
    updates: UpdateViewModel,
    realtime: RealtimeViewModel,
    dashboard: DashboardViewModel,
    attendance: AttendanceViewModel,
    tasks: TaskViewModel,
    leaves: LeaveViewModel,
    profile: ProfileViewModel,
    chat: ChatViewModel,
    biometricAvailable: Boolean,
    onBiometricLogin: () -> Unit,
    onBiometricEnroll: () -> Unit,
    onAttendanceLocationPermission: () -> Unit,
    onAttendanceBiometric: () -> Unit,
    onPickChatDocument: () -> Unit,
) {
    val ui by auth.ui.collectAsStateWithLifecycle()
    val realtimeState by realtime.state.collectAsStateWithLifecycle()
    val tenantAuthenticated = (ui.state as? AuthState.Authenticated)?.user?.tenantId != null
    LaunchedEffect(tenantAuthenticated) { realtime.setAuthenticatedTenant(tenantAuthenticated) }
    val authenticatedUser = (ui.state as? AuthState.Authenticated)?.user
    LaunchedEffect(authenticatedUser?.tenantId, authenticatedUser?.id) {
        chat.setScope(authenticatedUser?.tenantId, authenticatedUser?.id)
    }
    val appContext = LocalContext.current.applicationContext
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
                if (event.type in DASHBOARD_REFRESH_EVENTS) dashboard.refresh()
                if (event.type in TASK_REFRESH_EVENTS) tasks.refresh()
                chat.onRealtimeEvent(event.type)
            }
        }
    }
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
            state.user.role,
            state.user.hasReports,
            state.user,
            updates,
            realtimeState,
            dashboard,
            attendance,
            tasks,
            leaves,
            profile,
            chat,
            biometricAvailable,
            ui.biometricEnrolled,
            onBiometricEnroll,
            auth::disableBiometric,
            onAttendanceLocationPermission,
            onAttendanceBiometric,
            onPickChatDocument,
        )
    }
}

@Composable
private fun AuthenticatedShell(
    role: String,
    hasReports: Boolean,
    user: app.aino.mobile.core.auth.AinoUser,
    updates: UpdateViewModel,
    realtimeState: RealtimeState,
    dashboard: DashboardViewModel,
    attendance: AttendanceViewModel,
    tasks: TaskViewModel,
    leaves: LeaveViewModel,
    profile: ProfileViewModel,
    chat: ChatViewModel,
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    onBiometricEnroll: () -> Unit,
    onBiometricDisable: () -> Unit,
    onAttendanceLocationPermission: () -> Unit,
    onAttendanceBiometric: () -> Unit,
    onPickChatDocument: () -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val chatUi by chat.ui.collectAsStateWithLifecycle()
    val tabs = visibleBottomDestinations(
        user.tenantFeatures,
        ungatedPlatformAdmin = user.role == "platform_admin" && user.tenantId == null,
    )
    fun navigate(destination: AinoDestination) {
        nav.navigate(destination.route) {
            launchSingleTop = true
            if (destination.inBottomBar) popUpTo(AinoDestination.Dashboard.route)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LegacyTopBar(
                user = user,
                realtimeState = realtimeState,
                onNotifications = { navigate(AinoDestination.Notifications) },
                onProfile = { navigate(AinoDestination.Profile) },
            )
        },
        bottomBar = {
            LegacyBottomBar(
                destinations = tabs,
                currentRoute = current,
                chatUnread = chatUi.unread,
                onNavigate = ::navigate,
            )
        },
    ) { padding ->
        NavHost(nav, startDestination = AinoDestination.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(AinoDestination.Dashboard.route) {
                HomeScreen(
                    user,
                    dashboard,
                    attendance,
                    onAttendanceLocationPermission,
                    onAttendanceBiometric,
                    onCalendar = { navigate(AinoDestination.Calendar) },
                    onTasks = { navigate(AinoDestination.Tasks) },
                )
            }
            composable(AinoDestination.Attendance.route) {
                AttendanceScreen(attendance, onAttendanceLocationPermission, onAttendanceBiometric)
            }
            composable(AinoDestination.Tasks.route) { TasksScreen(tasks) }
            composable(AinoDestination.Chat.route) { ChatScreen(chat, onPickChatDocument) }
            bottomDestinations.filterNot {
                it in setOf(AinoDestination.Dashboard, AinoDestination.Attendance, AinoDestination.Tasks, AinoDestination.Chat, AinoDestination.More)
            }.forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination.label) }
            }
            composable(AinoDestination.More.route) {
                val updateUi by updates.ui.collectAsStateWithLifecycle()
                val context = LocalContext.current
                MoreScreen(
                    role,
                    hasReports,
                    user.tenantFeatures,
                    updateUi,
                    realtimeState,
                    biometricAvailable,
                    biometricEnrolled,
                    ::navigate,
                    updates::check,
                    { updates.install(context) },
                    onBiometricEnroll,
                    onBiometricDisable,
                )
            }
            composable(AinoDestination.Leaves.route) { LeavesScreen(leaves) }
            composable(AinoDestination.Profile.route) { ProfileScreen(profile) }
            composable(AinoDestination.Notifications.route) { PlaceholderScreen("Notifications") }
            val nativeMoreRoutes = setOf(AinoDestination.Leaves, AinoDestination.Profile)
            availableMoreDestinations(role, hasReports, user.tenantFeatures).filterNot { it in nativeMoreRoutes }.forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination.label) }
            }
        }
    }
}

@Composable
private fun LegacyTopBar(
    user: app.aino.mobile.core.auth.AinoUser,
    realtimeState: RealtimeState,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.aino_icon),
                contentDescription = "AINO",
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)),
            )
            Text(
                "AINO",
                Modifier.padding(start = 10.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 1.5.sp,
            )
        }
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).clickable(onClick = onNotifications),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.NotificationsNone, "Notifications", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.padding(start = 12.dp).size(34.dp).clickable(onClick = onProfile)) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(user.fullName ?: user.username), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp)
                    .background(if (realtimeState == RealtimeState.Connected) app.aino.mobile.core.designsystem.theme.AinoSuccess else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun LegacyBottomBar(
    destinations: List<AinoDestination>,
    currentRoute: String?,
    chatUnread: Int,
    onNavigate: (AinoDestination) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)).padding(top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { item ->
            val selected = currentRoute == item.route
            Column(
                Modifier.weight(1f).fillMaxSize().clickable { onNavigate(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Box {
                    Icon(
                        item.icon,
                        item.label,
                        Modifier.size(23.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item == AinoDestination.Chat && chatUnread > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(start = 14.dp).background(AinoDanger, CircleShape)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (chatUnread > 99) "99+" else chatUnread.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Text(
                    item.label,
                    Modifier.padding(top = 3.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun initials(name: String): String = name.trim().split(Regex("\\s+")).take(2)
    .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifBlank { "?" }

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

@Composable
private fun MoreScreen(
    role: String,
    hasReports: Boolean,
    features: Map<String, Boolean>,
    updateUi: app.aino.mobile.core.update.UpdateUiState,
    realtimeState: RealtimeState,
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    onNavigate: (AinoDestination) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onBiometricEnroll: () -> Unit,
    onBiometricDisable: () -> Unit,
) {
    AinoAtmosphere {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Text("More", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column {
                    val destinations = availableMoreDestinations(role, hasReports, features)
                    destinations.forEachIndexed { index, destination ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onNavigate(destination) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(34.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(9.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(destination.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(destination.label, Modifier.padding(start = 12.dp).weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index < destinations.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                    }
                }
            }
        }
    }
}

private fun destinationSubtitle(destination: AinoDestination): String = when (destination) {
    AinoDestination.Leaves -> "Balances, applications and holidays"
    AinoDestination.Calendar -> "Events, meetings and schedule"
    AinoDestination.Notes -> "Daily notes and shared documents"
    AinoDestination.Organization -> "People, teams and structure"
    AinoDestination.Manager -> "Approvals and team activity"
    AinoDestination.Admin -> "Workspace configuration"
    AinoDestination.Tenants -> "Platform tenant management"
    AinoDestination.Profile -> "Account and preferences"
    AinoDestination.Notifications -> "Updates and alerts"
    else -> destination.label
}

private fun realtimeLabel(state: RealtimeState): String = when (state) {
    RealtimeState.Connected -> "Live updates are active"
    is RealtimeState.Connecting -> "Connecting · attempt ${state.attempt + 1}"
    is RealtimeState.Stopped -> state.reason
    RealtimeState.Disconnected -> "Connects after tenant sign-in"
}
