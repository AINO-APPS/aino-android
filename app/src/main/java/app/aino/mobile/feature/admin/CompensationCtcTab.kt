package app.aino.mobile.feature.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.delay

/** `CTC Breakdown Settings`: the percentages `calcFromCtc` uses; a failed load shows the defaults. */
@Composable
internal fun CtcSettingsTab(state: PayrollUiState, viewModel: PayrollViewModel) {
    val colors = LocalWebColors.current
    val loaded = state.ctc.data
    var form by remember(loaded) { mutableStateOf(ctcFormFor(loaded ?: CtcConfig())) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (saved) {
            delay(2_000)
            saved = false
        }
    }
    state.ctc.error?.let { AdminError(it) }
    if (loaded == null && state.ctc.loading) {
        AdminLoading()
        return
    }
    AdminRowCard {
        AdminTitle(
            "CTC Breakdown Settings",
            "These percentages are used to auto-calculate monthly component amounts when an Annual CTC is entered during compensation assignment.",
        )
        val num = KeyboardType.Decimal
        AdminField("Basic Salary \u2014 % of Monthly CTC", form.basicPct, { form = form.copy(basicPct = it) }, keyboardType = num)
        AdminField("HRA \u2014 % of Basic Salary", form.hraPct, { form = form.copy(hraPct = it) }, keyboardType = num)
        AdminField("Conveyance \u2014 % of Monthly CTC", form.conveyancePct, { form = form.copy(conveyancePct = it) }, keyboardType = num)
        AdminField("PF \u2014 % of Basic Salary", form.pfPct, { form = form.copy(pfPct = it) }, keyboardType = num)
        AdminField("PF Maximum Cap (\u20B9/month)", form.pfMax, { form = form.copy(pfMax = it) }, keyboardType = num)
        AdminField("Professional Tax \u2014 Fixed (\u20B9/month)", form.ptFixed, { form = form.copy(ptFixed = it) }, keyboardType = num)
        Text(
            "Special Allowance is automatically the remaining balance after Basic, HRA, and Conveyance. TDS defaults to \u20B90 and can be set manually per employee.",
            color = colors.textMuted, fontSize = 0.78.rem,
        )
        AdminButton(
            if (saved) "Saved!" else if (state.busy) "Saving..." else "Save Settings",
            { viewModel.saveCtc(form) { c -> form = ctcFormFor(c); saved = true } },
            enabled = !state.busy && form.toConfig() != null,
        )
    }
}
