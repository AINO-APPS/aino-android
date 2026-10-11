package app.aino.mobile.core.call

import app.aino.mobile.feature.chat.CallLog
import org.junit.Assert.*
import org.junit.Test

class MissedCallHistoryTest {
    @Test fun historyUsesNewIncomingMissedRowsNotExistingBacklogOrAnsweredCalls() {
        val call = CallLog(id = 11, conversationId = 2, callerId = 3, status = "missed")
        assertTrue(missedHistoryEligible(call, 1, 10))
        assertFalse(missedHistoryEligible(call, 3, 10))
        assertFalse(missedHistoryEligible(call, 1, 11))
        assertTrue(missedHistoryEligible(call, 1, 11, pending = setOf(11)))
        assertFalse(missedHistoryEligible(call.copy(status = "answered"), 1, 10))
        assertFalse(missedHistoryEligible(call.copy(status = "declined"), 1, 10))
        assertFalse(missedHistoryEligible(call.copy(status = "ended", duration = 20), 1, 10))
        assertFalse(missedHistoryEligible(call.copy(conversationId = 0), 1, 10))
    }
}
