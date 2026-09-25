package app.aino.mobile.feature.notifications

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotificationsRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String = { """{"ok":true}""" }) =
        NotificationsRepository(
            ApiClient { request ->
                captured += request
                ApiResponse(200, emptyMap(), body(request).toByteArray())
            },
        )

    @Test
    fun listDecodesTheBareEnvelopeWithUnreadCount() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"notifications":[
                 {"id":12,"user_id":3,"type":"task","title":"New task","body":"Fix login","link_task_id":44,
                  "is_read":false,"created_at":"2026-09-25T06:00:00.000Z","task_title":"Fix login"},
                 {"id":11,"user_id":3,"type":"meeting_invite","title":"Standup","body":null,"link_task_id":null,
                  "is_read":true,"created_at":"2026-09-24T06:00:00.000Z","task_title":null}],
               "unread":"1","total":2,"page":1,"perPage":50}"""
        }

        val page = repository.list()

        assertEquals("GET", captured.single().method)
        assertEquals("notifications", captured.single().path)
        assertNull(captured.single().body)
        assertEquals(1, page.unread)
        assertEquals(2, page.total)
        assertEquals(2, page.notifications.size)
        val first = page.notifications[0]
        assertEquals(12L, first.id)
        assertEquals(44L, first.linkTaskId)
        assertFalse(first.isRead)
        assertEquals("Fix login", first.taskTitle)
        assertTrue(page.notifications[1].isRead)
        assertNull(page.notifications[1].body)
    }

    @Test
    fun mutationsUseTheWebRoutesWithBodylessPosts() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured)

        repository.markRead(12)
        repository.markAllRead()
        repository.delete(12)

        assertEquals(listOf("POST", "POST", "DELETE"), captured.map { it.method })
        assertEquals(listOf("notifications/12/read", "notifications/read-all", "notifications/12"), captured.map { it.path })
        assertEquals(0, captured[0].body!!.size)
        assertEquals(0, captured[1].body!!.size)
        assertNull(captured[2].body)
    }

    @Test
    fun serverErrorMessagesAreSurfaced() {
        val repository = NotificationsRepository(
            ApiClient { request ->
                throw ApiError.Http(500, """{"error":"Failed to delete notification"}""", request.method, request.path)
            },
        )
        try {
            repository.delete(1)
            fail("expected failure")
        } catch (error: NotificationsFailure) {
            assertEquals("Failed to delete notification", error.message)
            assertEquals(500, error.statusCode)
        }
    }
}
