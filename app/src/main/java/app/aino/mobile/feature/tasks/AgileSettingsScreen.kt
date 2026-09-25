package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * Admin → Agile Config (`pages/AgileSettings.tsx` + `AgileSettings.module.css`
 * at its ≤480px rules): single-column form rows and flags, horizontally
 * scrolling tables, scrollable tab strip.
 *
 * Deliberately not ported: row reordering. The web has no reorder control
 * (drag or otherwise) and the server's `PUT …/reorder` routes are shadowed by
 * `PUT …/:id`, so an up/down control would only ever fail. The Custom Fields
 * tab is P10.5, and the access-request / grant UI was removed from the web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgileSettingsScreen(viewModel: AgileSettingsViewModel, onBack: () -> Unit, onChanged: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val changed by rememberUpdatedState(onChanged)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AdminEvent.Toast -> android.widget.Toast.makeText(context, event.text, android.widget.Toast.LENGTH_SHORT).show()
                AdminEvent.Changed -> changed()
            }
        }
    }
    AinoFullPage("Agile Config", onBack = onBack, scrollable = false) {
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { viewModel.refresh(pull = true) }, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 60.dp),
            ) {
                if (ui.permsLoading) {
                    Text("Loading…", color = colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp))
                    return@Column
                }
                AgileHeader(ui)
                ui.permsError?.let {
                    Spacer(Modifier.height(8.dp))
                    ErrorMsg(it)
                }
                Spacer(Modifier.height(18.dp))
                AgileTabs(ui.tab, viewModel::selectTab)
                Spacer(Modifier.height(16.dp))
                if (ui.error.isNotEmpty()) {
                    AgileAlert(ui.error, colors.danger)
                    Spacer(Modifier.height(12.dp))
                }
                when (ui.tab) {
                    AgileTab.General -> GeneralTab(ui, viewModel)
                    AgileTab.Types -> TypesTab(ui, viewModel)
                    AgileTab.Workflow -> WorkflowTab(ui, viewModel)
                    AgileTab.Labels -> LabelsTab(ui, viewModel)
                }
            }
        }
    }
    ui.confirm?.let { BrowserConfirmDialog(it, viewModel::dismissConfirm) }
}

