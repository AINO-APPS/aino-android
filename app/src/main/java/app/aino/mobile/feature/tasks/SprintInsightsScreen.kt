package app.aino.mobile.feature.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ShowChart
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `pages/SprintInsights.tsx`: summary, sprint tickets, burndown, velocity,
 * cumulative flow, cycle/lead time and the retrospective editor for one
 * selected sprint. The web's manual refresh button is replaced by
 * pull-to-refresh (product decision); charts are drawn natively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprintInsightsScreen(viewModel: TaskViewModel, onBack: () -> Unit, requestedSprintId: Long? = null, onOpenTask: (Long) -> Unit = {}) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val repo = viewModel.repo
    var sprints by remember { mutableStateOf<List<AvailableSprint>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var data by remember { mutableStateOf<SprintInsightsData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        val list = withContext(Dispatchers.IO) { runCatching(repo::loadTeamSprints).getOrDefault(sprints) }
        sprints = list
        if (selectedId == null) selectedId = initialInsightsSprint(list, requestedSprintId)
    }
    LaunchedEffect(selectedId, reloadKey) {
        val id = selectedId ?: run { refreshing = false; return@LaunchedEffect }
        loading = true
        data = withContext(Dispatchers.IO) {
            coroutineScope {
                val stats = async { runCatching { repo.loadSprintStats(id) }.getOrNull() }
                val cfd = async { runCatching { repo.loadCumulativeFlow(id) }.getOrNull() }
                val cycle = async { runCatching { repo.loadCycleTime(id) }.getOrNull() }
                val retro = async { runCatching { repo.loadRetrospective(id) }.getOrNull() }
                val tasks = async { runCatching { repo.loadSprintTaskRows(id) }.getOrDefault(emptyList()) }
                SprintInsightsData(stats.await(), cfd.await(), cycle.await(), retro.await(), tasks.await())
            }
        }
        loading = false
        refreshing = false
    }
    val selected = sprints.firstOrNull { it.id == selectedId }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; reloadKey++ },
        modifier = Modifier.fillMaxSize().background(colors.bg),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Row(Modifier.clickable(onClick = onBack).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(14.dp), tint = colors.textMuted)
                        Spacer(Modifier.width(4.dp))
                        Text("Back to Tasks", color = colors.textMuted, fontSize = 0.82.rem)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Sprint Insights", color = colors.text, fontSize = 1.25.rem, fontWeight = FontWeight.Bold)
                    Text("Burndown, velocity, cumulative flow, cycle time and retrospectives.", color = colors.textMuted, fontSize = 0.85.rem)
                }
                if (sprints.isNotEmpty()) {
                    val options = sprintGroups(sprints).flatMap { (group, list) -> list.map { it.id to "${it.name}  ·  $group" } }
                    WebSelect(options, selectedId ?: sprints.first().id, { selectedId = it }, Modifier.fillMaxWidth())
                }
            }
            if (selectedId == null) {
                Text("Select a sprint to see insights.", color = colors.textMuted, fontSize = 0.85.rem)
                return@Column
            }
            val d = data
            d?.stats?.let { stats -> SummaryCard(stats, selected, ui.agile.unitLabel) }
            InsightCard(Icons.Outlined.Checklist, "Sprint Tickets") { SprintTicketsTable(d?.tasks.orEmpty(), onOpenTask) }
            InsightCard(Icons.Outlined.ShowChart, "Burndown") { BurndownChart(selectedId!!, reloadKey, viewModel, ui.agile.unitLabel) }
            InsightCard(Icons.Outlined.BarChart, "Velocity") { VelocityChart(reloadKey, viewModel, ui.agile.unitLabel) }
            InsightCard(Icons.Outlined.Layers, "Cumulative Flow") { CumulativeFlowChart(d?.cfd, loaded = d != null) }
            InsightCard(Icons.Outlined.ShowChart, "Cycle & Lead Time") { CycleTimePanel(d?.cycle, loaded = d != null, onOpenTask) }
            InsightCard(Icons.Outlined.ChatBubbleOutline, "Retrospective") {
                RetrospectivePanel(selectedId!!, d?.retro, viewModel) { reloadKey++ }
            }
            if (loading) Text("Loading…", color = colors.textMuted, fontSize = 0.85.rem)
        }
    }
}

