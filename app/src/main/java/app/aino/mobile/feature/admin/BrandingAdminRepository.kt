package app.aino.mobile.feature.admin

import app.aino.mobile.core.branding.Branding
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `client/src/api/meetings.ts` branding + email-template calls (P10.4),
 * admin half. The read-only app-wide branding lives in `core/branding`.
 */
class BrandingAdminRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true },
    private val boundary: () -> String = { "aino-${UUID.randomUUID()}" },
) {
    fun branding(): Branding = load {
        // @api GET branding
        decode(api.execute(ApiRequest(path = "branding")))
    }

    /** `updateBrandingAccent`: answers the `org_branding` row (no `org_name`). */
    fun updateAccent(accent: String): Branding {
        val body = buildJsonObject { put("accent_color", accent) }
        // @api PUT branding
        return mutate("branding", body, "PUT")
    }

    /** `uploadBrandingLogo`: multipart field `logo`; answers the `org_branding` row. */
    fun uploadLogo(logo: StagedLogo): Branding = load {
        val payload = logoMultipart(logo, boundary())
        // @api POST branding/logo
        decode(api.execute(ApiRequest("POST", "branding/logo", headers = mapOf("Content-Type" to payload.first), body = payload.second)))
    }

    fun deleteLogo() {
        // @api DELETE branding/logo
        mutate<Unit, JsonObject>("branding/logo", Unit, "DELETE")
    }

    fun emailTemplates(): List<EmailTemplate> = load {
        // @api GET branding/email-templates
        decode<EmailTemplateList>(api.execute(ApiRequest(path = "branding/email-templates"))).templates
    }

    /** `updateEmailTemplate`: upsert the override `{ subject, body_html, enabled }`. */
    fun saveTemplate(draft: TemplateDraft) {
        val key = encodePath(draft.key)
        val body = buildJsonObject {
            put("subject", draft.subject)
            put("body_html", draft.body)
            put("enabled", draft.enabled)
        }
        // @api PUT branding/email-templates/:templateKey
        mutate<JsonObject, JsonObject>("branding/email-templates/$key", body, "PUT")
    }

    /** `revertEmailTemplate`: delete the override. */
    fun revertTemplate(templateKey: String) {
        val key = encodePath(templateKey)
        // @api DELETE branding/email-templates/:templateKey
        mutate<Unit, JsonObject>("branding/email-templates/$key", Unit, "DELETE")
    }

    /** `previewEmailTemplate`: renders the draft against the org branding (no DB write). */
    fun preview(templateKey: String, subject: String, bodyHtml: String): EmailPreview {
        val key = encodePath(templateKey)
        val body = buildJsonObject {
            put("subject", subject)
            put("body_html", bodyHtml)
        }
        // @api POST branding/email-templates/:templateKey/preview
        return mutate("branding/email-templates/$key/preview", body, "POST")
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun encodePath(segment: String): String = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private inline fun <T> load(block: () -> T): T {
        try {
            return block()
        } catch (error: ApiError.Http) {
            throw AdminFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String): R {
        try {
            // OkHttp requires a body for POST/PUT/PATCH; DELETE may go without one.
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw AdminFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        val obj = json.parseToJsonElement(error.responseBody).jsonObject
        (obj["error"] ?: obj["message"])?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}

/** `(contentType, body)` of a single-file `multipart/form-data` upload under field `logo`. */
internal fun logoMultipart(logo: StagedLogo, boundary: String): Pair<String, ByteArray> {
    val safeName = logo.fileName.replace(Regex("[\\r\\n\"/\\\\]"), "_").take(255).ifBlank { "logo" }
    val body = ByteArrayOutputStream().apply {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"logo\"; filename=\"$safeName\"\r\nContent-Type: ${logo.mimeType}\r\n\r\n".toByteArray())
        write(logo.bytes)
        write("\r\n--$boundary--\r\n".toByteArray())
    }.toByteArray()
    return "multipart/form-data; boundary=$boundary" to body
}
