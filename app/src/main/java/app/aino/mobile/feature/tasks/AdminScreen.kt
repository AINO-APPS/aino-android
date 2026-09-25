package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** An Admin panel section (`pages/admin/index.tsx` `SECTIONS`) that exists on Android. */
data class AdminSection(val key: String, val label: String, val group: String, val icon: ImageVector)

/**
 * Only the sections Android implements; the rest of the web registry lands
 * with P10.1. Both require an org and the `agile` feature (`isAllowed`).
 */
private val ANDROID_ADMIN_SECTIONS = listOf(
    AdminSection("agile", "Agile Config", "Structure", Icons.Outlined.AccountTree),
    AdminSection("projects", "Projects", "Structure", Icons.Outlined.Folder),
)

private val ADMIN_GROUP_ORDER = listOf("Overview", "People", "Structure", "Operations", "Compliance", "Settings")

private val ADMIN_ROLES = setOf("hr_admin", "super_admin", "platform_admin")

fun canOpenAdmin(role: String?): Boolean = role in ADMIN_ROLES

/**
 * `isAllowed` for the ported sections: `requires: "orgId"` plus `feature: "agile"`.
 * [ungatedPlatformAdmin] mirrors the tenant-less platform admin who bypasses feature gates.
 */
fun allowedAdminSections(
    role: String?,
    orgId: Long?,
    features: Map<String, Boolean>,
    ungatedPlatformAdmin: Boolean = false,
): List<AdminSection> {
    if (!canOpenAdmin(role)) return emptyList()
    val agile = ungatedPlatformAdmin || features["agile"] == true
    return ANDROID_ADMIN_SECTIONS.filter { orgId != null && agile }
}

/** Sections grouped in the web's `GROUP_ORDER`. */
fun groupAdminSections(sections: List<AdminSection>): List<Pair<String, List<AdminSection>>> =
    ADMIN_GROUP_ORDER.mapNotNull { group -> sections.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it } }

/**
 * The Admin page at phone width: the web's mobile drawer (brand row + grouped,
 * collapsible nav) as the page itself; tapping a row opens that section.
 */
@Composable
fun AdminScreen(role: String, sections: List<AdminSection>, onOpen: (AdminSection) -> Unit) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (!canOpenAdmin(role)) {
            Text("Admin Panel", color = colors.text, fontSize = 2.rem, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(10.dp)).background(colors.cardBg)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(colors.danger))
                Text(
                    "Access denied. HR Admin, Super Admin, or Platform Admin role required.",
                    color = colors.textPrimary,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 9.6.dp, end = 9.6.dp, top = 5.6.dp, bottom = 13.6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp), tint = colors.textPrimary)
            Spacer(Modifier.width(8.8.dp))
            Column {
                Text("Admin Panel", color = colors.textPrimary, fontSize = 0.95.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.01.em)
                Text(
                    when (role) {
                        "platform_admin" -> "Platform Admin"
                        "super_admin" -> "Super Admin"
                        else -> "HR Admin"
                    },
                    color = colors.textSecondary,
                    fontSize = 0.72.rem,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(9.6.dp))
        groupAdminSections(sections).forEach { (group, items) ->
            var collapsed by rememberSaveable(group) { mutableStateOf(false) }
            Column(Modifier.padding(top = 10.4.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { collapsed = !collapsed }
                        .alpha(0.75f).padding(horizontal = 9.6.dp, vertical = 7.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(group.uppercase(), color = colors.textSecondary, fontSize = 0.68.rem, fontWeight = FontWeight.Bold, letterSpacing = 0.09.em)
                    Spacer(Modifier.width(5.6.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(12.dp).rotate(if (collapsed) -90f else 0f), tint = colors.textSecondary)
                }
                if (!collapsed) {
                    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(item) }
                                    .padding(horizontal = 11.2.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(item.icon, null, Modifier.size(16.dp), tint = colors.textSecondary)
                                Spacer(Modifier.width(9.6.dp))
                                Text(item.label, color = colors.textPrimary, fontSize = 0.88.rem, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