@Composable
private fun AgileHeader(ui: AgileSettingsUiState) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text("Agile Configuration", color = colors.text, fontSize = 1.2.rem, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Customise your team's work item types, workflow states and estimation rules.", color = colors.textMuted, fontSize = 0.78.rem)
        }
        val (icon, text, fg, bg) = if (ui.perms.canEdit) {
            Quad(Icons.Outlined.VerifiedUser, ui.perms.editorLabel(), colors.success, colors.success.copy(alpha = 0.14f))
        } else {
            Quad(Icons.Outlined.Lock, "Read-only", colors.textMuted, colors.bgSecondary)
        }
        Row(
            Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
            Text(text, color = fg, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Quad(val icon: ImageVector, val text: String, val fg: Color, val bg: Color)

@Composable
private fun AgileTabs(selected: AgileTab, onSelect: (AgileTab) -> Unit) {
    val colors = LocalWebColors.current
    val icons = mapOf(
        AgileTab.General to Icons.Outlined.Settings,
        AgileTab.Types to Icons.Outlined.Layers,
        AgileTab.Workflow to Icons.Outlined.AccountTree,
        AgileTab.Labels to Icons.Outlined.LocalOffer,
    )
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AgileTab.entries.forEach { tab ->
                val active = tab == selected
                val tint = if (active) colors.primary else colors.textMuted
                Column(Modifier.width(IntrinsicSize.Max).clickable { onSelect(tab) }) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icons.getValue(tab), null, Modifier.size(14.dp), tint = tint)
                        Spacer(Modifier.width(6.dp))
                        Text(tab.label, color = tint, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) colors.primary else Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun AgileAlert(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 0.85.rem,
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** `.card` at ≤900px. */
@Composable
private fun AgileCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String, first: Boolean = false) {
    Text(
        text.uppercase(),
        color = LocalWebColors.current.text,
        fontSize = 0.85.rem,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = if (first) 0.dp else 18.dp, bottom = 10.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionHead(title: String, canEdit: Boolean, adding: Boolean, addText: String, onToggle: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(), color = LocalWebColors.current.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        if (canEdit) WebButton(if (adding) "Cancel" else addText, onToggle, style = BtnStyle.Primary, small = true, icon = Icons.Outlined.Add)
    }
}

@Composable
private fun HelpText(text: AnnotatedString) {
    Text(text, color = LocalWebColors.current.textMuted, fontSize = 0.82.rem, modifier = Modifier.padding(bottom = 16.dp))
}

private val bold = SpanStyle(fontWeight = FontWeight.Bold)
private val italic = SpanStyle(fontStyle = FontStyle.Italic)

// ─── General tab ───────────────────────────────────────────────────────────

@Composable
private fun GeneralTab(ui: AgileSettingsUiState, viewModel: AgileSettingsViewModel) {
    val colors = LocalWebColors.current
    val settings = ui.settings
    if (settings == null) {
        ui.settingsError?.let { ErrorMsg(it) }
            ?: Text("Loading…", color = colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp))
        return
    }
    val canEdit = ui.canEdit
    AgileCard {
        ui.settingsError?.let { ErrorMsg(it, Modifier.padding(bottom = 12.dp)) }
        SectionTitle("Estimation", first = true)
        FormRow("Scale type") {
            WebSelect(ESTIMATION_OPTIONS, settings.estimationType, viewModel::setEstimationType, Modifier.fillMaxWidth(), enabled = canEdit)
        }
        FormRow("Scale values (comma-separated)") {
            AdminInput(ui.valuesText, viewModel::setValuesText, Modifier.fillMaxWidth(), enabled = canEdit && settings.estimationType != "none")
        }
        FormRow("Unit label") {
            AdminInput(
                settings.estimationUnitLabel.orEmpty(),
                { v -> viewModel.editSettings { it.copy(estimationUnitLabel = v) } },
                Modifier.fillMaxWidth(),
                placeholder = "SP",
                enabled = canEdit,
            )
        }
        SectionTitle("Features")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            AGILE_FEATURE_FLAGS.forEach { (key, label) ->
                AdminCheckbox(
                    settings.flag(key),
                    { on -> viewModel.editSettings { it.withFlag(key, on) } },
                    label,
                    Modifier.fillMaxWidth().background(colors.bgSecondary, RoundedCornerShape(6.dp)).padding(horizontal = 2.dp),
                    enabled = canEdit,
                )
            }
        }
        SectionTitle("Definition of Done (default)")
        AdminInput(
            settings.defaultDod.orEmpty(),
            { v -> viewModel.editSettings { it.copy(defaultDod = v) } },
            Modifier.fillMaxWidth(),
            placeholder = "- Code reviewed\n- Tests pass\n- Documentation updated",
            enabled = canEdit,
            singleLine = false,
            minLines = 6,
        )
        Spacer(Modifier.height(18.dp))
        WebButton(
            if (ui.saving) "Saving…" else "Save Settings",
            viewModel::saveSettings,
            Modifier.fillMaxWidth(),
            style = BtnStyle.Primary,
            enabled = canEdit && !ui.saving,
            icon = Icons.Outlined.Save,
        )
    }
}

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = LocalWebColors.current.textMuted, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold)
        content()
    }
}

// ─── Tables (≤900px: horizontal scroll, nowrap) ────────────────────────────

private data class Col(val title: String, val width: Dp)

@Composable
private fun AgileTable(columns: List<Col>, rows: @Composable (List<Dp>) -> Unit) {
    val colors = LocalWebColors.current
    Column(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(colors.bgSecondary)) {
            columns.forEach { col ->
                Text(
                    col.title.uppercase(),
                    color = colors.textMuted,
                    fontSize = 0.7.rem,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    modifier = Modifier.width(col.width).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.width(columns.fold(0.dp) { acc, c -> acc + c.width }).height(1.dp).background(colors.border))
        rows(columns.map { it.width })
    }
}

@Composable
private fun TableRow(widths: List<Dp>, cells: List<@Composable () -> Unit>) {
    val colors = LocalWebColors.current
    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, cell ->
            Box(Modifier.width(widths[i]).padding(horizontal = 8.dp, vertical = 6.dp)) { cell() }
        }
    }
    Box(Modifier.width(widths.fold(0.dp) { acc, w -> acc + w }).height(1.dp).background(colors.border))
}

@Composable
private fun CellCheckbox(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalWebColors.current
    Checkbox(
        checked = checked,
        onCheckedChange = if (enabled) onChange else null,
        enabled = enabled,
        modifier = Modifier.size(28.dp),
        colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted),
    )
}

/** `.cellInput`: edits locally and saves on blur / Done when the text changed. */
@Composable
private fun CommitInput(serverValue: String, enabled: Boolean, onCommit: (String) -> Unit, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text) {
    var text by remember(serverValue) { mutableStateOf(serverValue) }
    AdminInput(
        text,
        { text = it },
        modifier,
        enabled = enabled,
        keyboardType = keyboardType,
        padding = 4.dp,
        onFocusLost = { if (text != serverValue) onCommit(text) },
    )
}

