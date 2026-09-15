package app.aino.mobile.core.call

import org.junit.Assert.assertEquals
import org.junit.Test

class LockScreenPolicyTest {
    @Test
    fun api27AndNewerUseActivityLockScreenApis() {
        assertEquals(
            LockScreenWindowStrategy.ACTIVITY_API,
            LockScreenPolicy.windowStrategy(27),
        )
        assertEquals(
            LockScreenWindowStrategy.ACTIVITY_API,
            LockScreenPolicy.windowStrategy(35),
        )
    }

    @Test
    fun api26UsesLegacyWindowFlags() {
        assertEquals(
            LockScreenWindowStrategy.LEGACY_FLAGS,
            LockScreenPolicy.windowStrategy(26),
        )
    }
}