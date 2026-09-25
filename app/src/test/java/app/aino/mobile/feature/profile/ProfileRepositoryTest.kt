package app.aino.mobile.feature.profile

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.network.TokenStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, tokens: TokenStore? = null, body: (ApiRequest) -> String) =
        ProfileRepository(
            ApiClient { request ->
                captured += request
                ApiResponse(200, emptyMap(), body(request).toByteArray())
            },
            tokens,
        )

    private val ApiRequest.text get() = body?.toString(Charsets.UTF_8)

    @Test
    fun decodesTheTenantProfileIncludingDerivedFields() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"id":3,"username":"vvronline","full_name":"Vishnu V R","email":"v@aino.org.in",
               "role":"super_admin","org_id":1,"team_id":2,"team_name":"Platform","tenant_id":7,
               "avatar":"/uploads/t7/o1/avatars/user_x.png",
               "has_reports":true,"must_change_password":false,"tenant_plan":"standard",
               "tenant_features":{"agile":true,"webhooks":false}}"""
        }

        val user = repository.load()

        assertEquals("profile", captured.single().path)
        assertEquals("Vishnu V R", user.display())
        assertEquals("/uploads/t7/o1/avatars/user_x.png", user.avatar)
        assertEquals(true, user.hasReports)
        assertEquals(true, user.tenantFeatures["agile"])
    }

    @Test
    fun profileAndEmailUseSeparatePutRoutes() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"id":3,"username":"vvronline"}""" }

        repository.updateProfile("  Vishnu V R  ", "  vvronline  ")
        repository.updateEmail("  v@aino.org.in ")

        assertEquals("PUT", captured[0].method)
        assertEquals("profile", captured[0].path)
        assertEquals("""{"full_name":"Vishnu V R","username":"vvronline"}""", captured[0].text)
        assertEquals("PUT", captured[1].method)
        assertEquals("profile/email", captured[1].path)
        assertEquals("""{"email":"v@aino.org.in"}""", captured[1].text)
    }

    @Test
    fun surfacesTheServerUniquenessMessage() {
        val repository = ProfileRepository(
            ApiClient {
                throw ApiError.Http(400, """{"error":"Username already taken"}""", "PUT", "https://next.aino.org.in/api/profile")
            },
        )
        try {
            repository.updateProfile("Vishnu", "taken")
            fail("expected a ProfileFailure")
        } catch (failure: ProfileFailure) {
            assertEquals(400, failure.statusCode)
            assertEquals("Username already taken", failure.message)
        }
    }

    @Test
    fun statusPayloadIsTheResolverCamelCaseShape() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"userId":3,"effective":"dnd","presence":"online","manualStatus":"dnd",
               "presencePreference":"auto","statusMessage":null,"statusMessageExpiresAt":null,"source":"manual"}"""
        }

        val status = repository.getStatus()
        repository.setStatus("busy")
        repository.setStatus(null)
        repository.setPresencePreference("invisible")
        repository.activityPing()

        assertEquals(3L, status.userId)
        assertEquals("dnd", status.effective)
        assertEquals("dnd", status.manualStatus)
        assertEquals("me/status", captured[0].path)
        assertEquals("PUT", captured[1].method)
        assertEquals("""{"status":"busy","message":null,"messageExpiresAt":null}""", captured[1].text)
        // Clearing sends an explicit null, which the route treats as "clear manual".
        assertEquals("""{"status":null,"message":null,"messageExpiresAt":null}""", captured[2].text)
        assertEquals("me/status/presence-preference", captured[3].path)
        assertEquals("""{"preference":"invisible"}""", captured[3].text)
        assertEquals("POST", captured[4].method)
        assertEquals("me/status/activity-ping", captured[4].path)
        // OkHttp rejects a POST without a body.
        assertEquals(0, captured[4].body!!.size)
    }

    @Test
    fun avatarUploadIsASingleAvatarMultipartPart() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"avatar":"/uploads/t7/o1/avatars/user_new.jpg"}""" }

        val response = repository.uploadAvatar("me.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val removed = run {
            captured.clear()
            repository(captured) { """{"avatar":null}""" }.removeAvatar()
        }

        assertEquals("/uploads/t7/o1/avatars/user_new.jpg", response.avatar)
        assertNull(removed.avatar)
        assertEquals("DELETE", captured.single().method)
        assertEquals("profile/avatar", captured.single().path)
    }

    @Test
    fun avatarMultipartUsesTheServerFieldName() {
        val (type, body) = buildAvatarMultipart("a\"b.png", "image/png", byteArrayOf(9), "aino-boundary")
        val text = body.toString(Charsets.ISO_8859_1)

        assertEquals("multipart/form-data; boundary=aino-boundary", type)
        assertTrue(text.contains("""name="avatar"; filename="a_b.png""""))
        assertTrue(text.contains("Content-Type: image/png"))
        assertTrue(text.endsWith("\r\n--aino-boundary--\r\n"))
    }

    @Test
    fun passwordChangeKeepsTheRotatedToken() {
        val captured = mutableListOf<ApiRequest>()
        val saved = mutableListOf<String>()
        val tokens = object : TokenStore {
            override fun getToken(): String? = null
            override fun saveToken(token: String) { saved += token }
            override fun clearToken() = Unit
        }
        val repository = repository(captured, tokens) {
            """{"message":"Password updated successfully","must_change_password":false,"token":"rotated"}"""
        }

        repository.changePassword("old-pass", "new-pass-123")

        assertEquals("profile/password", captured.single().path)
        assertEquals("""{"current_password":"old-pass","new_password":"new-pass-123"}""", captured.single().text)
        assertEquals(listOf("rotated"), saved)
    }

    @Test
    fun deleteAccountSendsThePasswordInTheBody() {
        val captured = mutableListOf<ApiRequest>()
        repository(captured) { """{"message":"Account deleted successfully"}""" }.deleteAccount("secret")

        assertEquals("DELETE", captured.single().method)
        assertEquals("profile", captured.single().path)
        assertEquals("""{"password":"secret"}""", captured.single().text)
    }

    @Test
    fun notificationPrefsFallBackToWebDefaultsAndUpdatePartially() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            if (request.method == "GET") "{}" else """{"v":1,"muteAll":true,"readReceipts":false}"""
        }

        val defaults = repository.notificationPrefs()
        val updated = repository.updateNotificationPrefs(JsonObject(mapOf("muteAll" to JsonPrimitive(true))))

        assertEquals(NotificationPrefs(), defaults)
        assertEquals(true, defaults.readReceipts)
        assertEquals(0.4, defaults.reactionVolume, 0.0)
        assertEquals("""{"muteAll":true}""", captured[1].text)
        assertEquals(true, updated.muteAll)
        assertEquals(false, updated.readReceipts)
    }

    @Test
    fun themeFaceAndBiometricRoutes() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { request ->
            when (request.path) {
                "tracker/theme" -> """{"theme":"light"}"""
                "profile/face-status" -> """{"enrolled":true,"enrolled_at":"2026-09-01T10:00:00Z"}"""
                "auth/biometric" -> """{"devices":[{"id":"7.abc","device_label":"Pixel","platform":"android","created_at":"2026-09-01T10:00:00Z","last_used_at":null}]}"""
                else -> """{"ok":true}"""
            }
        }

        assertEquals("light", repository.getTheme().theme)
        repository.setTheme("dark")
        assertEquals(true, repository.faceStatus().enrolled)
        repository.clearFaceEnrollment()
        val devices = repository.biometricDevices()
        repository.revokeBiometricDevice("7.abc")

        assertEquals("""{"theme":"dark"}""", captured[1].text)
        assertEquals("DELETE", captured[3].method)
        assertEquals("profile/face-enroll", captured[3].path)
        assertEquals("Pixel", devices.single().deviceLabel)
        assertEquals("DELETE", captured[5].method)
        assertEquals("auth/biometric/7.abc", captured[5].path)
    }
}
