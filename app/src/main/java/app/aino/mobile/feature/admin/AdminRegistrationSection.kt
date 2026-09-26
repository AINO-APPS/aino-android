package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** Android-only page: `admin/registration-settings` mode + `admin/invite-codes` list/create/deactivate. */
@Composable
internal fun AdminRegistrationSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    val clipboard = LocalClipboardManager.current
    AdminRowCard {
        AdminTitle("Registration", "Who can create an account on the sign-up page.")
        AdminLoadState(state.registration) { reg ->
            AdminChipRow(REGISTRATION_MODES, reg.mode) { if (it != reg.mode) viewModel.setRegistrationMode(it) }
        }
    }
    var role by remember { mutableStateOf("employee") }
    var maxUses by remember { mutableStateOf("1") }
    var days by remember { mutableStateOf("") }
    AdminRowCard {
        AdminTitle("New invite code")
        AdminPicker("Role", AdminCatalog.INVITE_ROLES.map { it to AdminCatalog.roleLabel(it) }, role, { role = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminField("Max uses", maxUses, { maxUses = it }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number, hint = "0 = unlimited")
            AdminField("Expires in (days)", days, { days = it }, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number, hint = "Blank = never")
        }
        AdminButton("Create code", { viewModel.createInviteCode(role, maxUses, days) }, enabled = !state.busy, small = true)
        state.createdInvite?.let { inv ->
            Text(inv.code, color = colors.success, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 1.1.rem)
            AdminButton("Copy code", { clipboard.setText(AnnotatedString(inv.code)) }, style = AdminButtonStyle.Secondary, small = true)
        }
    }
    AdminLoadState(state.inviteCodes) { codes ->
        if (codes.isEmpty()) AdminEmpty("No invite codes")
        codes.forEach { c ->
            AdminRowCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.code, color = colors.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    AdminPill(if (c.isActive) "Active" else "Inactive", if (c.isActive) colors.success else colors.textMuted)
                }
                AdminCell("Role", AdminCatalog.roleLabel(c.role))
                AdminCell("Uses", "${c.usedCount ?: 0} / ${if ((c.maxUses ?: 0) == 0) "\u221e" else c.maxUses}")
                AdminCell("Expires", c.expiresAt?.let(::shortDateTime) ?: "Never")
                c.createdByName?.let { AdminCell("Created by", it) }
                if (c.isActive) {
                    AdminButtonRow {
                        AdminButton("Copy", { clipboard.setText(AnnotatedString(c.code)) }, style = AdminButtonStyle.Secondary, small = true)
                        AdminButton("Deactivate", { viewModel.deactivateInviteCode(c.id) }, style = AdminButtonStyle.Danger, enabled = !state.busy, small = true)
                    }
                }
            }
        }
    }
}
