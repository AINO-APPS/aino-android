package app.aino.mobile.core.call

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Signal parity: the notification's Answer asks for mic/camera on the call screen before accepting. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IncomingCallViewModelTest {
    private val requests = CopyOnWriteArrayList<ApiRequest>()
    private val acceptGate = CountDownLatch(1)
    private val api = ApiClient { request ->
        requests += request
        acceptGate.await(5, TimeUnit.SECONDS)
        ApiResponse(200, emptyMap(), "{}".toByteArray())
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { acceptGate.countDown(); Dispatchers.resetMain() }

    private fun viewModel() = IncomingCallViewModel(api, RuntimeEnvironment.getApplication(), CallSessionController())

    @Test fun `answer from the notification waits for the call screen instead of accepting at once`() {
        val vm = viewModel()
        assertTrue(vm.route(parseIncomingCallRoute("aino://call/31?callId=301&callType=video&peerId=6&peerName=Priya&autoAnswer=1")!!))

        assertTrue(vm.ui.value.answeredFromNotification)
        assertEquals(IncomingCallState.Ringing, vm.ui.value.state)
        assertTrue(requests.isEmpty())
    }

    @Test fun `answer tapped while the same call is already answering is ignored`() {
        val vm = viewModel()
        val ringing = parseIncomingCallRoute("aino://call/31?callId=301&callType=voice&peerId=6")!!
        assertTrue(vm.route(ringing))
        vm.answer()
        assertEquals(IncomingCallState.Answering, vm.ui.value.state)

        vm.route(ringing.copy(action = "answer"))

        assertEquals(IncomingCallState.Answering, vm.ui.value.state)
        assertFalse(vm.ui.value.answeredFromNotification)
    }
}
