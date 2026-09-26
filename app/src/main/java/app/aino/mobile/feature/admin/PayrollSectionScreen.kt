package app.aino.mobile.feature.admin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * A P10.3 payroll page (web `/admin?tab=compensation|salary-slips|payment-config`)
 * as a full-screen page. [onOpenEmployee] / [onOpenSlip] open the Android-only
 * employee and slip pages.
 */
@Composable
fun PayrollSectionScreen(
    viewModel: PayrollViewModel,
    sectionKey: String,
    onBack: () -> Unit,
    onOpenEmployee: (userId: Long, name: String) -> Unit,
    onOpenSlip: (Long) -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(sectionKey) { viewModel.loadSection(sectionKey) }
    AinoFullPage(
        title = payrollSectionTitle(sectionKey),
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.loadSection(sectionKey, force = true) }) {
                Icon(Icons.Outlined.Refresh, "Refresh", tint = LocalWebColors.current.text)
            }
        },
    ) {
        state.notice?.let { AdminNoticeBanner(it) }
        when (sectionKey) {
            PayrollSectionKeys.COMPENSATION -> CompensationSection(state, viewModel, onOpenEmployee)
            PayrollSectionKeys.SALARY_SLIPS -> SalarySlipsSection(state, viewModel, onOpenSlip)
            PayrollSectionKeys.PAYMENT_CONFIG -> PaymentSettingsSection(state, viewModel)
            else -> AdminEmpty("This section is not available.")
        }
    }
}

/** `SalarySlips.tsx` `STATUS_COLORS`. */
internal fun payrollStatusColor(status: String?): Color = when (status) {
    "draft" -> Color(0xFFF59E0B)
    "published", "processed" -> Color(0xFF10B981)
    "processing" -> Color(0xFF3B82F6)
    "failed", "reversed" -> Color(0xFFEF4444)
    else -> Color(0xFF6B7280)
}

/**
 * `PaymentSettings.tsx`: current status card, then the Razorpay X form. Saved
 * secrets and the key id are never refilled (the server only returns masks).
 */
@Composable
internal fun PaymentSettingsSection(state: PayrollUiState, viewModel: PayrollViewModel) {
    AdminLoadState(state.paymentConfig) { saved ->
        val config = saved.value
        var form by remember(config) {
            mutableStateOf(
                PaymentConfigForm(
                    accountNumber = config?.accountNumber.orEmpty(),
                    defaultTransferMode = config?.defaultTransferMode ?: "NEFT",
                    isActive = config?.isActive ?: false,
                ),
            )
        }
        if (config != null) {
            AdminRowCard {
                AdminCell("Current Status", if (config.isActive) "\u2713 Active" else "\u2717 Inactive")
                AdminCell("Key ID", config.apiKeyId ?: "Not set")
                AdminCell("Account", config.accountNumber ?: "Not set")
                AdminCell("Transfer Mode", config.defaultTransferMode.orEmpty())
            }
        }
        AdminRowCard {
            AdminTitle(
                if (config != null) "Update Configuration" else "Setup Configuration",
                "Enter your Razorpay X (Payouts) API credentials. These are different from your regular Razorpay payment gateway keys.",
            )
            AdminField("API Key ID", form.apiKeyId, { form = form.copy(apiKeyId = it) }, placeholder = "rzp_live_...")
            AdminField("API Key Secret", form.apiKeySecret, { form = form.copy(apiKeySecret = it) }, placeholder = "Enter secret key", password = true)
            AdminField(
                "Account Number (Razorpay X)", form.accountNumber, { form = form.copy(accountNumber = it) },
                placeholder = "2323230012345679", keyboardType = KeyboardType.Number,
            )
            AdminField(
                "Webhook Secret", form.webhookSecret, { form = form.copy(webhookSecret = it) },
                placeholder = if (config?.webhookSecret != null) "(configured \u2014 enter new to replace)" else "From Razorpay Dashboard \u2192 Webhooks",
                password = true,
                hint = "Configure your webhook URL as: https://your-domain.com/api/webhooks/razorpay",
            )
            AdminPicker("Default Transfer Mode", TRANSFER_MODES, form.defaultTransferMode, { form = form.copy(defaultTransferMode = it) })
            AdminSwitchRow("Enable disbursement (allow salary transfers)", form.isActive, { form = form.copy(isActive = it) })
            AdminButtonRow {
                AdminButton(if (state.busy) "Saving..." else "Save Configuration", { viewModel.savePaymentConfig(form) }, enabled = !state.busy)
                if (config != null) {
                    AdminButton("Test Connection", viewModel::testPaymentConfig, style = AdminButtonStyle.Secondary, enabled = !state.busy)
                }
            }
        }
    }
}
