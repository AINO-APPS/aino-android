package app.aino.mobile.feature.organization

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private enum class ChartView { Dept, Tree }

/** `components/organization/OrgChartView.tsx`. */
@Composable
internal fun OrgChartTab(section: Section<OrgChart>) {
    val colors = LocalWebColors.current
    var view by rememberSaveable { mutableStateOf(ChartView.Dept) }
    var search by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<ChartMember?>(null) }

    section.error?.let {
        OrgErrorText(it)
        VSpace(12)
    }
    val chart = section.data
    if (chart == null) {
        if (section.loading || section.error == null) OrgLoading()
        return
    }
    val filtered = remember(chart, search) { filterChartMembers(chart.members, search) }
    val tree = remember(chart) { buildReportingTree(chart.members) }
    val total = if (view == ChartView.Dept) filtered.size else chart.members.size

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ViewToggle(view) { view = it }
        OrgTextField(
            search,
            { search = it },
            placeholder = "Filter by name, role, or manager…",
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) },
            trailingIcon = if (search.isNotEmpty()) {
                { IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(16.dp)) } }
            } else null,
        )
        Text(plural(total, "member"), color = colors.textSecondary, fontSize = 0.8.rem)

        when (view) {
            ChartView.Dept -> {
                chart.departments.forEach { dept ->
                    if (search.isNotEmpty() && filtered.none { it.departmentId == dept.id }) return@forEach
                    DeptCard(dept, chart.teams, filtered, search, onMember = { detail = it })
                }
                val unassigned = filtered.filter { it.departmentId == null && it.teamId == null }
                if (unassigned.isNotEmpty()) {
                    ChartPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("❓", fontSize = 1.3.rem)
                            Spacer(Modifier.width(12.dp))
                            Text("Unassigned", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.1.rem)
                            Spacer(Modifier.width(8.dp))
                            HeadcountBadge(unassigned.size)
                        }
                        ChipFlow(unassigned, search) { detail = it }
                    }
                }
                if (search.isNotEmpty() && filtered.isEmpty()) OrgEmpty("No members match \"$search\"")
            }
            ChartView.Tree -> {
                if (tree.roots.isEmpty()) {
                    OrgEmpty("No reporting lines configured — assign managers to employees to build the hierarchy.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tree.roots.forEach { TreeNode(it, tree, 0, search) }
                    }
                }
            }
        }
    }

    detail?.let { member ->
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = colors.bgElevated,
            icon = { UserAvatar(member.name, member.avatar, 40.dp) },
            title = { Text(member.name, color = colors.text, fontWeight = FontWeight.Bold) },
            text = { Text(memberTooltip(member).substringAfter('\n'), color = colors.textSecondary) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("OK", color = colors.primary) } },
        )
    }
}

@Composable
private fun ViewToggle(active: ChartView, onSelect: (ChartView) -> Unit) {
    val colors = LocalWebColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape)) {
        listOf(
            Triple(ChartView.Dept, "By Department", Icons.Outlined.Business),
            Triple(ChartView.Tree, "Reporting Lines", Icons.Outlined.Group),
        ).forEach { (mode, label, icon) ->
            val selected = mode == active
            Row(
                Modifier
                    .weight(1f)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (selected) Color.White else colors.textSecondary
                Icon(icon, null, Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(5.dp))
                Text(label, color = tint, fontSize = 0.85.rem)
            }
        }
    }
}

