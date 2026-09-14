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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import app.aino.mobile.core.update.UpdateViewModel
import app.aino.mobile.core.realtime.RealtimeState
import app.aino.mobile.core.realtime.RealtimeViewModel
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone

@Composable
fun AinoApp(
    auth: AuthViewModel,
    updates: UpdateViewModel,
    realtime: RealtimeViewModel,
    dashboard: DashboardViewModel,
    biometricAvailable: Boolean,
    onBiometricLogin: () -> Unit,
    onBiometricEnroll: () -> Unit,
) {
    val ui by auth.ui.collectAsStateWithLifecycle()
    val realtimeState by realtime.state.collectAsStateWithLifecycle()
    val tenantAuthenticated = (ui.state as? AuthState.Authenticated)?.user?.tenantId != null
    LaunchedEffect(tenantAuthenticated) { realtime.setAuthenticatedTenant(tenantAuthenticated) }
    LaunchedEffect(tenantAuthenticated) {
        if (tenantAuthenticated) {
            realtime.events.collect { event ->
                if (event.type in DASHBOARD_REFRESH_EVENTS) dashboard.refresh()
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
            biometricAvailable,
            ui.biometricEnrolled,
            onBiometricEnroll,
            auth::disableBiometric,
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
    biometricAvailable: Boolean,
    biometricEnrolled: Boolean,
    onBiometricEnroll: () -> Unit,
    onBiometricDisable: () -> Unit,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    Scaffold(bottomBar = {
        NavigationBar {
            bottomDestinations.forEach { item ->
                NavigationBarItem(
                    selected = current == item.route,
                    onClick = {
                        nav.navigate(item.route) {
                            launchSingleTop = true
                            popUpTo(AinoDestination.Dashboard.route)
                        }
                    },
                    icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(23.dp)) },
                    label = { Text(item.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = AinoDestination.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(AinoDestination.Dashboard.route) { HomeScreen(user, dashboard) }
            bottomDestinations.filterNot { it in setOf(AinoDestination.Dashboard, AinoDestination.More) }.forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination.label) }
            }
            composable(AinoDestination.More.route) {
                val updateUi by updates.ui.collectAsStateWithLifecycle()
                val context = LocalContext.current
                MoreScreen(
                    role,
                    hasReports,
                    updateUi,
                    realtimeState,
                    biometricAvailable,
                    biometricEnrolled,
                    { destination -> nav.navigate(destination.route) },
                    updates::check,
                    { updates.install(context) },
                    onBiometricEnroll,
                    onBiometricDisable,
                )
            }
            availableMoreDestinations(role, hasReports).forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination.label) }
            }
        }
    }
}

private val DASHBOARD_REFRESH_EVENTS = setOf(
    "task_assigned",
    "task_updated",
    "calendar_refresh",
    "meeting_updated",
    "meeting_cancelled",
)

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
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            AinoSectionHeader("More", "Workspace, security and application controls")
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column {
                    availableMoreDestinations(role, hasReports).forEach { destination ->
                        ListItem(
                            headlineContent = { Text(destination.label, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(destinationSubtitle(destination), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingContent = {
                                Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(9.dp)).padding(9.dp)) {
                                    Icon(destination.icon, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { onNavigate(destination) },
                        )
                    }
                }
            }
            AinoSectionHeader("System")
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (realtimeState == RealtimeState.Connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                            null,
                            tint = if (realtimeState == RealtimeState.Connected) app.aino.mobile.core.designsystem.theme.AinoSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("Realtime", style = MaterialTheme.typography.titleMedium)
                            Text(realtimeLabel(realtimeState), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        AinoBadge(if (realtimeState == RealtimeState.Connected) "Online" else "Offline", if (realtimeState == RealtimeState.Connected) AlertTone.Success else AlertTone.Warning)
                    }
                    when (val available = updateUi.available) {
                        null -> AinoPrimaryButton(if (updateUi.loading) "Checking…" else "Check for updates", onCheckUpdate, Modifier.fillMaxWidth(), !updateUi.loading, leadingIcon = { Icon(Icons.Outlined.SystemUpdate, null, Modifier.padding(end = 8.dp), tint = androidx.compose.ui.graphics.Color.White) })
                        else -> AinoPrimaryButton(if (updateUi.loading) "Downloading…" else "Install ${available.version}", onInstallUpdate, Modifier.fillMaxWidth(), !updateUi.loading, leadingIcon = { Icon(Icons.Outlined.SystemUpdate, null, Modifier.padding(end = 8.dp), tint = androidx.compose.ui.graphics.Color.White) })
                    }
                    updateUi.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (biometricAvailable) {
                AinoSectionHeader("Security")
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("Biometric sign-in", style = MaterialTheme.typography.titleMedium)
                                Text("Protected by Android Keystore", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            AinoBadge(if (biometricEnrolled) "Enabled" else "Off", if (biometricEnrolled) AlertTone.Success else AlertTone.Info)
                        }
                        AinoPrimaryButton(
                            if (biometricEnrolled) "Disable biometric sign-in" else "Enable biometric sign-in",
                            if (biometricEnrolled) onBiometricDisable else onBiometricEnroll,
                            Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Outlined.Security, null, Modifier.padding(end = 8.dp), tint = androidx.compose.ui.graphics.Color.White) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun destinationSubtitle(destination: AinoDestination): String = when (destination) {
    AinoDestination.Calendar -> "Events, meetings and schedule"
    AinoDestination.Notes -> "Daily notes and shared documents"
    AinoDestination.Organization -> "People, teams and structure"
    AinoDestination.Manager -> "Approvals and team activity"
    AinoDestination.Admin -> "Workspace configuration"
    AinoDestination.Tenants -> "Platform tenant management"
    AinoDestination.Profile -> "Account and preferences"
    else -> destination.label
}

private fun realtimeLabel(state: RealtimeState): String = when (state) {
    RealtimeState.Connected -> "Live updates are active"
    is RealtimeState.Connecting -> "Connecting · attempt ${state.attempt + 1}"
    is RealtimeState.Stopped -> state.reason
    RealtimeState.Disconnected -> "Connects after tenant sign-in"
}