@Composable
private fun InsightCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().background(colors.bg, RoundedCornerShape(10.dp)).border(1.dp, colors.border, RoundedCornerShape(10.dp)).padding(16.dp),
    ) {
        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = colors.text)
            Spacer(Modifier.width(6.dp))
            Text(title, color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun EmptyChart(text: String) {
    val colors = LocalWebColors.current
    Text(
        text, color = colors.textMuted, fontSize = 0.82.rem, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(colors.bgSecondary, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 30.dp),
    )
}

private enum class StatKind { Default, Ok, Warning, Danger }

@Composable
private fun Stat(label: String, value: String, sub: String? = null, kind: StatKind = StatKind.Default, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val accent = when (kind) {
        StatKind.Ok -> colors.success
        StatKind.Warning -> colors.warning
        StatKind.Danger -> colors.danger
        StatKind.Default -> colors.textMuted
    }
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(colors.bgSecondary).border(1.dp, colors.border, RoundedCornerShape(8.dp))) {
        Box(Modifier.width(3.dp).heightIn(min = 64.dp).background(accent))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label.uppercase(), color = colors.textMuted, fontSize = 0.7.rem, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = colors.text, fontSize = 1.25.rem, fontWeight = FontWeight.Bold, maxLines = 1)
            sub?.let { Text(it, color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
}

/** `summaryGrid` is two columns at ≤480px. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(stats: SprintStats, sprint: AvailableSprint?, unitLabel: String) {
    val colors = LocalWebColors.current
    val t = stats.totals
    Box(Modifier.fillMaxWidth().background(colors.bg, RoundedCornerShape(10.dp)).border(1.dp, colors.border, RoundedCornerShape(10.dp)).padding(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
            val cell = Modifier.weight(1f)
            Stat("Tickets", "${t.doneTasks}/${t.tasks}", "${t.percentByTasks}% complete", modifier = cell)
            Stat("Points ($unitLabel)", "${formatPoints(t.donePoints)}/${formatPoints(t.points)}", "${t.percentByPoints}% complete", modifier = cell)
            Stat("Unestimated", "${t.unestimatedTasks}", kind = if (t.unestimatedTasks > 0) StatKind.Warning else StatKind.Ok, modifier = cell)
            Stat("Blocked", "${t.blockedTasks}", kind = if (t.blockedTasks > 0) StatKind.Danger else StatKind.Ok, modifier = cell)
            sprint?.let { Stat("Status", it.status, "${it.startDate.take(10)} → ${it.endDate.take(10)}", modifier = cell) }
        }
    }
}

/** A web `<table>` that scrolls horizontally on a phone. */
@Composable
private fun InsightTable(headers: List<String>, widths: List<Int>, rows: List<List<@Composable () -> Unit>>) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(colors.bgSecondary)) {
            headers.forEachIndexed { i, h ->
                Text(h, color = colors.textMuted, fontSize = 0.78.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(widths[i].dp).padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
        rows.forEach { cells ->
            Box(Modifier.height(1.dp).width(widths.sum().dp).background(colors.border))
            Row(verticalAlignment = Alignment.CenterVertically) {
                cells.forEachIndexed { i, cell -> Box(Modifier.width(widths[i].dp).padding(horizontal = 10.dp, vertical = 7.dp)) { cell() } }
            }
        }
    }
}

@Composable
private fun TicketCell(id: Long, title: String, onOpenTask: (Long) -> Unit) {
    val colors = LocalWebColors.current
    Row {
        Text("#$id ", color = colors.text, fontSize = 0.8.rem)
        Text(title, color = colors.primary, fontSize = 0.8.rem, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onOpenTask(id) })
    }
}

@Composable
private fun CellText(text: String, color: Color = LocalWebColors.current.text, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = 0.8.rem, fontWeight = weight, maxLines = 1)
}

