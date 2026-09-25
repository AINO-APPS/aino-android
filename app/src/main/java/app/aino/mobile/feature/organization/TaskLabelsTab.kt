package app.aino.mobile.feature.organization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private const val LABEL_MAX = 30

/** `pages/admin/TaskLabelsTab.tsx` (managers only). */
@Composable
internal fun TaskLabelsTab(ui: OrganizationUiState, viewModel: OrganizationViewModel) {
    val colors = LocalWebColors.current
    val section = ui.labels
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(DEFAULT_LABEL_COLOR) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var editColor by rememberSaveable { mutableStateOf(DEFAULT_LABEL_COLOR) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    if (section.initialLoading) {
        OrgLoading()
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocalOffer, null, Modifier.size(15.dp), tint = colors.text)
            Spacer(Modifier.width(6.dp))
            Text("Task Labels", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
        }
        Text(
            "Create labels that members of your organization can use to categorize tasks.",
            color = colors.textMuted,
            fontSize = 0.85.rem,
        )
        section.error?.let { OrgErrorText(it) }
        ui.notices[NoticeSlot.Labels]?.let { OrgNoticeBanner(it) }

        OrgRowCard {
            OrgFieldLabel("Label Name")
            OrgTextField(name, { name = it }, placeholder = "e.g. Bug, Feature, Urgent", maxLength = LABEL_MAX)
            OrgFieldLabel("Color")
            ColorPicker(color) { color = it }
            OrgButton(
                "Add Label",
                onClick = {
                    viewModel.createLabel(name, color) {
                        name = ""
                        color = DEFAULT_LABEL_COLOR
                    }
                },
                enabled = name.isNotBlank() && !ui.busy,
            )
        }

        val labels = section.data.orEmpty()
        if (labels.isEmpty()) {
            OrgEmpty("No labels yet. Create one above!")
        } else {
            labels.forEach { label ->
                OrgRowCard {
                    if (editId == label.id) {
                        OrgCellLabel("Label")
                        OrgTextField(editName, { editName = it }, placeholder = "", maxLength = LABEL_MAX)
                        ColorPicker(editColor) { editColor = it }
                    } else {
                        LabelBadge(label)
                    }
                    OrgCell("Created By", label.createdByUsername?.takeIf(String::isNotEmpty) ?: "—")
                    OrgButtonRow {
                        if (editId == label.id) {
                            OrgButton(
                                "Save",
                                onClick = { viewModel.updateLabel(label.id, editName, editColor) { editId = null } },
                                small = true,
                                enabled = editName.isNotBlank() && !ui.busy,
                            )
                            OrgButton("Cancel", onClick = { editId = null }, style = OrgButtonStyle.Secondary, small = true)
                        } else {
                            OrgButton(
                                "Edit",
                                onClick = {
                                    editId = label.id
                                    editName = label.name
                                    editColor = label.color
                                },
                                style = OrgButtonStyle.Secondary,
                                small = true,
                            )
                            OrgButton("Delete", onClick = { deleteId = label.id }, style = OrgButtonStyle.Danger, small = true, enabled = !ui.busy)
                        }
                    }
                }
            }
        }
    }

    deleteId?.let { id ->
        OrgConfirmDialog(
            title = "Delete Label",
            message = "Delete this label? It will be removed from all tasks. This cannot be undone.",
            confirmText = "Delete",
            onConfirm = {
                deleteId = null
                viewModel.deleteLabel(id)
            },
            onDismiss = { deleteId = null },
        )
    }
}

@Composable
private fun LabelBadge(label: TaskLabel) {
    Text(
        label.name,
        color = Color.White,
        fontSize = 0.8.rem,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(parseHexColor(label.color), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Preset swatches plus the `<input type="color">` equivalent as a hex field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(value: String, onChange: (String) -> Unit) {
    var hex by rememberSaveable(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_LABEL_COLORS.forEach { preset ->
                ColorDot(parseHexColor(preset), selected = value.equals(preset, ignoreCase = true)) { onChange(preset) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(parseHexColor(value), selected = false) {}
            Spacer(Modifier.width(8.dp))
            OrgTextField(
                hex,
                { next ->
                    val cleaned = ("#" + next.removePrefix("#").filter { it.isLetterOrDigit() }).take(7)
                    hex = cleaned
                    if (isHexColor(cleaned)) onChange(cleaned.lowercase())
                },
                placeholder = "#0ea5e9",
                modifier = Modifier.weight(1f),
            )
        }
    }
}
