package app.aino.mobile.feature.attendance

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationMetricsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readsTheNestedServerShape() {
        val metrics = json.decodeFromString<NotificationMetrics>(
            """{"windowHours":24,"successRate":97.5,"counts":{"totalNotifications":50,"routingAttempts":40,"successfulRoutes":39},
               "latency":{"averageMs":120,"p50Ms":90,"p95Ms":412.6},"lastEventAt":null}""",
        )
        assertEquals("97.5%", routingSuccessLabel(metrics))
        assertEquals("39/40 routes · p95 413ms", routingMeta(metrics))
    }

    @Test
    fun noAttemptsReadsNoData() {
        val metrics = json.decodeFromString<NotificationMetrics>("""{"successRate":null,"counts":{"routingAttempts":0,"successfulRoutes":0},"latency":{"p95Ms":0}}""")
        assertEquals("No data", routingSuccessLabel(metrics))
        assertEquals("0/0 routes", routingMeta(metrics))
        assertEquals("No data", routingSuccessLabel(null))
    }
}
