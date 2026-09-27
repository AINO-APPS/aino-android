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
import app.aino.mobile.core.designsystem.icons.HeroIcons

/**
 * An Admin panel section (`pages/admin/index.tsx` `SECTIONS`) that exists on Android.
 * [requires] is the web's `requires` (`orgId` | `super` | `approver`) plus the
 * Android-only `platform`; [feature] is the tenant feature gate.
 */
data class AdminSection(
    val key: String,
    val label: String,
    val group: String,
    val icon: ImageVector,
    val requires: String? = null,
    val feature: String? = null,
)

/**
 * The web registry in its order, limited to what Android implements, plus the
 * Android-only pages for endpoints the web UI never calls (Organizations,
 * Task Labels, Registration & Invites, Announcements).
 */
private val ANDROID_ADMIN_SECTIONS = listOf(
    AdminSection("home", "Home", "Overview", HeroIcons.Home),
    AdminSection("users", "Users", "People", HeroIcons.Users),
    AdminSection("add", "Add People", "People", HeroIcons.UserPlus),
    AdminSection("role-requests", "Role Requests", "People", HeroIcons.ArrowPath),
    AdminSection("departments", "Departments", "Structure", HeroIcons.BuildingOffice, requires = "orgId"),
    AdminSection("teams", "Teams", "Structure", HeroIcons.UserGroup, requires = "orgId"),
    AdminSection("org-chart", "Org Chart", "Structure", HeroIcons.RectangleGroup, requires = "orgId"),
    AdminSection("agile", "Agile Config", "Structure", HeroIcons.ViewColumns, requires = "orgId", feature = "agile"),
    AdminSection("projects", "Projects", "Structure", HeroIcons.Folder, requires = "orgId", feature = "agile"),
    AdminSection("organizations", "Organizations", "Structure", HeroIcons.BuildingOffice2, requires = "platform"),
    AdminSection("task-labels", "Task Labels", "Structure", HeroIcons.Tag),
    AdminSection("payroll", "Payroll Periods", "Operations", HeroIcons.Banknotes, feature = "payroll"),
    AdminSection("compensation", "Compensation", "Operations", HeroIcons.Wallet, requires = "orgId", feature = "payroll"),
    AdminSection("salary-slips", "Salary Slips", "Operations", HeroIcons.ReceiptPercent, requires = "orgId", feature = "payroll"),
    AdminSection("payment-config", "Payment Settings", "Operations", HeroIcons.CreditCard, requires = "orgId", feature = "payroll"),
    AdminSection("audit", "Audit Logs", "Compliance", HeroIcons.Clock),
    AdminSection("org-settings", "Org Settings", "Settings", HeroIcons.Cog6Tooth, requires = "orgId"),
    AdminSection("registration", "Registration & Invites", "Settings", HeroIcons.Key),
    AdminSection("announcements", "Announcements", "Settings", HeroIcons.Megaphone, requires = "super"),
)

private val ADMIN_GROUP_ORDER = listOf("Overview", "People", "Structure", "Operations", "Compliance", "Settings")

private val ADMIN_ROLES = setOf("hr_admin", "super_admin", "platform_admin")

fun canOpenAdmin(role: String?): Boolean = role in ADMIN_ROLES

/**
 * Web `isAllowed`: the feature gate first, then `requires`.
 * [ungatedPlatformAdmin] mirrors the tenant-less platform admin who bypasses feature gates.
 */
fun allowedAdminSections(
    role: String?,
    orgId: Long?,
    features: Map<String, Boolean>,
    ungatedPlatformAdmin: Boolean = false,
): List<AdminSection> {
    if (!canOpenAdmin(role)) return emptyList()
    return ANDROID_ADMIN_SECTIONS.filter { section ->
        val featureOk = section.feature == null || ungatedPlatformAdmin || features[section.feature] == true
        featureOk && when (section.requires) {
            null -> true
            "orgId" -> orgId != null
            "super" -> role == "super_admin" || role == "platform_admin"
            "platform" -> role == "platform_admin"
            "approver" -> role in ADMIN_ROLES
            else -> true
        }
    }
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
            Icon(HeroIcons.Cog6Tooth, null, Modifier.size(18.dp), tint = colors.textPrimary)
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
                    Icon(HeroIcons.ChevronDown, null, Modifier.size(12.dp).rotate(if (collapsed) -90f else 0f), tint = colors.textSecondary)
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
