package app.aino.mobile.core.push

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NotificationPresentationStoreTest {
    @Test fun socketAndFcmOrderingSurvivesRestartAndDoesNotCrossAccounts() {
        val context = RuntimeEnvironment.getApplication()
        val socket = NotificationPresentationStore(context, "1:2")
        assertFalse(socket.handled("msg:1", 1000))
        socket.record("msg:1", PostingOutcome.Posted, 1000)
        val fcmAfterRestart = NotificationPresentationStore(context, "1:2")
        assertTrue(fcmAfterRestart.handled("msg:1", 1001))
        assertFalse(NotificationPresentationStore(context, "1:3").handled("msg:1", 1001))
        assertFalse(NotificationPresentationStore(context, "2:2").handled("msg:1", 1001))
        assertFalse(fcmAfterRestart.handled("reaction:1", 1001))
        assertFalse(fcmAfterRestart.handled("msg:1", 86_401_001))
    }

    @Test fun deniedOrFailedPostingCanRetryAndVisibleOrMutedEventsStayHandled() {
        val store = NotificationPresentationStore(RuntimeEnvironment.getApplication(), "1:2")
        store.record("msg:2", PostingOutcome.Blocked("Permission"), 1000)
        assertFalse(store.handled("msg:2", 1001))
        store.record("msg:2", PostingOutcome.Failed("Post"), 1001)
        assertFalse(store.handled("msg:2", 1002))
        store.record("msg:2", PostingOutcome.Posted, 1002)
        assertTrue(store.handled("msg:2", 1003))
        store.record("msg:3", PostingOutcome.Suppressed("Visible or muted"), 1000)
        assertTrue(store.handled("msg:3", 1001))
    }
}
