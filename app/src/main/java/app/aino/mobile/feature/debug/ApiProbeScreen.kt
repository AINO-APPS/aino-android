package app.aino.mobile.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoScaffold
import app.aino.mobile.core.designsystem.AinoTopBar
import app.aino.mobile.core.designsystem.AlertTone

/** Debug-only screen that probes the 12 core endpoints (P0.3). */
@Composable
fun ApiProbeScreen(viewModel: ApiProbeViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AinoScaffold(
        topBar = { AinoTopBar("API Probe", subtitle = "Debug · core endpoint health") },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AinoPrimaryButton(
                text = if (ui.running) "Probing…" else "Run probe",
                onClick = viewModel::run,
                enabled = !ui.running,
            )
            ui.results.forEach { result ->
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                result.endpoint,
                                Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            AinoBadge(
                                if (result.status > 0) result.status.toString() else "ERR",
                                if (result.ok) AlertTone.Success else AlertTone.Error,
                            )
                        }
                        Text(
                            result.snippet,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