@Composable
private fun SprintTicketsTable(tasks: List<Task>, onOpenTask: (Long) -> Unit) {
    if (tasks.isEmpty()) return EmptyChart("No tickets in this sprint.")
    val priorityColors = mapOf("high" to Color(0xFFEF4444), "medium" to Color(0xFFF59E0B), "low" to Color(0xFF6B7280))
    Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        InsightTable(
            listOf("Ticket", "Status", "Priority", "Points"), listOf(220, 110, 90, 70),
            tasks.map { t ->
                listOf(
                    { TicketCell(t.id, t.title, onOpenTask) },
                    { CellText(INSIGHT_STATUS_LABELS[t.status] ?: t.status) },
                    { CellText(t.priority.replaceFirstChar(Char::uppercase), priorityColors[t.priority] ?: Color(0xFF6B7280), FontWeight.Medium) },
                    { CellText(t.storyPoints?.let(::formatPoints) ?: "—") },
                )
            },
        )
    }
}

private fun Double?.orDash(): String = this?.let(::formatPoints) ?: "—"

/** `BurndownChart`: dashed ideal line vs actual remaining points. */
@Composable
private fun BurndownChart(sprintId: Long, reloadKey: Int, viewModel: TaskViewModel, unitLabel: String) {
    val colors = LocalWebColors.current
    var data by remember { mutableStateOf<BurndownResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(sprintId, reloadKey) {
        loading = true
        data = withContext(Dispatchers.IO) { runCatching { viewModel.repo.loadBurndown(sprintId) }.getOrNull() }
        loading = false
    }
    val d = data
    when {
        loading -> return Text("Loading burndown…", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(30.dp))
        d == null -> return EmptyChart("Burndown unavailable")
        d.snapshots.isEmpty() && d.startScope == 0.0 -> return EmptyChart("No data yet — add story points & start the sprint to see a burndown.")
    }
    d!!
    val measurer = rememberTextMeasurer()
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(16.dp).height(2.dp).background(colors.textMuted))
            Text(" Ideal  ", color = colors.textMuted, fontSize = 0.72.rem)
            Box(Modifier.width(16.dp).height(2.dp).background(colors.primary))
            Text(" Actual", color = colors.textMuted, fontSize = 0.72.rem)
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val padL = 40.dp.toPx(); val padR = 12.dp.toPx(); val padT = 12.dp.toPx(); val padB = 28.dp.toPx()
            val innerW = size.width - padL - padR
            val innerH = size.height - padT - padB
            val scope = maxOf(1.0, d.startScope)
            val label = TextStyle(color = colors.textMuted, fontSize = 10.sp)
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            for (i in 0..4) {
                val y = padT + innerH * i / 4
                drawLine(colors.border, Offset(padL, y), Offset(size.width - padR, y), 1f, pathEffect = dash)
                drawLabel(measurer, "${Math.round(d.startScope * (1 - i / 4.0))}", Offset(padL - 6.dp.toPx(), y), label, alignEnd = true)
            }
            drawLine(colors.border, Offset(padL, size.height - padB), Offset(size.width - padR, size.height - padB), 1f)
            drawLabel(measurer, d.sprint.startDate.take(10), Offset(padL, size.height - 6.dp.toPx()), label, baseline = true)
            drawLabel(measurer, d.sprint.endDate.take(10), Offset(size.width - padR, size.height - 6.dp.toPx()), label, alignEnd = true, baseline = true)
            val idealCount = d.ideal.size
            val ideal = d.ideal.mapIndexed { i, p ->
                Offset(padL + innerW * i / maxOf(1, idealCount - 1), (padT + innerH - innerH * (p.remaining / scope)).toFloat())
            }
            val totalDays = maxOf(1, idealCount - 1)
            val actual = d.snapshots.map { sn ->
                val idx = burndownDayIndex(sn.snapshotDate, d.sprint.startDate, totalDays)
                Offset(padL + innerW * idx / totalDays, (padT + innerH - innerH * (sn.remainingPoints / scope)).toFloat())
            }
            if (ideal.isNotEmpty()) drawPath(pathOf(ideal), colors.textMuted, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            if (actual.isNotEmpty()) drawPath(pathOf(actual), colors.primary, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            actual.forEach { drawCircle(colors.primary, 3.dp.toPx(), it) }
        }
    }
}

