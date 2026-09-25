package app.aino.mobile.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.network.NetworkConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WEEKDAY_OPTIONS = DAY_NAMES.mapIndexed { index, label -> index to label }
private val DATE_LABEL = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/**
 * `EventFormModal` as a full-screen route (`calendar/event`). A `Dialog` window
 * does not reliably receive navigation-bar / IME insets, which pushed the
 * Save / Close row off-screen; the shell's full-screen page handles both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventFormScreen(
    editor: EventEditor,
    tasks: List<CalendarTask>,
    meetingsEnabled: Boolean,
    isOrganizer: Boolean,
    viewModel: CalendarViewModel,
    onJoinMeeting: (String) -> Unit,
) {
    val colors = LocalWebColors.current
    val form = editor.form
    val creating = editor.creating
    val readOnly = !creating && editor.hasMeeting && !isOrganizer
    val now = LocalDateTime.now()
    val showTimes = !form.allDay || editor.addMeeting
    BackHandler(onBack = viewModel::closeEditor)

    AinoFullPage(
        title = if (creating) "New Event" else if (readOnly) "Event Details" else "Edit Event",
        onBack = viewModel::closeEditor,
        scrollable = false,
    ) {
            Text(
                when {
                    creating -> "Capture the details and keep your schedule in sync."
                    readOnly -> "Only the meeting organizer can edit or cancel this event."
                    else -> "Update details for this scheduled item."
                },
                color = colors.textMuted, fontSize = 0.76.rem, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FormLabel("Title")
                Field(form.title, { v -> viewModel.updateForm { f, _ -> f.copy(title = v) } }, "Event title", colors, enabled = !readOnly)
                FormLabel("Description")
                Field(form.description, { v -> viewModel.updateForm { f, _ -> f.copy(description = v) } }, "Optional description", colors, enabled = !readOnly, minLines = 2)

                FormLabel("Start")
                DateTimeRow(
                    date = form.start.toLocalDate(),
                    minDate = if (creating) now.toLocalDate() else null,
                    time = form.start.toLocalTime(),
                    showTime = showTimes,
                    enabled = !readOnly,
                    disabled = { startTimeDisabled(it, form, now, creating) },
                    onDate = { d -> viewModel.updateForm { f, n -> f.withStartDate(d, n, creating) } },
                    onTime = { t -> viewModel.updateForm { f, _ -> f.withStart(f.start.toLocalDate().atTime(t)) } },
                )
                FormLabel("End")
                DateTimeRow(
                    date = form.end.toLocalDate(),
                    minDate = form.start.toLocalDate(),
                    time = form.end.toLocalTime(),
                    showTime = showTimes,
                    enabled = !readOnly,
                    disabled = { endTimeDisabled(it, form, now, creating) },
                    onDate = { d -> viewModel.updateForm { f, n -> f.withEndDate(d, n, creating) } },
                    onTime = { t -> viewModel.updateForm { f, _ -> f.copy(end = f.end.toLocalDate().atTime(t)) } },
                )
                CheckRow(
                    checked = form.allDay,
                    enabled = !editor.addMeeting && !readOnly,
                    label = "All day",
                    hint = if (editor.addMeeting) "(disabled for meetings)" else null,
                ) { v -> viewModel.updateForm { f, _ -> f.copy(allDay = v) } }

                if (creating) {
                    FormLabel("Schedule")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton("Single day", form.scheduleMode != "multi") {
                            viewModel.updateForm { f, _ -> f.copy(scheduleMode = "single", weekdays = listOf(monDayIndex(f.start.toLocalDate()))) }
                        }
                        ModeButton("Custom days this week", form.scheduleMode == "multi") {
                            viewModel.updateForm { f, _ -> f.copy(scheduleMode = "multi", weekdays = f.weekdays.ifEmpty { listOf(monDayIndex(f.start.toLocalDate())) }) }
                        }
                    }
                    if (form.scheduleMode == "multi") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            WEEKDAY_OPTIONS.forEach { (value, label) ->
                                val selected = value in form.weekdays
                                ModeButton(label, selected) {
                                    viewModel.updateForm { f, _ ->
                                        // At least one day stays selected.
                                        if (selected && f.weekdays.size == 1) f
                                        else f.copy(weekdays = if (selected) f.weekdays - value else (f.weekdays + value).sorted())
                                    }
                                }
                            }
                        }
                    }
                }

                if (tasks.isNotEmpty()) {
                    FormLabel("Link to Task")
                    SelectField(
                        value = tasks.firstOrNull { it.id == form.taskId }?.title ?: "None",
                        options = listOf<Pair<Long?, String>>(null to "None") + tasks.map { it.id to it.title },
                        enabled = !readOnly,
                        onSelect = { id -> viewModel.updateForm { f, _ -> f.copy(taskId = id) } },
                    )
                }

                if (meetingsEnabled) {
                    if (editor.hasMeeting) MeetingBanner(editor, onJoinMeeting)
                    else if (creating) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .background(colors.bg).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Videocam, null, Modifier.size(16.dp), tint = colors.text)
                            Text("Add online meeting", color = colors.text, fontSize = 0.84.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp).weight(1f))
                            Switch(editor.addMeeting, { viewModel.toggleMeeting() }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                        }
                    }
                    if (creating && editor.addMeeting) MeetingOptions(editor, viewModel)
                }

                editor.error?.let { Text(it, color = colors.danger, fontSize = 0.82.rem) }
            }

            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!creating && isOrganizer) {
                    OutlinedButton(
                        onClick = viewModel::delete, enabled = !editor.saving, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
                    ) { Text(if (editor.hasMeeting) "Cancel Event" else "Delete") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::closeEditor, modifier = Modifier.weight(1f)) { Text("Close", color = colors.text) }
                    if (isOrganizer) {
                        Button(
                            onClick = viewModel::save,
                            enabled = form.title.isNotBlank() && !editor.saving,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = Color.White),
                        ) {
                            if (editor.saving) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Save")
                        }
                    }
                }
            }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text.uppercase(), color = LocalWebColors.current.textSecondary, fontSize = 0.74.rem, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 0.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, colors: WebColors, enabled: Boolean, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, minLines = minLines, singleLine = minLines == 1,
        placeholder = { Text(placeholder, color = colors.textMuted) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        colors = fieldColors(colors),
    )
}

@Composable
private fun fieldColors(colors: WebColors) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = colors.text, unfocusedTextColor = colors.text, disabledTextColor = colors.textSecondary,
    focusedBorderColor = colors.primary, unfocusedBorderColor = colors.border, disabledBorderColor = colors.border,
    focusedContainerColor = colors.bg, unfocusedContainerColor = colors.bg, disabledContainerColor = colors.bg,
    cursorColor = colors.primary,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(
    date: LocalDate,
    minDate: LocalDate?,
    time: LocalTime,
    showTime: Boolean,
    enabled: Boolean,
    disabled: (LocalTime) -> Boolean,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime) -> Unit,
) {
    var pickDate by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PickerBox(DATE_LABEL.format(date), enabled, Modifier.weight(1f)) { pickDate = true }
        if (showTime) {
            SelectField(
                value = timeLabel(time),
                options = timeOptions(time).map { it.time to it.label },
                enabled = enabled,
                isDisabled = disabled,
                modifier = Modifier.weight(1f),
                onSelect = onTime,
            )
        }
    }
    if (pickDate) {
        val minMillis = minDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = minMillis == null || utcTimeMillis >= minMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun PickerBox(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).border(1.dp, colors.border, shape).background(colors.bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = if (enabled) colors.text else colors.textSecondary, fontSize = 0.84.rem) }
}

@Composable
private fun <T> SelectField(
    value: String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    isDisabled: (T) -> Boolean = { false },
    onSelect: (T) -> Unit,
) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        PickerBox(value, enabled, Modifier.fillMaxWidth()) { open = true }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp).background(colors.bgElevated)) {
            options.forEach { (key, label) ->
                val off = isDisabled(key)
                DropdownMenuItem(
                    text = { Text(label, color = if (off) colors.textMuted else colors.text) },
                    enabled = !off,
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, label: String, hint: String? = null, onChange: (Boolean) -> Unit) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
        Text(label, color = colors.text, fontSize = 0.84.rem)
        hint?.let { Text("  $it", color = colors.textMuted, fontSize = 0.7.rem) }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(8.dp)
    Text(
        label, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold,
        color = if (active) Color.White else colors.textSecondary,
        modifier = Modifier.clip(shape).border(1.dp, if (active) colors.primary else colors.border, shape)
            .background(if (active) colors.primary else colors.bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetingBanner(editor: EventEditor, onJoinMeeting: (String) -> Unit) {
    val colors = LocalWebColors.current
    val code = editor.meetingCode ?: return
    val link = "${NetworkConfig.serverOrigin}/meeting/$code"
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .background(colors.bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Videocam, null, Modifier.size(16.dp), tint = colors.primary)
            Text("Online meeting", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.84.rem, modifier = Modifier.padding(start = 6.dp).weight(1f))
            Button(
                onClick = { onJoinMeeting(code) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = Color.White),
            ) { Text("Join") }
        }
        Text(
            link, color = colors.primary, fontSize = 0.78.rem, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { runCatching { uri.openUri(link) } },
        )
        if (editor.meetingParticipants.isNotEmpty()) {
            Text("Required", color = colors.textSecondary, fontSize = 0.72.rem, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                editor.meetingParticipants.filter { it.role == "organizer" || it.participantType == "required" }.forEach {
                    ParticipantBadge(it.display() + if (it.role == "organizer") " (organizer)" else "", optional = false)
                }
            }
            val optional = editor.meetingParticipants.filter { it.participantType == "optional" }
            if (optional.isNotEmpty()) {
                Text("Optional", color = colors.textSecondary, fontSize = 0.72.rem, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    optional.forEach { ParticipantBadge(it.display(), optional = true) }
                }
            }
        }
    }
}

@Composable
private fun ParticipantBadge(text: String, optional: Boolean) {
    val colors = LocalWebColors.current
    Text(
        text, color = if (optional) colors.textSecondary else colors.text, fontSize = 0.76.rem,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(colors.surface).border(1.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun MeetingOptions(editor: EventEditor, viewModel: CalendarViewModel) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .background(colors.bgHover).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FormLabel("Required participants")
        ParticipantPicker(PickerKind.Required, editor.required, editor, viewModel)
        FormLabel("Optional participants")
        ParticipantPicker(PickerKind.Optional, editor.optional, editor, viewModel)
        if (editor.conflicts.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.warning.copy(alpha = .12f)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = colors.warning)
                Column {
                    editor.conflicts.values.forEach { c ->
                        Text("${c.name} has a scheduling conflict: \"${c.events.firstOrNull()?.title.orEmpty()}\"", color = colors.text, fontSize = 0.78.rem)
                    }
                }
            }
        }
        CheckRow(editor.settings.muteOnJoin, true, "Mute participants on join") { viewModel.updateMeetingSettings(editor.settings.copy(muteOnJoin = it)) }
        CheckRow(editor.settings.allowScreenShare, true, "Allow screen sharing") { viewModel.updateMeetingSettings(editor.settings.copy(allowScreenShare = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantPicker(kind: PickerKind, participants: List<ParticipantUser>, editor: EventEditor, viewModel: CalendarViewModel) {
    val colors = LocalWebColors.current
    if (participants.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            participants.forEach { p ->
                val conflict = editor.conflicts[p.id]
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (conflict != null) colors.warning.copy(alpha = .15f) else colors.surface)
                        .border(1.dp, if (conflict != null) colors.warning else colors.border, RoundedCornerShape(999.dp))
                        .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (conflict != null) Icon(Icons.Outlined.WarningAmber, "conflict", Modifier.size(12.dp), tint = colors.warning)
                    Text(p.display(), color = colors.text, fontSize = 0.78.rem, modifier = Modifier.padding(horizontal = 4.dp))
                    Icon(
                        Icons.Outlined.Close, "Remove",
                        Modifier.size(20.dp).clip(RoundedCornerShape(999.dp)).clickable { viewModel.removeParticipant(kind, p.id) }.padding(4.dp),
                        tint = colors.textSecondary,
                    )
                }
            }
        }
    }
    OutlinedTextField(
        value = editor.queries[kind].orEmpty(),
        onValueChange = { viewModel.queryPeople(kind, it) },
        placeholder = { Text("Search people to invite…", color = colors.textMuted) },
        singleLine = true,
        trailingIcon = if (kind in editor.searching) {
            { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        colors = fieldColors(colors),
    )
    editor.results[kind]?.takeIf { it.isNotEmpty() }?.let { results ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.bgElevated).border(1.dp, colors.border, RoundedCornerShape(8.dp))) {
            results.forEach { user ->
                Row(Modifier.fillMaxWidth().clickable { viewModel.addParticipant(kind, user) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(user.display(), color = colors.text, fontSize = 0.84.rem)
                    user.email?.let { Text(" — $it", color = colors.textMuted, fontSize = 0.78.rem) }
                }
            }
        }
    }
}
