package app.aino.mobile.feature.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.DEFAULT_BRAND_ACCENT
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** `BrandingSection.tsx`: staged logo + accent, a live preview and one Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrandingTab(viewModel: BrandingViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { picked -> viewModel.pickLogo { readStagedLogo(context, picked) } }
    }
    state.notice?.let { AdminNoticeBanner(it) }
    AdminLoadState(state.branding) { saved ->
        val draft = state.draft
        val editable = state.canEdit && !state.saving
        val fallback = hexColor(DEFAULT_BRAND_ACCENT)
        val accent = if (draft.accentValid) hexColor(draft.accent, fallback) else fallback
        val logo = draft.effectiveLogo(saved)
        AdminRowCard {
            AdminTitle("Organization logo", "PNG, JPG, SVG, GIF or WebP \u2014 max 2 MB. Recommended height 40 px.")
            LogoPreview(logo)
            AdminButtonRow {
                AdminButton(if (logo != null) "Replace" else "Choose logo", { picker.launch("image/*") }, style = AdminButtonStyle.Secondary, enabled = editable, small = true)
                if (logo != null) AdminButton("Remove", viewModel::stageRemoveLogo, style = AdminButtonStyle.Secondary, enabled = editable, small = true)
            }
            if (draft.logoDirty()) {
                AdminHint(if (draft.removeLogo) "Removal pending \u2014 tap Save to apply" else "New logo selected \u2014 tap Save to apply")
            }
        }
        AdminRowCard {
            AdminTitle("Accent color", "The accent color is applied to buttons, links, badges, and outgoing email templates.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BRAND_ACCENT_PRESETS.forEach { preset ->
                    AdminColorDot(hexColor(preset), draft.accent.equals(preset, ignoreCase = true)) { if (editable) viewModel.setAccent(preset) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).background(accent, RoundedCornerShape(8.dp)).border(1.dp, LocalWebColors.current.border, RoundedCornerShape(8.dp)))
                AdminTextInput(draft.accent, viewModel::setAccent, DEFAULT_BRAND_ACCENT, Modifier.weight(1f), enabled = editable)
            }
            if (!draft.accentValid) AdminHint("Accent color must be a 6-digit hex (e.g. #2383e2)")
        }
        BrandPreviewCard(logo, accent)
        if (state.canEdit) {
            val dirty = draft.dirty(saved)
            AdminButtonRow {
                AdminButton(if (state.saving) "Saving\u2026" else "Save changes", viewModel::saveBranding, enabled = dirty && !state.saving && draft.accentValid)
                AdminButton("Cancel", viewModel::cancelBranding, style = AdminButtonStyle.Cancel, enabled = dirty && !state.saving)
            }
            if (!dirty) AdminHint("No unsaved changes")
        } else {
            AdminHint("You don't have permission to edit branding. Contact your HR admin or super admin.")
        }
    }
}

/** `.previewBlock`: sample heading, text and a primary button in the draft accent. */
@Composable
private fun BrandPreviewCard(logo: Any?, accent: Color) {
    val colors = LocalWebColors.current
    AdminFieldLabel("Live preview")
    Column(
        Modifier.fillMaxWidth().background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (logo != null) BrandLogoImage(logo, 32.dp, 160.dp, "")
        Text("Sample heading", color = accent, fontSize = 1.05.rem, fontWeight = FontWeight.Bold)
        Text(
            "This is how content will look with your accent color. The button below uses the same hue.",
            color = colors.textSecondary,
            fontSize = 0.85.rem,
        )
        Text(
            "Primary action",
            color = Color.White,
            fontSize = 0.85.rem,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(accent, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
