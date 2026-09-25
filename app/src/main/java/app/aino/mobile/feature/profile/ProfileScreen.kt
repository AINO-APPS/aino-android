package app.aino.mobile.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.auth.AinoUser
import app.aino.mobile.core.designsystem.component.StatusDot
import app.aino.mobile.core.designsystem.component.StatusGlyph
import app.aino.mobile.core.designsystem.component.StatusGlyphIcon
import app.aino.mobile.core.designsystem.component.StatusVisual
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.component.profileStatusVisual
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** StatusPicker `PICKABLE_STATUSES` (`client/src/status/constants.ts` STATUS_META). */
private val PICKABLE = listOf(
    "available" to StatusVisual("Available", Color(0xFF22C55E), StatusGlyph.Check),
    "busy" to StatusVisual("Busy", Color(0xFFEF4444), StatusGlyph.Dot),
    "dnd" to StatusVisual("Do Not Disturb", Color(0xFFEF4444), StatusGlyph.Minus),
    "brb" to StatusVisual("Away", Color(0xFFF59E0B), StatusGlyph.Clock),
)

/** Server-derived statuses the picker shows read-only (STATUS_META `auto`). */
private val AUTO_LABELS = mapOf("away" to "Away (idle)", "in_call" to "In a Call", "in_meeting" to "In a Meeting")

/**
 * Profile page — the web's `ProfileMenu` dropdown content as a full screen:
 * header (photo + camera, name, @username, email, status/mode badges),
 * StatusPicker, then Edit Profile · Remove Photo · Notification Sounds ·
 * Face Enrollment · theme · Sign Out.
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    authUser: AinoUser,
    workState: String?,
    workMode: String?,
    isDark: Boolean,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onNotificationSounds: () -> Unit,
    onFaceEnrollment: () -> Unit,
    onToggleTheme: () -> Unit,
    onAvatarChanged: (String?) -> Unit,
    onSignOut: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.uploadAvatar(it, onAvatarChanged) }
    }
    val fullName = ui.user?.fullName ?: authUser.fullName
    val username = ui.user?.username ?: authUser.username
    val email = ui.user?.email ?: authUser.email
    val avatar = ui.user?.avatar ?: authUser.avatar
    val effective = ui.status?.effective ?: "available"
    val visual = profileStatusVisual(effective, workState, workMode)

    ProfilePage("Profile", onBack) {
        // ── Header ──
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(104.dp)) {
                UserAvatar(fullName ?: username, avatar, 96.dp, Modifier.align(Alignment.Center))
                if (ui.avatarUploading) {
                    Box(Modifier.size(96.dp).align(Alignment.Center).background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                    }
                }
                StatusDot(visual, 22.dp, colors.bg, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(34.dp).background(colors.primary, CircleShape)
                        .border(3.dp, colors.bg, CircleShape)
                        .clickable(enabled = !ui.avatarUploading) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.PhotoCamera, "Change photo", Modifier.size(16.dp), tint = Color.White) }
            }
            Text(fullName.orEmpty(), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("@$username", color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            email?.takeIf(String::isNotBlank)?.let {
                Text(it, color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(effective, visual)
                if (workState != null && workState != "logged_out") ModeBadge(workMode == "office")
            }
        }

        // ── StatusPicker ──
        StatusPickerSection(ui.status, ui.statusBusy, viewModel::setManualStatus, viewModel::toggleInvisible)

        // ── Menu ──
        ProfileSection {
            ProfileRow(Icons.Outlined.Edit, "Edit Profile", onEditProfile)
            if (!avatar.isNullOrBlank()) {
                RowDivider()
                ProfileRow(Icons.Outlined.Delete, "Remove Photo", { viewModel.askRemoveAvatar(true) })
            }
            RowDivider()
            ProfileRow(Icons.Outlined.NotificationsNone, "Notification Sounds", onNotificationSounds)
            RowDivider()
            ProfileRow(Icons.Outlined.Face, "Face Enrollment", onFaceEnrollment)
            RowDivider()
            ProfileRow(if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (isDark) "Light Mode" else "Dark Mode", onToggleTheme)
        }
        ProfileSection {
            ProfileRow(Icons.AutoMirrored.Outlined.Logout, "Sign Out", { viewModel.askSignOut(true) }, tint = colors.danger)
        }
    }

    if (ui.signOutConfirming) {
        ProfileConfirmDialog(
            title = "Sign Out",
            message = "Are you sure you want to sign out?",
            confirmText = "Sign Out",
            danger = true,
            busy = ui.signingOut,
            onConfirm = { viewModel.confirmSignOut(workState, workMode, onSignOut) },
            onCancel = { viewModel.askSignOut(false) },
        )
    }
    if (ui.removeAvatarConfirming) {
        ProfileConfirmDialog(
            title = "Remove Photo",
            message = "Are you sure you want to remove your profile photo?",
            confirmText = "Remove",
            danger = true,
            onConfirm = { viewModel.removeAvatar(onAvatarChanged) },
            onCancel = { viewModel.askRemoveAvatar(false) },
        )
    }
    ProfileAlert(ui.alert, viewModel::dismissAlert)
}

/** `.dd-status-badge.status-*` tints. */
@Composable
private fun StatusBadge(effective: String, visual: StatusVisual) {
    val colors = LocalWebColors.current
    val (fg, bg) = when (effective) {
        "available" -> Color(0xFF4ADE80) to Color(0xFF22C55E)
        "busy", "dnd", "in_call" -> Color(0xFFF87171) to Color(0xFFEF4444)
        "away" -> Color(0xFFFBBF24) to Color(0xFFF59E0B)
        "in_meeting" -> Color(0xFF38BDF8) to Color(0xFF0EA5E9)
        "offline" -> colors.textMuted to Color(0xFF94A3B8)
        else -> colors.textSecondary to Color.Transparent
    }
    Badge(visual.label, fg, bg) { StatusGlyphIcon(visual.glyph, fg, Modifier.size(9.dp)) }
}

