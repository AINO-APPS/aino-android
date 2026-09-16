package app.aino.mobile.core

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TestHarnessFlowTest {
    @Test
    fun turbineObservesStateTransitions() = runTest {
        val state = MutableStateFlow("idle")

        state.test {
            assertEquals("idle", awaitItem())
            state.value = "connected"
            assertEquals("connected", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}