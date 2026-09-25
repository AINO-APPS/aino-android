package app.aino.mobile.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** GlobalSearch as a full page; the field is focused with the keyboard up on entry. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val colors = LocalWebColors.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    AinoFullPage(title = "Search", onBack = onBack, scrollable = false) {
        Row(
            Modifier.fillMaxWidth().background(colors.bgSecondary).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Search, null, tint = colors.text.copy(alpha = 0.6f), modifier = Modifier.size(17.dp))
            BasicTextField(
                value = ui.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.primaryLight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.submit()
                    keyboard?.hide()
                }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (ui.query.isEmpty()) {
                            Text(
                                "Search or jump to any page, task, leave, event…",
                                color = colors.textMuted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (ui.loading) {
                CircularProgressIndicator(
                    color = colors.primaryLight, trackColor = colors.border, strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (ui.query.isNotEmpty()) {
                IconButton(onClick = viewModel::clear, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "Clear search", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        HorizontalDivider(color = colors.border)

        if (ui.error.isNotEmpty()) {
            Text(ui.error, color = colors.danger, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (ui.showNoResults) {
            Text(
                "No results for \"${ui.query}\"", color = colors.textMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        if (ui.hasResults) {
            val sections = remember(ui.navResults, ui.results) { searchSections(ui.navResults, ui.results) }
            LazyColumn(Modifier.fillMaxSize()) {
                sections.forEach { section ->
                    stickyHeader(key = "section-${section.title}") {
                        Text(
                            section.title.uppercase(),
                            color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.66.sp,
                            modifier = Modifier.fillMaxWidth().background(colors.bgSecondary)
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(section.rows, key = { it.key }) { row ->
                        SearchResultRow(row) {
                            keyboard?.hide()
                            onOpenLink(row.link)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: SearchRow, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (row.kind == SearchKind.User && row.avatarUrl != null) {
            UserAvatar(row.title, row.avatarUrl, 28.dp)
        } else {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Icon(rowIcon(row), null, tint = colors.textPrimary, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (row.snippet != null) {
                val text = if (row.snippetHtml) highlighted(row.snippet, colors.primaryLight) else AnnotatedString(row.snippet)
                Text(text, color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        row.badge?.let { badge ->
            Row(
                Modifier.background(badge.bg?.let(::Color) ?: colors.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val fg = badge.fg?.let(::Color) ?: colors.textMuted
                Text(badge.text, color = fg, fontSize = 11.sp, maxLines = 1)
                if (badge.go) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = fg, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun highlighted(html: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    parseSnippetHighlights(html).forEach { run ->
        if (run.bold) withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) { append(run.text) } else append(run.text)
    }
}

private fun rowIcon(row: SearchRow): ImageVector = when (row.kind) {
    SearchKind.Nav -> navIconVector(row.navIcon ?: NavIcon.Home)
    SearchKind.Task -> Icons.AutoMirrored.Outlined.Assignment
    SearchKind.Note -> Icons.Outlined.Description
    SearchKind.Event -> Icons.Outlined.CalendarToday
    SearchKind.Leave -> Icons.Outlined.BeachAccess
    SearchKind.Sprint -> Icons.Outlined.RocketLaunch
    SearchKind.User -> Icons.Outlined.Person
    SearchKind.Log -> Icons.AutoMirrored.Outlined.Article
}

private fun navIconVector(icon: NavIcon): ImageVector = when (icon) {
    NavIcon.Home -> Icons.Outlined.Home
    NavIcon.Calendar -> Icons.Outlined.CalendarToday
    NavIcon.CheckSquare -> Icons.Outlined.CheckBox
    NavIcon.FileText -> Icons.Outlined.Description
    NavIcon.MessageSquare -> Icons.Outlined.ChatBubbleOutline
    NavIcon.CalendarCheck -> Icons.Outlined.EventAvailable
    NavIcon.Palmtree -> Icons.Outlined.BeachAccess
    NavIcon.BarChart3 -> Icons.Outlined.BarChart
    NavIcon.FileEdit -> Icons.Outlined.EditNote
    NavIcon.Building2 -> Icons.Outlined.Business
    NavIcon.ClipboardList -> Icons.AutoMirrored.Outlined.Assignment
    NavIcon.Wallet -> Icons.Outlined.AccountBalanceWallet
    NavIcon.Users -> Icons.Outlined.Group
    NavIcon.Settings -> Icons.Outlined.Settings
    NavIcon.User -> Icons.Outlined.Person
    NavIcon.UserPlus -> Icons.Outlined.PersonAdd
    NavIcon.Download -> Icons.Outlined.Download
    NavIcon.ScrollText -> Icons.AutoMirrored.Outlined.Article
    NavIcon.RefreshCw -> Icons.Outlined.Refresh
    NavIcon.Building -> Icons.Outlined.Apartment
}
