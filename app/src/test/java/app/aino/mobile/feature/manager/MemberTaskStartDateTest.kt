package app.aino.mobile.feature.manager

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberTaskStartDateTest {
    @Test fun attendanceDayTasksReadStartDateWithLegacyFallbackAndExplicitClearing() {
        val repository = ManagerRepository(ApiClient { request ->
            assertEquals("manager/member/7/tasks?date=2026-11-10", request.path)
            ApiResponse(200, emptyMap(), """[
                {"id":1,"title":"Canonical","date":"2026-11-09","planned_date":"2026-11-09","start_date":"2026-11-10"},
                {"id":2,"title":"Legacy planned day","planned_date":"2026-11-10"},
                {"id":3,"title":"Old day","date":"2026-11-10"},
                {"id":4,"title":"Cleared","planned_date":"2026-11-10","start_date":null}
            ]""".toByteArray())
        })

        val rows = repository.memberTasks(7, "2026-11-10")

        assertEquals(listOf("2026-11-10", "2026-11-10", "2026-11-10", null), rows.map { it.startDate })
    }
}
