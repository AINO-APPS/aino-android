package app.aino.mobile.feature.attendance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared Material3 building blocks for the mobile-first Attendance page,
 * tinted with [LocalWebColors] so light/dark and org branding still apply.
 */

/** Bordered Material3 card (flat: shadows are invisible on the dark theme). */
@Composable
fun AttendanceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWebColors.current
    val cardColors = CardDefaults.cardColors(containerColor = colors.cardBg, contentColor = colors.text)
    val border = BorderStroke(1.dp, colors.border)
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Card(onClick, modifier.fillMaxWidth(), shape = shape, colors = cardColors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier.fillMaxWidth(), shape = shape, colors = cardColors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Section title row with an optional trailing action. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalWebColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = colors.text,
            fontWeight = FontWeight.Bold,
            fontSize = 1.rem,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** Small tinted pill: status, type and legend chips. */
@Composable
fun StatusPill(label: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, dot: Boolean = false) {
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).background(color, CircleShape))
        icon?.let { Icon(it, null, Modifier.size(12.dp), tint = color) }
        Text(label, color = color, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Inline error with optional Retry / dismiss. */
@Composable
fun ErrorNotice(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HeroIcons.ExclamationCircle, null, Modifier.size(18.dp), tint = colors.danger)
        Text(
            message,
            color = colors.danger,
            fontSize = 0.82.rem,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
        )
        onRetry?.let { TextButton(onClick = it) { Text("Retry", color = colors.danger, fontWeight = FontWeight.SemiBold) } }
        onDismiss?.let {
            IconButton(onClick = it) { Icon(HeroIcons.XMark, "Dismiss", Modifier.size(16.dp), tint = colors.danger) }
        }
    }
}

/** Tinted informational banner (edit mode, conflicts) inside forms. */
@Composable
fun InfoNotice(message: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text(message, color = color, fontSize = 0.82.rem)
    }
}

/** Friendly empty state for lists. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).background(colors.primaryGlow, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = colors.primary)
        }
        Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
        body?.let {
            Text(it, color = colors.textMuted, fontSize = 0.82.rem, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
        }
    }
}

/** ‹ label › stepper used for months and years. Tapping the label resets when [onLabelClick] is set. */
@Composable
fun Stepper(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    prevDescription: String,
    nextDescription: String,
    modifier: Modifier = Modifier,
    onLabelClick: (() -> Unit)? = null,
    nextEnabled: Boolean = true,
) {
    val colors = LocalWebColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(HeroIcons.ChevronLeft, prevDescription, Modifier.size(18.dp), tint = colors.text) }
        if (onLabelClick != null) {
            TextButton(onClick = onLabelClick) {
                Text(label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.92.rem)
            }
        } else {
            Text(label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.92.rem)
        }
        IconButton(onClick = onNext, enabled = nextEnabled) {
            Icon(HeroIcons.ChevronRight, nextDescription, Modifier.size(18.dp), tint = if (nextEnabled) colors.text else colors.textMuted)
        }
    }
}

/** KPI tile: icon, big value, caption. */
@Composable
fun KpiTile(value: String, label: String, accent: Color, icon: ImageVector, modifier: Modifier = Modifier, caption: String? = null) {
    val colors = LocalWebColors.current
    AttendanceCard(modifier, contentPadding = 14.dp) {
        Box(Modifier.size(30.dp).background(accent.copy(alpha = 0.14f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(16.dp), tint = accent)
        }
        Text(value, color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.15.rem, modifier = Modifier.padding(top = 10.dp), maxLines = 1)
        Text(label, color = colors.textMuted, fontSize = 0.74.rem, maxLines = 1)
        caption?.let { Text(it, color = colors.textSecondary, fontSize = 0.68.rem, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Form field caption. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalWebColors.current.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 0.8.rem, modifier = modifier)
}

/** Material3 FilterChip tinted with [tint] (defaults to the brand primary). */
@Composable
fun AttendanceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalWebColors.current.primary,
) {
    val colors = LocalWebColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(16.dp), tint = tint) } },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.textSecondary,
            selectedContainerColor = tint.copy(alpha = 0.16f),
            selectedLabelColor = tint,
            selectedLeadingIconColor = tint,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.border,
            selectedBorderColor = tint.copy(alpha = 0.5f),
        ),
    )
}

/**
 * Material3 bottom sheet for the page forms: title, close button and a
 * scrollable, keyboard-aware body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalWebColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
        contentColor = colors.text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = colors.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 1.15.rem,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss) { Icon(HeroIcons.XMark, "Close", Modifier.size(18.dp), tint = colors.textSecondary) }
            }
            content()
        }
    }
}

/** `fmtDate` port: "2026-01-15" → "Thu, Jan 15". */
fun fmtDate(value: String): String = runCatching {
    LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
}.getOrDefault(value)

/** "Today" / "Yesterday" / "Fri, Oct 2". */
fun friendlyDate(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
}
