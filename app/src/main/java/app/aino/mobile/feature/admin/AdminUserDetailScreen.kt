package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** Which confirm dialog the detail page is showing. */
internal enum class UserDialog { None, ToggleActive, ResetPassword, ResetFace, Delete, RemoveFromOrg }

/** Roles the caller may assign: platform admins may grant super_admin; others stop below it. */
internal fun assignableRoles(callerRole: String): List<String> =
    if (callerRole == "platform_admin") AdminCatalog.ROLES else AdminCatalog.ROLES - "super_admin"

/**
 * `UserDrawer` as a full page: profile, role change (immediate for super+,
 * a request with reason otherwise), org/department/team/manager assignment,
 * activate/deactivate, password reset, face-enroll reset (Android-only action),
 * org invite / remove (Android-only actions) and delete (super_admin+).
 */
@Composable
fun AdminUserDetailScreen(viewModel: AdminViewModel, userId: Long, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { viewModel.loadUserDetail(userId) }
    val colors = LocalWebColors.current
    val loaded = state.user.data?.takeIf { it.id == userId }
    AinoFullPage(title = loaded?.fullName?.ifBlank { null } ?: "User", onBack = onBack) {
        state.notice?.let { AdminNoticeBanner(it) }
        AdminLoadState(state.user) { user ->
            if (user.id != userId) return@AdminLoadState
            var dialog by remember(user.id) { mutableStateOf(UserDialog.None) }
            AdminRowCard {
                Text(user.fullName, color = colors.text, fontSize = 1.1.rem, fontWeight = FontWeight.Bold)
                AdminCell("Username", "@${user.username}")
                AdminCell("Email", user.email ?: "\u2014")
                AdminCell("Role", AdminCatalog.roleLabel(user.role))
                AdminCell("Status", if (user.isActive) "Active" else "Inactive")
                if (state.isPlatformAdmin) AdminCell("Organization", user.orgName ?: "\u2014")
                AdminCell("Department", user.departmentName ?: "\u2014")
                AdminCell("Team", user.teamName ?: "\u2014")
                AdminCell("Manager", user.managerName ?: "\u2014")
                AdminCell("Joined", shortDate(user.createdAt))
            }
            val self = user.id == state.userId
            if (!self) {
                AdminUserRoleCard(state, user, viewModel)
                AdminUserAssignmentCard(state, user, viewModel)
            }
            AdminTitle("Actions")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = !state.busy
                if (!self) {
                    AdminButton(if (user.isActive) "Deactivate" else "Activate", { dialog = UserDialog.ToggleActive }, Modifier, AdminButtonStyle.Secondary, enabled)
                }
                AdminButton("Reset password", { dialog = UserDialog.ResetPassword }, Modifier, AdminButtonStyle.Secondary, enabled)
                AdminButton("Reset face enrollment", { dialog = UserDialog.ResetFace }, Modifier, AdminButtonStyle.Secondary, enabled)
                if (!self && user.orgId != null) {
                    AdminButton("Remove from organization", { dialog = UserDialog.RemoveFromOrg }, Modifier, AdminButtonStyle.Secondary, enabled)
                }
                if (!self && state.isSuperOrAbove) {
                    AdminButton("Delete user", { dialog = UserDialog.Delete }, Modifier, AdminButtonStyle.Danger, enabled)
                }
            }
            if (!self && user.orgId == null && state.orgId != null) AdminUserInviteCard(state, user, viewModel)
            AdminUserDialogs(dialog, user, viewModel, onDeleted = onBack) { dialog = UserDialog.None }
        }
    }
}