private fun pathOf(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    style: TextStyle,
    alignEnd: Boolean = false,
    center: Boolean = false,
    baseline: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val x = when {
        alignEnd -> at.x - layout.size.width
        center -> at.x - layout.size.width / 2f
        else -> at.x
    }
    val y = if (baseline) at.y - layout.size.height else at.y - layout.size.height / 2f
    drawText(layout, topLeft = Offset(x, y))
}

/** `VelocityChart`: bars for the last 6 completed sprints + dashed average. */
@Composable
private fun VelocityChart(reloadKey: Int, viewModel: TaskViewModel, unitLabel: String) {
    val colors = LocalWebColors.current
    var data by remember { mutableStateOf<VelocityResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(reloadKey) {
        loading = true
        data = withContext(Dispatchers.IO) { runCatching { viewModel.repo.loadVelocity(6) }.getOrNull() }
        loading = false
    }
    val d = data
    if (loading) return Text("Loading velocity…", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(30.dp))
    if (d == null || d.sprints.isEmpty()) return EmptyChart("No completed sprints yet — velocity will appear after the first sprint completes.")
    val measurer = rememberTextMeasurer()
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Avg: ", color = colors.textMuted, fontSize = 0.72.rem)
            Text(formatPoints(d.average), color = colors.textMuted, fontSize = 0.72.rem, fontWeight = FontWeight.Bold)
            Text(" $unitLabel", color = colors.textMuted, fontSize = 0.72.rem)
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val padL = 36.dp.toPx(); val padR = 12.dp.toPx(); val padT = 12.dp.toPx(); val padB = 36.dp.toPx()
            val innerW = size.width - padL - padR
            val innerH = size.height - padT - padB
            val maxVal = maxOf(d.average, d.sprints.maxOf { it.velocityPoints }).takeIf { it > 0 } ?: 1.0
            val slot = innerW / d.sprints.size
            val barW = maxOf(8.dp.toPx(), slot - 14.dp.toPx())
            if (d.average > 0) {
                val y = (padT + innerH - innerH * d.average / maxVal).toFloat()
                drawLine(colors.success, Offset(padL, y), Offset(size.width - padR, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
            val small = TextStyle(color = colors.textMuted, fontSize = 10.sp)
            d.sprints.forEachIndexed { i, sp ->
                val cx = padL + slot * i + slot / 2
                val bh = (innerH * sp.velocityPoints / maxVal).toFloat()
                val y = padT + innerH - bh
                drawRoundRect(colors.primary, Offset(cx - barW / 2, y), Size(barW, bh), CornerRadius(3.dp.toPx()))
                drawLabel(measurer, formatPoints(sp.velocityPoints), Offset(cx, y - 4.dp.toPx()), TextStyle(color = colors.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold), center = true, baseline = true)
                drawLabel(measurer, sp.name.replace("Sprint ", "S"), Offset(cx, size.height - 18.dp.toPx()), small, center = true, baseline = true)
                sp.completedAt?.let { drawLabel(measurer, formatShortDate(it), Offset(cx, size.height - 4.dp.toPx()), small, center = true, baseline = true) }
            }
        }
    }
}

private fun formatShortDate(value: String): String {
    val date = runCatching { java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: localDateOf(value)
    return date?.format(java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy", java.util.Locale.US)).orEmpty()
}

/** `CumulativeFlowChart`: stacked areas by workflow category, Done on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CumulativeFlowChart(cfd: CfdResponse?, loaded: Boolean) {
    val colors = LocalWebColors.current
    if (!loaded || cfd == null) return EmptyChart(if (loaded) "No data yet for this sprint." else "Loading…")
    val series = cfd.series
    if (series.isEmpty()) return EmptyChart("No data yet for this sprint.")
    val measurer = rememberTextMeasurer()
    Column {
        FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CFD_BANDS.forEach { (_, label, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(color)))
                    Spacer(Modifier.width(4.dp))
                    Text(label, color = colors.textMuted, fontSize = 0.75.rem)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val padL = 32.dp.toPx(); val padR = 12.dp.toPx(); val padT = 12.dp.toPx(); val padB = 36.dp.toPx()
            val innerW = size.width - padL - padR
            val innerH = size.height - padT - padB
            val maxY = maxOf(1, series.maxOf { day -> CFD_BANDS.sumOf { day.value(it.first) } })
            val xStep = innerW / maxOf(1, series.size - 1)
            val label = TextStyle(color = colors.textMuted, fontSize = 10.sp)
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            listOf(0f, .25f, .5f, .75f, 1f).forEach { p ->
                val y = padT + innerH * p
                drawLine(colors.border, Offset(padL, y), Offset(size.width - padR, y), 1f, pathEffect = dash)
                drawLabel(measurer, "${Math.round(maxY * (1 - p))}", Offset(padL - 4.dp.toPx(), y), label, alignEnd = true)
            }
            fun yOf(v: Int) = padT + innerH - innerH * v / maxY
            CFD_BANDS.forEachIndexed { index, (category, _, color) ->
                val bottoms = series.map { day -> CFD_BANDS.take(index).sumOf { day.value(it.first) } }
                val tops = series.mapIndexed { i, day -> bottoms[i] + day.value(category) }
                val path = Path().apply {
                    tops.forEachIndexed { i, v -> if (i == 0) moveTo(padL, yOf(v)) else lineTo(padL + i * xStep, yOf(v)) }
                    bottoms.indices.reversed().forEach { i -> lineTo(padL + i * xStep, yOf(bottoms[i])) }
                    close()
                }
                drawPath(path, Color(color).copy(alpha = 0.85f))
            }
            listOf(0, series.size / 2, series.size - 1).distinct().filter { it in series.indices }.forEach { i ->
                val at = Offset(padL + i * xStep, size.height - 12.dp.toPx())
                drawLabel(
                    measurer, series[i].date.take(10), at, label, baseline = false,
                    alignEnd = i == series.size - 1 && i != 0, center = i != 0 && i != series.size - 1,
                )
            }
        }
    }
}

/** `CycleTimePanel`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CycleTimePanel(data: CycleResponse?, loaded: Boolean, onOpenTask: (Long) -> Unit) {
    if (!loaded || data == null) return EmptyChart(if (loaded) "No completed tickets yet." else "Loading…")
    if (data.tasks.isEmpty()) return EmptyChart("No completed tickets yet.")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
            val cell = Modifier.weight(1f)
            Stat("Cycle (avg)", data.cycle.avg?.let { "${formatPoints(it)} d" } ?: "—", "median ${data.cycle.median.orDash()} · p90 ${data.cycle.p90.orDash()}", modifier = cell)
            Stat("Lead (avg)", data.lead.avg?.let { "${formatPoints(it)} d" } ?: "—", "median ${data.lead.median.orDash()} · p90 ${data.lead.p90.orDash()}", modifier = cell)
            Stat("Tickets sampled", "${data.cycle.n}", modifier = cell)
        }
        InsightTable(
            listOf("Ticket", "Type", "Story Points", "Cycle (d)", "Lead (d)", "Completed"), listOf(200, 90, 100, 80, 80, 180),
            data.tasks.map { t ->
                listOf(
                    { TicketCell(t.id, t.title, onOpenTask) },
                    {
                        t.typeName?.let { name ->
                            val tint = hexColor(t.typeColor, LocalWebColors.current.textSecondary)
                            Text(name, color = tint, fontSize = 0.72.rem, modifier = Modifier.border(1.dp, tint, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    },
                    { CellText(t.storyPoints.orDash()) },
                    { CellText(t.cycleDays.orDash()) },
                    { CellText(t.leadDays.orDash()) },
                    { CellText(formatLocaleString(t.completedAt)) },
                )
            },
        )
    }
}

/** `RetrospectivePanel`. */
@Composable
private fun RetrospectivePanel(sprintId: Long, initial: Retrospective?, viewModel: TaskViewModel, onSaved: () -> Unit) {
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var wentWell by remember(sprintId, initial) { mutableStateOf(initial?.wentWell.orEmpty()) }
    var toImprove by remember(sprintId, initial) { mutableStateOf(initial?.toImprove.orEmpty()) }
    var summary by remember(sprintId, initial) { mutableStateOf(initial?.summary.orEmpty()) }
    var mood by remember(sprintId, initial) { mutableStateOf(initial?.teamMood) }
    var items by remember(sprintId, initial) { mutableStateOf(initial?.actionItems.orEmpty()) }
    var newAction by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    fun addAction() {
        if (newAction.isBlank()) return
        items = items + ActionItem(id = System.currentTimeMillis(), text = newAction.trim())
        newAction = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RetroColumn("What went well", wentWell, { wentWell = it }, "• Wins, smooth processes, kudos…", Color(0xFF10B981))
        RetroColumn("What to improve", toImprove, { toImprove = it }, "• Pain points, blockers, things to change…", Color(0xFFF59E0B))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Checklist, null, Modifier.size(14.dp), tint = colors.text)
            Spacer(Modifier.width(4.dp))
            Text("Action items", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) Text("No action items yet.", color = colors.textMuted, fontSize = 0.82.rem)
        items.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = a.done,
                    onCheckedChange = { items = items.map { if (it.id == a.id) it.copy(done = !it.done) else it } },
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.textMuted),
                )
                Text(
                    a.text, color = if (a.done) colors.textMuted else colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f),
                    textDecoration = if (a.done) TextDecoration.LineThrough else null,
                )
                Text("×", color = colors.textMuted, fontSize = 1.rem, modifier = Modifier.clickable { items = items.filterNot { it.id == a.id } }.padding(8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WebTextField(
                newAction, { newAction = it }, "New action item — press Enter to add", Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addAction() }),
            )
            Spacer(Modifier.width(8.dp))
            WebButton("Add", ::addAction, small = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Team mood", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold)
            MOODS.forEachIndexed { i, (emoji, _) ->
                val n = i + 1
                val active = mood == n
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.bg)
                        .border(1.dp, if (active) colors.primary else colors.border, RoundedCornerShape(6.dp))
                        .clickable { mood = if (active) null else n }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) { Text(emoji, fontSize = 1.1.rem) }
            }
        }
        WebTextField(summary, { summary = it }, "Summary (optional)", singleLine = false, minLines = 2)
        WebButton(
            if (saving) "Saving…" else if (saved) "Saved ✓" else "Save retrospective",
            {
                saving = true
                saved = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { viewModel.repo.saveRetrospective(sprintId, RetrospectivePayload(wentWell, toImprove, summary, mood, items)) }.isSuccess
                    }
                    saving = false
                    if (ok) {
                        saved = true
                        onSaved()
                        delay(1_500)
                        saved = false
                    }
                }
            },
            Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = !saving,
        )
    }
}

@Composable
private fun RetroColumn(label: String, value: String, onChange: (String) -> Unit, placeholder: String, accent: Color) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.bgSecondary).border(1.dp, colors.border, RoundedCornerShape(8.dp))) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = accent, fontSize = 0.78.rem, fontWeight = FontWeight.Bold)
            WebTextField(value, onChange, placeholder, singleLine = false, minLines = 5)
        }
    }
}
