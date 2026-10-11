package app.aino.mobile.core.call

import android.app.Application
import app.aino.mobile.core.push.PostingOutcome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MissedCallStateStoreTest {
    @Test fun postingAndExplicitHandlingAreIndependentPersistentAccountScopedStates() {
        val context = RuntimeEnvironment.getApplication()
        val store = MissedCallStateStore(context, "1:2")
        store.recordPosting(1, PostingOutcome.Blocked("Permission"))
        store.recordPosting(1, PostingOutcome.Failed("Post"))
        assertFalse(store.posted(1))
        store.recordPosting(1, PostingOutcome.Posted)
        assertTrue(MissedCallStateStore(context, "1:2").posted(1))
        assertFalse(store.handled(1))
        store.markHandled(2)
        assertTrue(MissedCallStateStore(context, "1:2").handled(2))
        assertFalse(store.posted(2))
        assertFalse(MissedCallStateStore(context, "2:2").posted(1))
        assertFalse(MissedCallStateStore(context, "1:3").handled(2))
    }
}
