package app.aino.mobile.feature.attendance

import androidx.test.core.app.ApplicationProvider
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** First remote clock-in enrolls fingerprint / PIN inline; the sheet must never stay stuck Authenticating. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttendanceEnrollmentFlowTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun remoteSession(): AttendanceViewModel {
        val repository = AttendanceRepository(ApiClient { request ->
            val body = if (request.path == "org/current") """{"attendance_verification_enabled":true}""" else "{}"
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val vm = AttendanceViewModel(repository, LocationProvider(ApplicationProvider.getApplicationContext()))
        vm.setWorkMode(WorkMode.Remote)
        vm.prepare(AttendanceAction.ClockIn, onPermissionRequired = {})
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.ui.value.verifySession == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val session = vm.ui.value.verifySession
        assertNotNull(session)
        assertEquals(VerifyStep.Ready, session!!.step)
        assertEquals(1, session.promptToken)
        return vm
    }

    @Test
    fun enrollmentSuccessRelaunchesTheVerifyPrompt() {
        val vm = remoteSession()
        vm.identityPromptShown() // setup prompt
        assertEquals(VerifyStep.Authenticating, vm.ui.value.verifySession!!.step)

        vm.identityEnrollmentFinished()

        val session = vm.ui.value.verifySession!!
        assertEquals(VerifyStep.Ready, session.step)
        assertEquals(2, session.promptToken)
        assertNull(session.submitError)
    }

    @Test
    fun enrollmentFailureShowsAnErrorAndAllowsCancel() {
        val vm = remoteSession()
        vm.identityPromptShown()

        vm.reportVerifyError("Server said no", VerifyFix.EnableFingerprint, "Fingerprint Not Enabled")

        val session = vm.ui.value.verifySession!!
        assertEquals(VerifyStep.Ready, session.step)
        assertEquals("Server said no", session.submitError?.message)
        vm.dismissVerify()
        assertNull(vm.ui.value.verifySession)
    }
}
