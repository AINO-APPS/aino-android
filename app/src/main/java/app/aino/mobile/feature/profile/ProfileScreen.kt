package app.aino.mobile.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone

@Composable
fun ProfileScreen(viewModel: ProfileViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AinoAtmosphere {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AinoSectionHeader("Profile", "Account details and workspace search")
            ProfileTabs(ui.tab, viewModel::selectTab)
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, AlertTone.Success) }
            when (ui.tab) {
                ProfileTab.Account -> AccountSection(ui, viewModel)
                ProfileTab.Search -> SearchSection(ui, viewModel)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProfileTabs(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ProfileTab.entries.forEach { tab ->
            Text(
                tab.name,
                Modifier.weight(1f).clickable { onSelect(tab) }
                    .background(if (selected == tab) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(vertical = 10.dp),
                color = if (selected == tab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AccountSection(ui: ProfileUiState, viewModel: ProfileViewModel) {
    val user = ui.user
    if (user == null) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Text(
                if (ui.loading) "Loading your profile…" else "Profile unavailable.",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        user.display().take(1).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(user.display(), style = MaterialTheme.typography.titleLarge)
                    Text("@${user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                AinoBadge(roleLabel(user.role), AlertTone.Info)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                user.teamName?.let { AinoBadge(it, AlertTone.Info) }
                user.tenantPlan?.let { AinoBadge("$it plan", AlertTone.Info) }
                if (user.hasReports) AinoBadge("Manager", AlertTone.Success)
                if (user.impersonated) AinoBadge("Impersonated", AlertTone.Warning)
            }
            user.email?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Face, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (ui.faceEnrolled) "Face descriptor enrolled" else "No face descriptor enrolled",
                    Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (ui.editing) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Edit details", style = MaterialTheme.typography.titleMedium)
                ProfileField("Full name", ui.draftName, onChange = { viewModel.updateDraft(name = it) })
                ProfileField("Username", ui.draftUsername, onChange = { viewModel.updateDraft(username = it) })
                AinoPrimaryButton("Save name and username", viewModel::saveProfile, Modifier.fillMaxWidth(), !ui.loading)
                // Email is a separate route with its own uniqueness check, so it
                // is saved independently rather than bundled into the same call.
                ProfileField("Email", ui.draftEmail, onChange = { viewModel.updateDraft(email = it) })
                AinoPrimaryButton("Save email", viewModel::saveEmail, Modifier.fillMaxWidth(), !ui.loading)
                Text(
                    "Cancel",
                    Modifier.clickable(onClick = viewModel::cancelEditing)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    } else {
        AinoPrimaryButton("Edit profile", viewModel::startEditing, Modifier.fillMaxWidth(), !ui.loading)
    }

    AinoPrimaryButton(
        if (ui.loading) "Refreshing…" else "Refresh profile",
        viewModel::refresh,
        Modifier.fillMaxWidth(),
        !ui.loading,
        leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.padding(end = 8.dp), tint = Color.White) },
    )
}

@Composable
private fun SearchSection(ui: ProfileUiState, viewModel: ProfileViewModel) {
    AinoGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileField(
                "Search tasks, notes, people…",
                ui.searchTerm,
                viewModel::updateSearchTerm,
                imeSearch = true,
                onCommit = viewModel::search,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AinoPrimaryButton(
                    if (ui.searching) "Searching…" else "Search",
                    viewModel::search,
                    Modifier.weight(1f),
                    !ui.searching,
                )
                Text(
                    "Clear",
                    Modifier.clickable(onClick = viewModel::clearSearch)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "Audit log results appear only for HR admins and above.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    val results = ui.searchResults
    if (ui.searchRan && results.isEmpty) {
        AinoGlassCard(Modifier.fillMaxWidth()) {
            Text("No matches.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (!ui.searchRan) return

    ResultGroup("Tasks", results.tasks.map { ResultRow(it.title, plainSnippet(it.snippet).ifBlank { it.status.orEmpty() }) })
    ResultGroup("Notes", results.notes.map { ResultRow(it.title, it.snippet.orEmpty()) })
    ResultGroup("People", results.users.map { ResultRow(it.display(), listOfNotNull(it.email, it.role?.let(::roleLabel)).joinToString(" · ")) })
    ResultGroup("Events", results.events.map { ResultRow(it.title, it.startTime?.take(16)?.replace('T', ' ').orEmpty()) })
    ResultGroup("Leaves", results.leaves.map { ResultRow("${it.leaveType} · ${it.date.take(10)}", it.status.orEmpty()) })
    ResultGroup("Sprints", results.sprints.map { ResultRow(it.name, it.goal ?: it.status.orEmpty()) })
    ResultGroup("Audit logs", results.logs.map { ResultRow("${it.action} ${it.entityType}", it.actorName.orEmpty()) })
}

private data class ResultRow(val title: String, val subtitle: String)

@Composable
private fun ResultGroup(title: String, rows: List<ResultRow>) {
    if (rows.isEmpty()) return
    AinoSectionHeader(title, "${rows.size} match(es)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold)
                    row.subtitle.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    imeSearch: Boolean = false,
    onCommit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = if (imeSearch) ImeAction.Search else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onSearch = { onCommit?.invoke() }),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    )
}
