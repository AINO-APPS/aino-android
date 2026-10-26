package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sprint heading shows the sprint's number, not its stored name.
 *
 * The heading already says "Sprint", so printing the name ("Sprint #3") repeats
 * the word and hides the number. `sprint_number` is authoritative; the trailing
 * digits of the name are the fallback for sprints created before the column
 * existed.
 */
class SprintNumberTest {
    @Test
    fun usesTheStoredSprintNumber() {
        val sprint = AvailableSprint(id = 1, name = "Release train", sprintNumber = 7)
        assertEquals(7, sprint.number)
    }

    @Test
    fun fallsBackToTheTrailingDigitsOfTheName() {
        assertEquals(3, AvailableSprint(id = 1, name = "Sprint #3").number)
        assertEquals(12, AvailableSprint(id = 1, name = "Sprint 12").number)
    }

    @Test
    fun aNameWithoutADigitHasNoNumber() {
        assertNull(AvailableSprint(id = 1, name = "Release train").number)
    }

    @Test
    fun theStoredNumberWinsOverTheName() {
        val sprint = AvailableSprint(id = 1, name = "Sprint #2", sprintNumber = 9)
        assertEquals(9, sprint.number)
    }
}
