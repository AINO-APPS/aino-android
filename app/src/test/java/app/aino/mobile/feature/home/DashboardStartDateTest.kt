package app.aino.mobile.feature.home

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DashboardStartDateTest {
    @Test fun myWorkGroupsCanonicalStartDatesAndFallsBackOnlyForOldServerRows() {
        val repository = DashboardRepository(ApiClient { request ->
            val body = when {
                request.path == "sprints/active" -> """{"sprint":null}"""
                request.path.startsWith("tasks?start_date=2026-11-09") -> """{"tasks":[
                    {"id":1,"title":"Canonical","assigned_to":7,"start_date":"2026-11-10","planned_date":"2026-11-09"},
                    {"id":2,"title":"Legacy","assigned_to":7,"planned_date":"2026-11-11"},
                    {"id":3,"title":"Cleared","assigned_to":7,"start_date":null,"planned_date":"2026-11-12"}
                ]}"""
                else -> """{"tasks":[]}"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })

        val work = repository.loadMyWork(7, LocalDate.of(2026, 11, 9))

        assertEquals(listOf("2026-11-10", "2026-11-11"), work.groups.map { it.key })
        assertEquals(listOf("Canonical", "Legacy"), work.groups.flatMap { it.tasks }.map { it.title })
    }
}
