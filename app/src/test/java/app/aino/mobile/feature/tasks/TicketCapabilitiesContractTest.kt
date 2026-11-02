package app.aino.mobile.feature.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

class TicketCapabilitiesContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(name: String): String =
        sharedFixture(name)?.let { String(Files.readAllBytes(it), StandardCharsets.UTF_8) }
            ?: requireNotNull(javaClass.classLoader?.getResource("contracts/$name")) {
                "missing contract fixture: contracts/$name"
            }.readText()

    private fun sharedFixture(name: String): Path? {
        val configured = System.getenv("AINO_PLATFORM_CONTRACT_FIXTURES")
            ?.let(Path::of)
            ?.takeIf(Files::isDirectory)
        if (configured != null) return configured.resolve(name).takeIf(Files::isRegularFile)

        return generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .flatMap { ancestor ->
                sequenceOf(
                    ancestor.resolve("aino-platform").resolve("contracts").resolve("fixtures"),
                    ancestor.resolve("contracts").resolve("fixtures"),
                )
            }
            .filter(Files::isDirectory)
            .mapNotNull { directory -> directory.resolve(name).takeIf(Files::isRegularFile) }
            .firstOrNull()
    }

    private fun fixtureData(name: String): JsonElement {
        val fixture = json.parseToJsonElement(payload(name))
        return (fixture as? JsonObject)?.get("data") ?: fixture
    }

    @Test
    fun ticketContractDecodesContainerAndCapabilities() {
        val ticket = json.decodeFromJsonElement<Task>(fixtureData("ticket-with-capabilities.json"))

        assertEquals(42L, ticket.id)
        assertEquals("backlog", ticket.container)
        assertFalse(ticket.capabilities!!.transitions.first().allowed)
        assertEquals("INVALID_TRANSITION", ticket.capabilities.transitions.first().reason)
        assertTrue(ticket.capabilities.can.edit.allowed)
        assertEquals("ACCESS_DENIED", ticket.capabilities.can.transfer.reason)
    }

    @Test
    fun sprintListContractDecodesPausedCurrentSprint() {
        val sprintList = json.decodeFromJsonElement<SprintListResponse>(fixtureData("sprint-list.current.json"))

        assertEquals(31L, sprintList.currentSprintId)
        assertEquals("paused", sprintList.sprints.single().status)
    }

    @Test
    fun boardContractDecodesColumnWipAndTicketCapabilities() {
        val board = json.decodeFromString<SprintBoardResponse>(payload("sprint-board.json"))

        assertEquals(10L, board.sprint.id)
        assertEquals(listOf(101L, 102L), board.columns.map { it.stateId })
        assertFalse(board.columns.first().accepting)
        assertEquals(1, board.columns.first().limit)
        assertTrue(board.columns.last().accepting)
        assertNull(board.columns.last().limit)
        assertEquals("sprint", board.tickets.single().container)
        assertEquals("IN_SPRINT", board.tickets.single().capabilities!!.can.schedule.reason)
    }
}
