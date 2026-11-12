package app.aino.mobile.core.update

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ClientUpgradeGate(
    updates: AppUpdater,
    upgrades: ClientUpgradeState = ClientUpgrades.state,
    content: @Composable () -> Unit,
) {
    val required by upgrades.required.collectAsState()
    if (required == null) {
        content()
        return
    }
    val update by updates.ui.collectAsState()
    val activity = LocalActivity.current
    BackHandler {}
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text("Update required", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Install the latest AINO app to continue. Your account and work are safe.")
            required?.minVersion?.let { Text("Required version: $it") }
            update.message?.let { Text(it) }
            Button(
                onClick = { activity?.let(updates::installRequired) },
                enabled = activity != null && !update.loading,
            ) {
                Text(if (update.readyToInstall) "Restart to update" else if (update.loading) "Checking for update…" else "Update now")
            }
        }
    }
}
