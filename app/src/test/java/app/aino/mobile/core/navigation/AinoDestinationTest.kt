package app.aino.mobile.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AinoDestinationTest {
    @Test
    fun destinationFor_matchesLabelsIgnoringCase() {
        assertEquals(AinoDestination.Home, destinationFor("home"))
        assertEquals(AinoDestination.Activity, destinationFor("ACTIVITY"))
    }

    @Test
    fun destinationFor_returnsNullForUnknownLabel() {
        assertNull(destinationFor("unknown"))
    }
}
