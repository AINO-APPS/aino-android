package app.aino.mobile.feature.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.designsystem.theme.AinoTheme
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutgoingCallConfirmationTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun requestShowsTargetAndCancelStartsNoCall() {
        val api = ApiClient { ApiResponse(200, emptyMap(), "[]".toByteArray()) }
        val dao = ChatThreadCacheTest.InMemoryMessagesDao()
        val vm = ChatViewModel(ChatRepository(api), { scope -> ChatCache(scope, ScopedCache(scope, dao)) })
        val store = ViewModelStore().apply { put("chat", vm) }
        try {
            compose.setContent { AinoTheme { ChatScreen(vm, onPickDocument = {}) } }
            compose.runOnIdle {
                vm.setScope(1, 1)
                vm.setCallsEnabled(true)
                vm.startCall(ChatConversation(7, otherUserId = 2, otherFullName = "Sam"), "voice")
            }
            compose.onNodeWithText("Start voice call?").assertExists()
            compose.onNodeWithText("Call Sam?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Start voice call?").assertDoesNotExist()
            compose.runOnIdle { assertNull(vm.ui.value.pendingCall) }
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }
}