@Composable
private fun DeleteCell(canEdit: Boolean, onDelete: () -> Unit) {
    if (canEdit) {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(LocalWebColors.current.danger).clickable(onClickLabel = "Delete", onClick = onDelete)
                .padding(horizontal = 9.6.dp, vertical = 5.dp),
        ) { Icon(Icons.Outlined.Delete, "Delete", Modifier.size(13.dp), tint = Color.White) }
    }
}

/** `.inlineForm`: dashed, wrapping row of inputs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineForm(content: @Composable RowScope.() -> Unit) {
    val colors = LocalWebColors.current
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .background(colors.bgSecondary, RoundedCornerShape(8.dp))
            .drawBehind {
                drawRoundRect(
                    colors.border,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                )
            }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

// ─── Types tab ─────────────────────────────────────────────────────────────

private val TYPE_COLUMNS = listOf(
    Col("Type", 150.dp), Col("Color", 150.dp), Col("Default", 72.dp), Col("Epic", 60.dp), Col("Active", 64.dp), Col("Actions", 72.dp),
)

@Composable
private fun TypesTab(ui: AgileSettingsUiState, viewModel: AgileSettingsViewModel) {
    val canEdit = ui.canEdit
    AgileCard {
        SectionHead("Work Item Types", canEdit, ui.addingType, "Add Type", viewModel::toggleAddingType)
        HelpText(
            buildAnnotatedString {
                withStyle(bold) { append("Work Item Types") }
                append(" classify ")
                withStyle(italic) { append("what kind") }
                append(" of work a ticket is — each ticket has exactly one type (Story / Bug / Task / Epic / Spike / etc.).\n")
                withStyle(bold) { append("Labels") }
                append(" (managed in the ")
                withStyle(italic) { append("Labels") }
                append(" tab here) are free-form tags — a ticket can have many, and they're typically used for cross-cutting concerns like \"frontend\", \"tech-debt\", \"needs-design\", \"Q4-OKR\".")
            },
        )
        ui.typesError?.let { ErrorMsg(it, Modifier.padding(bottom = 12.dp)) }
        if (ui.addingType && canEdit) {
            val form = ui.typeForm
            InlineForm {
                AdminInput(form.name, { v -> viewModel.editTypeForm { it.copy(name = v) } }, Modifier.width(160.dp), placeholder = "Name (e.g. Spike)", fontSize = 0.82.rem, padding = 6.dp)
                HexColorInput(form.color, { c -> viewModel.editTypeForm { it.copy(color = c) } })
                AdminInput(form.icon, { v -> viewModel.editTypeForm { it.copy(icon = v) } }, Modifier.width(120.dp), placeholder = "Icon name", fontSize = 0.82.rem, padding = 6.dp)
                AdminCheckbox(form.isEpic, { on -> viewModel.editTypeForm { it.copy(isEpic = on) } }, "Epic", fontSize = 0.8.rem)
                AdminCheckbox(form.isDefault, { on -> viewModel.editTypeForm { it.copy(isDefault = on) } }, "Default", fontSize = 0.8.rem)
                WebButton("Create", viewModel::submitType, style = BtnStyle.Primary, small = true)
            }
        }
        AgileTable(TYPE_COLUMNS) { widths ->
            ui.types.orEmpty().forEach { t ->
                TableRow(
                    widths,
                    listOf(
                        { CommitInput(t.name, canEdit, { v -> viewModel.updateType(t.id, patchOf("name", v)) }, Modifier.fillMaxWidth()) },
                        { HexColorInput(t.color, { c -> viewModel.updateType(t.id, patchOf("color", c)) }, enabled = canEdit) },
                        { CellCheckbox(t.isDefault, canEdit) { viewModel.updateType(t.id, patchOf("is_default", it)) } },
                        { CellCheckbox(t.isEpic, canEdit) { viewModel.updateType(t.id, patchOf("is_epic", it)) } },
                        { CellCheckbox(t.isActive, canEdit) { viewModel.updateType(t.id, patchOf("is_active", it)) } },
                        { DeleteCell(canEdit) { viewModel.deleteType(t) } },
                    ),
                )
            }
        }
    }
}

// ─── Workflow tab ──────────────────────────────────────────────────────────

private val STATE_COLUMNS = listOf(
    Col("Name", 150.dp), Col("Color", 150.dp), Col("WIP", 76.dp), Col("Initial", 64.dp),
    Col("Terminal", 76.dp), Col("Active", 64.dp), Col("Actions", 72.dp),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkflowTab(ui: AgileSettingsUiState, viewModel: AgileSettingsViewModel) {
    val colors = LocalWebColors.current
    val canEdit = ui.canEdit
    AgileCard {
        SectionHead("Workflow States", canEdit, ui.addingState, "Add State", viewModel::toggleAddingState)
        HelpText(
            buildAnnotatedString {
                append("Every workflow must keep ")
                withStyle(bold) { append("at least one active state in each of the 4 categories") }
                append(": Open, In Progress, In Review, Done. You cannot delete a state that still has tasks in it.")
            },
        )
        Glossary()
        ui.statesError?.let { ErrorMsg(it, Modifier.padding(bottom = 12.dp)) }
        if (ui.addingState && canEdit) {
            val form = ui.stateForm
            InlineForm {
                AdminInput(form.name, { v -> viewModel.editStateForm { it.copy(name = v) } }, Modifier.width(160.dp), placeholder = "Name (e.g. Triage)", fontSize = 0.82.rem, padding = 6.dp)
                WebSelect(WORKFLOW_CATEGORIES.map { it.key to it.label }, form.category, { c -> viewModel.editStateForm { it.copy(category = c) } }, Modifier.width(160.dp), fontSize = 0.82.rem)
                HexColorInput(form.color, { c -> viewModel.editStateForm { it.copy(color = c) } })
                AdminInput(
                    form.wipLimit,
                    { v -> viewModel.editStateForm { it.copy(wipLimit = v.filter(Char::isDigit)) } },
                    Modifier.width(80.dp),
                    placeholder = "WIP",
                    keyboardType = KeyboardType.Number,
                    fontSize = 0.82.rem,
                    padding = 6.dp,
                )
                AdminCheckbox(form.isInitial, { on -> viewModel.editStateForm { it.copy(isInitial = on) } }, "Initial", fontSize = 0.8.rem)
                AdminCheckbox(form.isTerminal, { on -> viewModel.editStateForm { it.copy(isTerminal = on) } }, "Terminal", fontSize = 0.8.rem)
                WebButton("Create", viewModel::submitState, style = BtnStyle.Primary, small = true)
            }
        }
        val grouped = groupStatesByCategory(ui.states.orEmpty())
        WORKFLOW_CATEGORIES.forEach { cat ->
            val catColor = hexColor(cat.color)
            Column(Modifier.padding(top = 18.dp)) {
                Row(Modifier.padding(top = 12.dp, bottom = 4.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(catColor))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(10.dp).background(catColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(cat.label, color = colors.textMuted, fontSize = 0.85.rem, fontWeight = FontWeight.SemiBold)
                }
                val rows = grouped[cat.key].orEmpty()
                if (rows.isEmpty()) {
                    Text(
                        "⚠ No active state in this category — add one to keep reporting accurate.",
                        color = colors.warning,
                        fontSize = 0.78.rem,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                            .background(colors.warning.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                AgileTable(STATE_COLUMNS) { widths ->
                    rows.forEach { t ->
                        TableRow(
                            widths,
                            listOf(
                                { CommitInput(t.name, canEdit, { v -> viewModel.updateState(t.id, patchOf("name", v)) }, Modifier.fillMaxWidth()) },
                                { HexColorInput(t.color, { c -> viewModel.updateState(t.id, patchOf("color", c)) }, enabled = canEdit) },
                                {
                                    CommitInput(
                                        t.wipText(),
                                        canEdit,
                                        { v -> viewModel.updateState(t.id, patchOf("wip_limit", if (v.isBlank()) null else parseWip(v))) },
                                        Modifier.width(56.dp),
                                        keyboardType = KeyboardType.Number,
                                    )
                                },
                                { CellCheckbox(t.isInitial, canEdit) { viewModel.updateState(t.id, patchOf("is_initial", it)) } },
                                { CellCheckbox(t.isTerminal, canEdit) { viewModel.updateState(t.id, patchOf("is_terminal", it)) } },
                                { CellCheckbox(t.isActive, canEdit) { viewModel.updateState(t.id, patchOf("is_active", it)) } },
                                { DeleteCell(canEdit) { viewModel.deleteState(t) } },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Glossary() {
    val colors = LocalWebColors.current
    val strong = SpanStyle(fontWeight = FontWeight.Bold, color = colors.text)
    val entries = listOf(
        buildAnnotatedString {
            withStyle(strong) { append("WIP") }
            append(" — ")
            withStyle(italic) { append("Work In Progress") }
            append(" limit. Maximum number of tickets allowed in this column at the same time. The board badge turns red when exceeded so the team can swarm and unblock work instead of starting more. Leave blank for no limit.")
        },
        buildAnnotatedString {
            withStyle(strong) { append("Initial") }
            append(" — The starting state for any newly created ticket. Exactly one state across the whole workflow should be marked as initial (typically ")
            withStyle(italic) { append("To Do") }
            append(").")
        },
        buildAnnotatedString {
            withStyle(strong) { append("Terminal") }
            append(" — A \"finished\" state. Tickets in a terminal state are counted as completed for sprint progress, burndown and velocity. Mark ")
            withStyle(italic) { append("Done") }
            append(" (and any other closing state like ")
            withStyle(italic) { append("Cancelled") }
            append(" or ")
            withStyle(italic) { append("Won't Fix") }
            append(") as terminal.")
        },
        buildAnnotatedString {
            withStyle(strong) { append("Active") }
            append(" — Whether the state is shown on the board. Uncheck to retire a state without deleting historical references to it.")
        },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgSecondary)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { Text(it, color = colors.textMuted, fontSize = 0.78.rem, lineHeight = (0.78 * 16 * 1.45).sp) }
        }
    }
}

// ─── Labels tab (TaskLabelsTab) ────────────────────────────────────────────

private const val LABEL_MAX = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelsTab(ui: AgileSettingsUiState, viewModel: AgileSettingsViewModel) {
    val colors = LocalWebColors.current
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(DEFAULT_MANAGED_LABEL_COLOR) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var editColor by rememberSaveable { mutableStateOf(DEFAULT_MANAGED_LABEL_COLOR) }
    AgileCard {
        SectionTitle("Task Labels", first = true)
        HelpText(
            buildAnnotatedString {
                withStyle(bold) { append("Labels") }
                append(" are free-form tags used for cross-cutting filtering and reporting (e.g. ")
                listOf("frontend", "tech-debt", "customer-X").forEach { withStyle(italic) { append(it) }; append(", ") }
                withStyle(italic) { append("Q4-OKR") }
                append("). A ticket can carry many labels, but only one Work Item Type.")
            },
        )
        val labels = ui.labels
        if (labels == null) {
            WebSpinner()
            return@AgileCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalOffer, null, Modifier.size(15.dp), tint = colors.text)
                Spacer(Modifier.width(6.dp))
                Text("Task Labels", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
            }
            Text("Create labels that members of your organization can use to categorize tasks.", color = colors.textMuted, fontSize = 0.85.rem)
            ui.labelsError?.let { ErrorMsg(it) }
            ui.labelNotice?.let { notice -> if (notice.ok) AgileAlert(notice.text, colors.success) else ErrorMsg(notice.text) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormRow("Label Name") {
                    AdminInput(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Bug, Feature, Urgent", maxLength = LABEL_MAX)
                }
                FormRow("Color") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ColorChips(MANAGED_LABEL_COLORS, color, { color = it }, ring = colors.text)
                        HexColorInput(color, { color = it })
                    }
                }
                WebButton(
                    "Add Label",
                    { viewModel.createLabel(name, color) { name = ""; color = DEFAULT_MANAGED_LABEL_COLOR } },
                    style = BtnStyle.Primary,
                    enabled = !ui.labelBusy,
                )
            }

            if (labels.isEmpty()) {
                Text("No labels yet. Create one above!", color = colors.textMuted, fontSize = 0.85.rem, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
            } else {
                AgileTable(listOf(Col("Label", 170.dp), Col("Created By", 120.dp), Col("Actions", 150.dp))) { widths ->
                    labels.forEach { label ->
                        val editing = editId == label.id
                        TableRow(
                            widths,
                            listOf(
                                {
                                    if (editing) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AdminInput(editName, { editName = it }, Modifier.fillMaxWidth(), maxLength = LABEL_MAX, padding = 5.dp)
                                            HexColorInput(editColor, { editColor = it })
                                        }
                                    } else {
                                        Text(
                                            label.name,
                                            color = Color.White,
                                            fontSize = 0.8.rem,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.background(hexColor(label.color), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                },
                                { Text(label.createdByUsername?.takeIf(String::isNotEmpty) ?: "—", color = colors.textMuted, fontSize = 0.8.rem) },
                                {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (editing) {
                                            WebButton("Save", { viewModel.updateLabel(label.id, editName, editColor) { editId = null } }, style = BtnStyle.Primary, small = true, enabled = !ui.labelBusy)
                                            WebButton("Cancel", { editId = null }, small = true)
                                        } else {
                                            WebButton("Edit", { editId = label.id; editName = label.name; editColor = label.color }, small = true)
                                            WebButton("Delete", { viewModel.requestDeleteLabel(label.id) }, style = BtnStyle.Danger, small = true)
                                        }
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
