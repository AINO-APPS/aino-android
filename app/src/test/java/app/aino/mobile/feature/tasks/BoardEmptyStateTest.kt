package app.aino.mobile.feature.tasks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which empty state the sprint board shows.
 *
 * The board used to say "Pull tickets in from the backlog" whenever no tickets
 * were visible — including when filters were what hid them. That is wrong: the
 * tickets exist, the view is just narrowed. The two cases need different copy
 * and different actions, so the branch is decided here rather than inline in the
 * composable.
 */
class BoardEmptyStateTest {
    @Test
    fun ticketsOnTheBoardMeansNoEmptyState() {
        assertEquals(BoardEmpty.None, boardEmptyState(hasTasks = true, filterCount = 0))
    }

    @Test
    fun filtersDoNotCreateAnEmptyStateWhenTicketsAreVisible() {
        assertEquals(BoardEmpty.None, boardEmptyState(hasTasks = true, filterCount = 3))
    }

    @Test
    fun aGenuinelyEmptySprintOffersPlanning() {
        assertEquals(BoardEmpty.Empty, boardEmptyState(hasTasks = false, filterCount = 0))
    }

    @Test
    fun filtersThatHideEveryTicketAreNotAnEmptySprint() {
        assertEquals(BoardEmpty.Filtered, boardEmptyState(hasTasks = false, filterCount = 2))
    }

    // ── Drop targets ────────────────────────────────────────────────────────
    // An empty column registers bounds from both its header and its dashed
    // body. The header must keep winning: it sits above the body and is the
    // target the board's onboarding copy names ("Drop on a column").

    private val header = Rect(left = 0f, top = 100f, right = 400f, bottom = 140f)
    private val body = Rect(left = 0f, top = 144f, right = 400f, bottom = 188f)

    @Test
    fun aDropOnTheHeaderTargetsThatColumn() {
        val headers = mapOf("pending" to header)
        val bodies = mapOf("pending" to body)

        assertEquals("pending", dropTargetKey(headers, bodies, Offset(200f, 120f)))
    }

    @Test
    fun aDropOnTheEmptyBodyAlsoTargetsThatColumn() {
        val headers = mapOf("pending" to header)
        val bodies = mapOf("pending" to body)

        assertEquals("pending", dropTargetKey(headers, bodies, Offset(200f, 166f)))
    }

    @Test
    fun theHeaderWinsEvenWhenABodyOverlapsIt() {
        // A layout that wrapped the body upward must not make the header stop
        // accepting drops — that was the regression when both wrote one slot.
        val headers = mapOf("pending" to header)
        val bodies = mapOf("pending" to Rect(0f, 100f, 400f, 400f))

        assertEquals("pending", dropTargetKey(headers, bodies, Offset(200f, 110f)))
    }

    @Test
    fun aDropOutsideEveryColumnTargetsNothing() {

        assertNull(dropTargetKey(mapOf("pending" to header), mapOf("pending" to body), Offset(200f, 600f)))
    }
}
