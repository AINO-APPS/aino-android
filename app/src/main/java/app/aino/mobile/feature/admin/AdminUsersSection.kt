package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.delay

/** `UserManagement.tsx` role filter options. */
private val USER_ROLE_FILTER = listOf("" to "All roles") + (AdminCatalog.ROLES + "platform_admin").map { it to AdminCatalog.roleLabel(it) }
private val USER_STATUS_FILTER = listOf("" to "All statuses", "true" to "Active", "false" to "Inactive")

/** `UserManagement.tsx`: debounced search, role/status filters, saved views and paging. */
@Composable
internal fun AdminUsersSection(state: AdminUiState, viewModel: AdminViewModel, onOpenUser: (Long) -> Unit) {
    val colors = LocalWebColors.current
    val filters = state.userFilters
    var search by rememberSaveable { mutableStateOf(filters.search) }
    var view by rememberSaveable { mutableStateOf(UserView.All) }
    // Web debounces the search box by 300 ms before refetching.
    LaunchedEffect(search) {
        if (search == filters.search) return@LaunchedEffect
        delay(300)
        viewModel.setUserFilters(filters.copy(search = search))
    }
    AdminTextInput(search, { search = it }, "Search by name, username or email")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AdminPicker("", USER_ROLE_FILTER, filters.role, { viewModel.setUserFilters(filters.copy(role = it)) }, Modifier.weight(1f))
        AdminPicker("", USER_STATUS_FILTER, filters.status, { viewModel.setUserFilters(filters.copy(status = it)) }, Modifier.weight(1f))
    }
    AdminChipRow(UserView.entries.map { it to it.label }, view) { view = it }
    val pendingIds = state.roleRequests.data.orEmpty().filter { it.status == "pending" }.mapNotNull { it.targetUserId }.toSet()
    AdminLoadState(state.users) { page ->
        val shown = page.data.filter { view.matches(it, pendingIds) }
        Text(
            "${page.total} user${if (page.total == 1) "" else "s"}" + if (view != UserView.All) " · ${shown.size} in this view" else "",
            color = colors.textSecondary, fontSize = 0.8.rem,
        )
        if (shown.isEmpty()) AdminEmpty("No users found")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { user -> AdminUserCard(user, user.id in pendingIds) { onOpenUser(user.id) } }
        }
        val pages = ((page.total + USERS_PAGE_SIZE - 1) / USERS_PAGE_SIZE).coerceAtLeast(1)
        if (pages > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AdminButton("Previous", { viewModel.setUserPage(state.userPage - 1) }, style = AdminButtonStyle.Secondary, enabled = state.userPage > 1, small = true)
                Spacer(Modifier.weight(1f))
                Text("Page ${state.userPage} of $pages", color = colors.textSecondary, fontSize = 0.8.rem)
                Spacer(Modifier.weight(1f))
                AdminButton("Next", { viewModel.setUserPage(state.userPage + 1) }, style = AdminButtonStyle.Secondary, enabled = state.userPage < pages, small = true)
            }
        }
    }
}

@Composable
private fun AdminUserCard(user: AdminUser, rolePending: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    AdminRowCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(user.fullName.ifBlank { user.username }, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${user.username}" + (user.email?.let { " · $it" } ?: ""), color = colors.textSecondary, fontSize = 0.78.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AdminRolePill(user.role)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AdminPill(if (user.isActive) "Active" else "Inactive", if (user.isActive) colors.success else colors.danger)
            if (rolePending) AdminPill("Role pending", colors.warning)
            val where = listOfNotNull(user.departmentName, user.teamName).joinToString(" · ")
            if (where.isNotEmpty()) Text(where, color = colors.textMuted, fontSize = 0.75.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
