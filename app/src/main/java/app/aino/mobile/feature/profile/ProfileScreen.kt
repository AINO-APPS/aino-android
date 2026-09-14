package app.aino.mobile.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileTabs(ui.tab, viewModel::selectTab)
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, AlertTone.Success) }
            when (ui.tab) {
                ProfileTab.Account -> AccountSection(ui, viewModel)
                ProfileTab.Search -> SearchSection(ui, viewModel)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ProfileTabs(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f), RoundedCornerShape(8.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ProfileTab.entries.forEach { tab ->
            Text(
                if (tab == ProfileTab.Account) "Profile" else "Search",
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
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(84.dp).padding(bottom = 0.dp), contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier.size(84.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        user.display().split(" ").take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Edit, "Edit profile", Modifier.size(13.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(user.display(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("@${user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            user.email?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AinoBadge(roleLabel(user.role), AlertTone.Info)
                user.teamName?.let { AinoBadge(it, AlertTone.Info) }
                user.tenantPlan?.let { AinoBadge("$it plan", AlertTone.Info) }
                if (user.hasReports) AinoBadge("Manager", AlertTone.Success)
                if (user.impersonated) AinoBadge("Impersonated", AlertTone.Warning)
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        ProfileAction(Icons.Outlined.Edit, "Edit Profile", viewModel::startEditing, !ui.loading)
    }

    ProfileAction(Icons.Outlined.Refresh, if (ui.loading) "Refreshing…" else "Refresh Profile", viewModel::refresh, !ui.loading)
}

@Composable
private fun ProfileAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .55f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SearchSection(ui: ProfileUiState, viewModel: ProfileViewModel) {
    OutlinedTextField(
        value = ui.searchTerm,
        onValueChange = viewModel::updateSearchTerm,
        placeholder = { Text("Search tasks, people, notes…") },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = if (ui.searchTerm.isNotEmpty()) {{
            Text("×", Modifier.clickable(onClick = viewModel::clearSearch).padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }} else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )

    if (!ui.searchRan && !ui.searching) {
        Column(
            Modifier.fillMaxWidth().height(280.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
            Text(
                if (ui.searchTerm.trim().isNotEmpty() && ui.searchTerm.trim().length < 2) "Type at least 2 characters to search" else "Search across tasks, people, and notes",
                Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
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
