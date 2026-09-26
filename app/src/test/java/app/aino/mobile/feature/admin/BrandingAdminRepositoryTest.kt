package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrandingAdminRepositoryTest {
    @Test
    fun brandingRoutesUseWebMethodsAndBodies() {
        val captured = mutableListOf<ApiRequest>()
        val repository = BrandingAdminRepository(capturingClient(captured) { request ->
            when {
                request.path == "branding" && request.method == "GET" -> """{"logo_url":"/uploads/l.png","accent_color":"#6366f1","org_name":"Acme"}"""
                request.path == "branding" -> """{"org_id":1,"logo_url":"/uploads/l.png","accent_color":"#10b981"}"""
                request.path == "branding/logo" && request.method == "POST" -> """{"org_id":1,"logo_url":"/uploads/new.png","accent_color":"#6366f1"}"""
                else -> """{"ok":true}"""
            }
        }, boundary = { "B" })

        val branding = repository.branding()
        assertEquals("Acme", branding.orgName)
        assertEquals("#10b981", repository.updateAccent("#10b981").accentColor)
        assertEquals("/uploads/new.png", repository.uploadLogo(StagedLogo("a\"b.png", "image/png", byteArrayOf(1, 2))).logoUrl)
        repository.deleteLogo()

        assertEquals(listOf("GET branding", "PUT branding", "POST branding/logo", "DELETE branding/logo"), captured.calls())
        assertEquals("""{"accent_color":"#10b981"}""", captured[1].text())
        val upload = captured[2]
        assertEquals("multipart/form-data; boundary=B", upload.headers["Content-Type"])
        val multipart = upload.body!!.toString(Charsets.ISO_8859_1)
        assertTrue(multipart.startsWith("--B\r\nContent-Disposition: form-data; name=\"logo\"; filename=\"a_b.png\"\r\nContent-Type: image/png\r\n\r\n"))
        assertTrue(multipart.endsWith("\r\n--B--\r\n"))
        assertNull(captured[3].body)
    }

    @Test
    fun templateRoutesEncodeTheKeyAndSendTheDraft() {
        val captured = mutableListOf<ApiRequest>()
        val repository = BrandingAdminRepository(capturingClient(captured) { request ->
            when {
                request.method == "GET" -> """{"templates":[{"template_key":"leaveApproved","subject":"S","body_html":"<p>B</p>","enabled":false,"is_overridden":true,"builtin_subject":"BS","builtin_body_html":"BB"}]}"""
                request.path.endsWith("/preview") -> """{"subject":"S2","html":"<html>x</html>"}"""
                else -> """{"ok":true}"""
            }
        })

        val template = repository.emailTemplates().single()
        assertEquals("leaveApproved", template.templateKey)
        assertTrue(template.isOverridden && !template.enabled)
        assertEquals("BB", template.builtinBodyHtml)
        repository.saveTemplate(TemplateDraft("leave Approved", "Hi", "<b>x</b>", enabled = true))
        repository.revertTemplate("leaveApproved")
        assertEquals("<html>x</html>", repository.preview("leaveApproved", "S2", "<i>y</i>").html)

        assertEquals(
            listOf(
                "GET branding/email-templates",
                "PUT branding/email-templates/leave%20Approved",
                "DELETE branding/email-templates/leaveApproved",
                "POST branding/email-templates/leaveApproved/preview",
            ),
            captured.calls(),
        )
        assertEquals("""{"subject":"Hi","body_html":"<b>x</b>","enabled":true}""", captured[1].text())
        assertEquals("""{"subject":"S2","body_html":"<i>y</i>"}""", captured[3].text())
    }

    @Test
    fun failuresSurfaceTheServerError() {
        val repository = BrandingAdminRepository(ApiClient { r -> throw ApiError.Http(400, """{"error":"Only image files are allowed"}""", r.method, r.path) })
        try {
            repository.uploadLogo(StagedLogo("x.txt", "text/plain", byteArrayOf(1)))
            fail("expected failure")
        } catch (e: AdminFailure) {
            assertEquals(400, e.statusCode)
            assertEquals("Only image files are allowed", e.adminMessage("Failed to save branding"))
        }
    }
}