/** `.dd-mode-office` / `.dd-mode-remote`. */
@Composable
private fun ModeBadge(office: Boolean) {
    val fg = if (office) Color(0xFF38BDF8) else Color(0xFFFBBF24)
    val bg = if (office) Color(0xFF0EA5E9) else Color(0xFFF59E0B)
    Badge(if (office) "Office" else "Remote", fg, bg) {
        Icon(if (office) Icons.Outlined.Apartment else Icons.Outlined.Home, null, Modifier.size(12.dp), tint = fg)
    }
}

@Composable
private fun Badge(label: String, fg: Color, bg: Color, leading: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.background(bg.copy(alpha = .12f), shape).border(1.dp, bg.copy(alpha = .2f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading()
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `StatusPicker.tsx` as an inline settings group. */
@Composable
private fun StatusPickerSection(
    status: StatusPayload?,
    busy: Boolean,
    onPick: (String) -> Unit,
    onToggleInvisible: () -> Unit,
) {
    val colors = LocalWebColors.current
    val effective = status?.effective ?: "available"
    val invisible = status?.presencePreference == "invisible"
    ProfileSection("Set status") {
        PICKABLE.forEachIndexed { index, (key, visual) ->
            if (index > 0) RowDivider()
            ProfileRow(
                icon = null,
                label = visual.label,
                onClick = { onPick(key) },
                enabled = !busy,
                leading = { PickerDot(visual) },
                trailing = if (effective == key && !invisible) {
                    { Icon(Icons.Outlined.Check, "Selected", Modifier.size(18.dp), tint = colors.primary) }
                } else null,
            )
        }
        RowDivider()
        ProfileRow(
            icon = null,
            label = if (invisible) "Stop appearing offline" else "Appear Offline",
            onClick = onToggleInvisible,
            enabled = !busy,
            leading = { PickerDot(StatusVisual("Offline", Color(0xFF64748B), StatusGlyph.Ring, ring = true)) },
            trailing = if (invisible) {
                { Icon(Icons.Outlined.Check, "Selected", Modifier.size(18.dp), tint = colors.primary) }
            } else null,
        )
        AUTO_LABELS[effective]?.let { label ->
            Text(
                "Status automatically set to \"$label\" — will revert when done.",
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun PickerDot(visual: StatusVisual) {
    Box(
        Modifier.size(20.dp).then(
            if (visual.ring) Modifier.border(1.5.dp, visual.color, CircleShape) else Modifier.background(visual.color, CircleShape),
        ),
        contentAlignment = Alignment.Center,
    ) { StatusGlyphIcon(visual.glyph, if (visual.ring) visual.color else Color.White, Modifier.size(12.dp)) }
}