/** `.card-panel` */
@Composable
private fun ChartPanel(content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun DeptCard(
    dept: ChartDepartment,
    teams: List<ChartTeam>,
    members: List<ChartMember>,
    highlight: String,
    onMember: (ChartMember) -> Unit,
) {
    val colors = LocalWebColors.current
    var open by rememberSaveable(dept.id) { mutableStateOf(true) }
    val deptTeams = teams.filter { it.departmentId == dept.id }
    val directMembers = members.filter { it.departmentId == dept.id && it.teamId == null }
    val totalCount = members.count { it.departmentId == dept.id }

    ChartPanel {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Business, null, Modifier.size(18.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (open) "▾" else "▸"} ${dept.name}",
                        color = colors.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 1.1.rem,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    HeadcountBadge(totalCount)
                }
                dept.headName?.takeIf(String::isNotEmpty)?.let {
                    Text("Head: $it", color = colors.textMuted, fontSize = 0.75.rem)
                }
            }
        }
        if (open) {
            deptTeams.forEach { team ->
                val teamMembers = members.filter { it.teamId == team.id }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp)
                        .background(colors.bgSecondary, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Group, null, Modifier.size(13.dp), tint = colors.text)
                        Spacer(Modifier.width(5.dp))
                        Text(team.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        HeadcountBadge(teamMembers.size)
                    }
                    team.leadName?.takeIf(String::isNotEmpty)?.let {
                        Text("Lead: $it", color = colors.textSecondary, fontSize = 0.8.rem)
                    }
                    if (teamMembers.isNotEmpty()) ChipFlow(teamMembers, highlight, onMember)
                    else Text("No members", color = colors.textMuted, fontSize = 0.75.rem)
                }
            }
            if (directMembers.isNotEmpty()) {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Not assigned to a team:", color = colors.textSecondary, fontSize = 0.85.rem)
                    ChipFlow(directMembers, highlight, onMember)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(members: List<ChartMember>, highlight: String, onMember: (ChartMember) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        members.forEach { MemberChip(it, highlight) { onMember(it) } }
    }
}

/** `.member-chip`: avatar, highlighted name and role badge; tap shows the web `title` details. */
@Composable
private fun MemberChip(member: ChartMember, highlight: String, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.cardBg)
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        UserAvatar(member.name, member.avatar, 20.dp)
        Text(highlighted(member.name, highlight, colors.accent), color = colors.text, fontSize = 0.85.rem, maxLines = 1)
        RoleBadge(member.role)
    }
}

private fun highlighted(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    highlightRanges(text, query).forEach { range ->
        addStyle(SpanStyle(background = accent.copy(alpha = 0.3f)), range.first, range.last + 1)
    }
}

/** Reporting-lines node: rows indent per level with a connector line down the children. */
@Composable
private fun TreeNode(member: ChartMember, tree: ReportingTree, depth: Int, highlight: String) {
    val colors = LocalWebColors.current
    var open by rememberSaveable(member.id) { mutableStateOf(depth < 2) }
    val children = tree.childrenOf(member.id)
    val matched = highlight.isNotEmpty() && member.name.lowercase().contains(highlight.lowercase())

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.cardBg)
                .border(1.dp, if (matched) colors.accent else Color.Transparent, RoundedCornerShape(8.dp))
                .then(if (children.isNotEmpty()) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (children.isNotEmpty()) {
                Text(if (open) "▾" else "▸", color = colors.textSecondary, fontSize = 0.85.rem, textAlign = TextAlign.Center, modifier = Modifier.width(14.dp))
            } else {
                Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(6.dp).background(colors.border, CircleShape))
                }
            }
            UserAvatar(member.name, member.avatar, 28.dp)
            Column(Modifier.weight(1f)) {
                Text(member.name, color = colors.text, fontSize = 0.9.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(treeMeta(member), color = colors.textSecondary, fontSize = 0.75.rem, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (children.isNotEmpty()) {
                Text(
                    plural(children.size, "direct report"),
                    color = colors.textMuted,
                    fontSize = 0.7.rem,
                    maxLines = 1,
                    modifier = Modifier.background(colors.bgSecondary, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (children.isNotEmpty() && open) {
            val line = colors.border
            Column(
                Modifier
                    .padding(start = 13.dp, top = 2.dp, bottom = 4.dp)
                    .drawBehind { drawLine(line, Offset.Zero, Offset(0f, size.height), 2.dp.toPx()) }
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                children.forEach { TreeNode(it, tree, depth + 1, highlight) }
            }
        }
    }
}
