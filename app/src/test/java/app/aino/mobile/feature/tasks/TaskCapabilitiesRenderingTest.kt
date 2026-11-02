package app.aino.mobile.feature.tasks

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCapabilitiesRenderingTest {
    private val board = Json { ignoreUnknownKeys = true }.decodeFromString<SprintBoardResponse>(
        requireNotNull(javaClass.classLoader?.getResource("contracts/sprint-board.json")).readText(),
    )
    private val ticket = board.tickets.single()

    @Test
    fun aDropRequiresBothTheServerTransitionAndAnAcceptingColumn() {
        assertTrue(ticket.dropTargetDecision(102, accepting = true).allowed)

        val fullColumn = ticket.dropTargetDecision(102, accepting = false)
        assertFalse(fullColumn.allowed)
        assertEquals("WIP_EXCEEDED", fullColumn.reason)

        val unavailableTransition = ticket.dropTargetDecision(101, accepting = true)
        assertFalse(unavailableTransition.allowed)
        assertEquals("INVALID_TRANSITION", unavailableTransition.reason)
    }

    @Test
    fun missingCapabilitiesFailClosed() {
        val decision = ticket.copy(capabilities = null).dropTargetDecision(102, accepting = true)
        assertFalse(decision.allowed)
    }

    @Test
    fun rejectedWritesAndDisabledControlsUseTheSameReasonCopy() {
        assertEquals("This sprint is frozen.", capabilityReasonCopy("SPRINT_FROZEN"))
        assertEquals("This sprint is frozen.", TaskCapabilityDecision(false, "SPRINT_FROZEN").reasonCopy())
        assertEquals("This sprint is frozen.", capabilityFailureCopy("SPRINT_FROZEN"))
    }
}
