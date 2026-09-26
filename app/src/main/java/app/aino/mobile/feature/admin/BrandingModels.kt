package app.aino.mobile.feature.admin

import app.aino.mobile.core.branding.Branding
import app.aino.mobile.core.designsystem.tokens.DEFAULT_BRAND_ACCENT
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /branding/email-templates` row: the built-in merged with the org override. */
@Serializable
data class EmailTemplate(
    @SerialName("template_key") val templateKey: String,
    val subject: String = "",
    @SerialName("body_html") val bodyHtml: String = "",
    val enabled: Boolean = true,
    @SerialName("is_overridden") val isOverridden: Boolean = false,
    @SerialName("builtin_subject") val builtinSubject: String? = null,
    @SerialName("builtin_body_html") val builtinBodyHtml: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class EmailTemplateList(val templates: List<EmailTemplate> = emptyList())

/** `POST /branding/email-templates/:key/preview` → `{ subject, html }`. */
@Serializable
data class EmailPreview(val subject: String = "", val html: String = "")

/** `EmailTemplatesSection.tsx` `TEMPLATE_LABELS` (server `mailer.ts` `TEMPLATE_KEYS`). */
val EMAIL_TEMPLATE_LABELS = mapOf(
    "leaveApproved" to "Leave approved",
    "leaveRejected" to "Leave rejected",
    "leaveRevoked" to "Leave revoked",
    "taskAssigned" to "Task assigned",
    "mention" to "You were mentioned",
    "manualEntryApproved" to "Manual entry approved",
    "manualEntryRejected" to "Manual entry rejected",
    "meetingScheduled" to "Meeting scheduled",
    "meetingUpdated" to "Meeting updated",
    "meetingCancelled" to "Meeting cancelled",
)

fun emailTemplateLabel(key: String): String = EMAIL_TEMPLATE_LABELS[key] ?: key

/** `BrandingSection.tsx` `PRESETS`. */
val BRAND_ACCENT_PRESETS = listOf("#2383e2", "#6366f1", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#ec4899")

/** Server multer limit and web client check. */
const val MAX_LOGO_BYTES = 2 * 1024 * 1024

/** Server `fileFilter`: `image/(png|jpe?g|gif|svg+xml|webp)`. */
val LOGO_MIME_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/svg+xml", "image/webp")

/** Server `PUT /email-templates/:key` limits (web `maxLength`). */
const val EMAIL_SUBJECT_MAX = 200
const val EMAIL_BODY_MAX = 30_000

/** Branding and email templates are editable by hr_admin, super_admin and platform_admin. */
fun canEditBranding(role: String): Boolean = role == "hr_admin" || role == "super_admin" || role == "platform_admin"

/**
 * Web hex `<input>` `onChange`: only `#?[0-9a-f]{0,6}` is accepted and the
 * `#` is re-added; anything else keeps [current].
 */
fun sanitizeHexInput(input: String, current: String): String =
    if (Regex("^#?[0-9a-fA-F]{0,6}$").matches(input)) (if (input.startsWith("#")) input else "#$input") else current

/** Client-side logo check; null = acceptable. Messages match the web and server copy. */
fun validateLogo(mimeType: String?, size: Long): String? = when {
    size > MAX_LOGO_BYTES -> "Logo must be under 2 MB"
    mimeType?.lowercase() !in LOGO_MIME_TYPES -> "Only image files are allowed"
    else -> null
}

/** A picked, not yet uploaded, logo. */
class StagedLogo(val fileName: String, val mimeType: String, val bytes: ByteArray)

/**
 * `BrandingSection.tsx` staged changes: logo, logo removal and accent are only
 * persisted on "Save changes".
 */
data class BrandingDraft(
    val accent: String = DEFAULT_BRAND_ACCENT,
    val logo: StagedLogo? = null,
    val removeLogo: Boolean = false,
) {
    val accentValid: Boolean get() = isHexColor(accent)

    fun accentDirty(saved: Branding): Boolean = !accent.equals(saved.accentColor.orEmpty(), ignoreCase = true)

    fun logoDirty(): Boolean = logo != null || removeLogo

    fun dirty(saved: Branding): Boolean = accentDirty(saved) || logoDirty()

    /** Preview source, staged over saved: the staged bytes, else the saved URL unless removal is pending. */
    fun effectiveLogo(saved: Branding): Any? = when {
        logo != null -> logo.bytes
        removeLogo -> null
        else -> saved.logoUrl?.takeIf(String::isNotBlank)
    }

    companion object {
        fun from(saved: Branding) = BrandingDraft(accent = saved.accentColor?.takeIf(String::isNotBlank) ?: DEFAULT_BRAND_ACCENT)
    }
}

/** `TemplateEditor` local state. */
data class TemplateDraft(
    val key: String,
    val subject: String,
    val body: String,
    val enabled: Boolean,
) {
    fun dirty(template: EmailTemplate): Boolean =
        subject != template.subject || body != template.bodyHtml || enabled != template.enabled

    /** "Insert built-in template": swaps the draft to the built-in without saving. */
    fun withBuiltin(template: EmailTemplate): TemplateDraft =
        copy(subject = template.builtinSubject.orEmpty(), body = template.builtinBodyHtml.orEmpty())

    companion object {
        fun from(template: EmailTemplate) = TemplateDraft(template.templateKey, template.subject, template.bodyHtml, template.enabled)
    }
}

/** Picker label: the web list row with its "customised" dot and "off" tag. */
fun emailTemplateOptionLabel(template: EmailTemplate): String = buildString {
    append(emailTemplateLabel(template.templateKey))
    if (template.isOverridden) append(" \u00b7 Customised")
    if (!template.enabled) append(" \u00b7 Off")
}

data class BrandingUiState(
    val role: String = "",
    val branding: Load<Branding> = Load(),
    val draft: BrandingDraft = BrandingDraft(),
    val templates: Load<List<EmailTemplate>> = Load(),
    val selectedKey: String? = null,
    val templateDraft: TemplateDraft? = null,
    val preview: EmailPreview? = null,
    val previewLoading: Boolean = false,
    val saving: Boolean = false,
    val reverting: Boolean = false,
    val notice: AdminNotice? = null,
) {
    val canEdit: Boolean get() = canEditBranding(role)
    val selected: EmailTemplate? get() = templates.data?.firstOrNull { it.templateKey == selectedKey }
}
