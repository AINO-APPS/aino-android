package app.aino.mobile.feature.attendance

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceRepositoryTest {
    @Test
    fun loadsHistoryWithVisibleGridRange() {
        var captured: ApiRequest? = null
        val repository = AttendanceRepository(ApiClient { request ->
            captured = request
            ApiResponse(
                200,
                emptyMap(),
                """[{"date":"2026-09-14","floorMinutes":480,"breakMinutes":30,"totalMinutes":510,"workMode":"office","entries":[]}]""".toByteArray(),
            )
        })

        val result = repository.loadHistory(MonthRange(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 10, 10)))

        assertEquals("tracker/history?from=2026-08-30&to=2026-10-10", captured?.path)
        assertEquals(480, result.single().floorMinutes)
        assertEquals("office", result.single().workMode)
    }

    @Test
    fun nullOrganizationUsesSafeDefaultPolicy() {
        val repository = AttendanceRepository(ApiClient {
            ApiResponse(200, emptyMap(), "null".toByteArray())
        })
        assertEquals(false, repository.loadPolicy().verificationEnabled)
        assertEquals("1,2,3,4,5", repository.loadPolicy().workDays)
    }
}