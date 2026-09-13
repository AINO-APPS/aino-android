package app.aino.mobile.core.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun AinoApp(auth: AuthViewModel) {
    val ui by auth.ui.collectAsStateWithLifecycle()
    when (val state = ui.state) {
        AuthState.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AuthState.SignedOut -> LoginScreen(ui.loading, ui.error, ui.message, auth::login)
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
        is AuthState.Authenticated -> AuthenticatedShell(state.user.role, state.user.hasReports)
    }
}

@Composable
private fun AuthenticatedShell(role: String, hasReports: Boolean) {
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
                    icon = { Text(item.label.take(1)) },
                    label = { Text(item.label) },
                )
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = AinoDestination.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(AinoDestination.Dashboard.route) { HomeScreen() }
            bottomDestinations.filterNot { it in setOf(AinoDestination.Dashboard, AinoDestination.More) }.forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination.label) }
            }
            composable(AinoDestination.More.route) {
                Column(Modifier.fillMaxSize()) {
                    availableMoreDestinations(role, hasReports).forEach { destination ->
                        ListItem(
                            headlineContent = { Text(destination.label) },
                            modifier = Modifier.clickable { nav.navigate(destination.route) },
                        )
                    }
                }
            }
            availableMoreDestinations(role, hasReports).forEach { destination ->
                composable(destination.route) { PlaceholderScreen(destination.label) }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title is ready for its feature module")
    }
}
