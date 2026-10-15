package app.aino.mobile.feature.chat

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.designsystem.theme.AinoTheme
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.push.VisibleThread
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var vm: ChatViewModel
    private lateinit var nav: NavHostController
    private lateinit var searchSnapshot: ChatUiState
    private var listCreations = 0
    private var listDisposals = 0
    private var openedJump: Long? = null
    private val directCreates = AtomicInteger()

    @Test fun existingChatBackRecreatesRetainedSearchThenSystemBackRestoresAllChats() = withChat {
        enterSearch()
        clickListRow("Saved chat preview")
        awaitThread(10)
        assertRetainedState()
        assertReturnedSearchThenClose()
    }

    @Test fun contactBackPreservesQueryAndResultsAfterStartingDirectChat() = withChat {
        enterSearch()
        clickListRow("@alex.contact")
        awaitThread(30)
        assertEquals(1, directCreates.get())
        assertRetainedState()
        assertReturnedSearchThenClose()
    }

    @Test fun messageResultKeepsJumpTargetAndRetainsSearchOnBack() = withChat {
        enterSearch()
        clickListRow("Alex launch details")
        awaitThread(10)
        // Capture the VM's one-shot before ChatThread consumes it to scroll.
        assertEquals(105L, openedJump)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Alex launch details").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Alex launch details").assertIsDisplayed()
        assertRetainedState()
        assertReturnedSearchThenClose()
    }

    @Test fun systemBackClosesThreadSearchBeforeThreadAndRetainedListSearch() = withChat {
        enterSearch()
        clickListRow("Saved chat preview")
        awaitThread(10)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput("launch")
        compose.waitUntil(5_000) { vm.ui.value.threadSearchMatches == listOf(105L) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 of 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("launch")).assertExists()

        systemBack()
        compose.waitUntil(5_000) { !vm.ui.value.threadSearchOpen }
        compose.runOnIdle {
            assertEquals("thread/{id}", nav.currentDestination?.route)
            assertEquals(10L, vm.ui.value.selectedConversation?.id)
            assertEquals("", vm.ui.value.threadSearchQuery)
            assertTrue(vm.ui.value.threadSearchMatches.isEmpty())
            assertNull(vm.ui.value.jumpToMessageId)
        }
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("Close search").assertDoesNotExist()
        assertRetainedState()
        assertReturnedSearchThenClose()
    }

    @Test fun clearSearchKeepsToolbarWhileCloseSearchExitsAndResetsQuery() = withChat {
        enterSearch()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle {
            assertTrue(vm.ui.value.listSearchOpen)
            assertEmptySearch()
        }
        compose.onNodeWithContentDescription("Close search").assertExists()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        compose.onNodeWithText("Search people, chats and messages").assertExists()
        showListRow("Unrelated preview").assertIsDisplayed()
        compose.onNodeWithText("@alex.contact").assertDoesNotExist()
        compose.onNodeWithText("Alex launch details").assertDoesNotExist()

        typeListQuery()
        compose.onNodeWithContentDescription("Close search").performClick()
        assertSearchClosed()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.runOnIdle { assertTrue(vm.ui.value.listSearchOpen); assertEmptySearch() }
        compose.onNodeWithText("Search people, chats and messages").assertExists()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    private fun withChat(test: () -> Unit) {
        val dao = ChatThreadCacheTest.InMemoryMessagesDao()
        val messages = (101L..125L).joinToString(",", "[", "]") { id ->
            val content = if (id == 105L) "Alex launch details" else "Thread message $id"
            """{"id":$id,"conversation_id":10,"sender_id":2,"content":"$content","created_at":"2026-10-10T10:00:${(id - 100).toString().padStart(2, '0')}Z"}"""
        }
        val messageHit = """[{"id":105,"conversation_id":10,"sender_id":2,"content":"Alex launch details","created_at":"2026-10-10T10:00:05Z"}]"""
        val api = ApiClient { request ->
            val body = when {
                request.method == "POST" && request.path == "chat/conversations" -> {
                    directCreates.incrementAndGet()
                    """{"conversationId":30}"""
                }
                request.path == "chat/conversations" -> """[
                    {"id":10,"other_user_id":2,"other_full_name":"Alex Chat","last_message":"Saved chat preview"},
                    {"id":20,"other_user_id":4,"other_full_name":"Morgan Other","last_message":"Unrelated preview"}
                    ${if (directCreates.get() > 0) ",{\"id\":30,\"other_user_id\":3,\"other_full_name\":\"Alex Contact\"}" else ""}
                ]"""
                request.path.startsWith("chat/presence?") -> "{}"
                request.path == "chat/search?q=alex" -> """[{"id":3,"full_name":"Alex Contact","username":"alex.contact"}]"""
                request.path == "chat/search-messages?q=alex" ||
                    request.path == "chat/search-messages?q=launch&convId=10" -> messageHit
                request.path.startsWith("chat/conversations/10/messages?") -> messages
                request.method == "POST" -> """{"ok":true}"""
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        }
        vm = ChatViewModel(ChatRepository(api), { scope -> ChatCache(scope, ScopedCache(scope, dao)) })
        val store = ViewModelStore().apply { put("chat", vm) }
        try {
            compose.setContent {
                AinoTheme {
                    nav = rememberNavController()
                    NavHost(
                        navController = nav,
                        startDestination = "chats",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable("chats") {
                            DisposableEffect(Unit) {
                                listCreations++
                                onDispose { listDisposals++ }
                            }
                            ChatScreen(vm, onPickDocument = {}, onOpenConversation = { id ->
                                vm.prepareConversation(id)
                                openedJump = vm.ui.value.jumpToMessageId
                                nav.navigate("thread/$id") { launchSingleTop = true }
                            })
                        }
                        composable("thread/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments!!.getLong("id")
                            // Like the app shell, NavHost handles back; disposal closes the popped thread.
                            DisposableEffect(entry) { onDispose { vm.closeConversation(id) } }
                            ChatScreen(vm, onPickDocument = {}, conversationId = id, onNavigateBack = { nav.popBackStack() })
                        }
                    }
                }
            }
            compose.runOnIdle { vm.setScope(1, 1) }
            compose.waitUntil(5_000) { vm.ui.value.conversations.size == 2 && !vm.ui.value.loading }
            test()
        } finally {
            compose.runOnIdle { vm.closeConversation(); store.clear(); VisibleThread.clear() }
        }
    }

    private fun enterSearch() {
        compose.onNodeWithContentDescription("Search").performClick()
        typeListQuery()
    }

    private fun typeListQuery() {
        compose.onNode(hasSetTextAction()).performTextInput("alex")
        compose.waitUntil(5_000) {
            val state = vm.ui.value
            !state.searching && state.userResults.map(ChatUser::id) == listOf(3L) &&
                state.messageResults.map(ChatMessage::id) == listOf(105L)
        }
        searchSnapshot = vm.ui.value
        compose.onNode(hasSetTextAction()).assertTextEquals("alex")
        showListRow("@alex.contact").assertIsDisplayed()
        showListRow("Alex launch details").assertIsDisplayed()
        compose.onNodeWithText("Unrelated preview").assertDoesNotExist()
    }

    private fun showListRow(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun clickListRow(text: String) { showListRow(text).performClick() }

    private fun awaitThread(id: Long) {
        compose.waitUntil(5_000) { vm.ui.value.selectedConversation?.id == id && !vm.ui.value.threadLoading }
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.runOnIdle { assertEquals(1, listDisposals) }
    }

    private fun assertRetainedState() = compose.runOnIdle {
        assertTrue(vm.ui.value.listSearchOpen)
        assertEquals(searchSnapshot.userSearch, vm.ui.value.userSearch)
        assertEquals(searchSnapshot.userResults, vm.ui.value.userResults)
        assertEquals(searchSnapshot.messageResults, vm.ui.value.messageResults)
    }

    private fun assertReturnedSearchThenClose() {
        systemBack()
        compose.waitUntil(5_000) { vm.ui.value.selectedConversation == null }
        compose.runOnIdle {
            assertEquals("chats", nav.currentDestination?.route)
            assertEquals(2, listCreations)
        }
        assertRetainedState()
        compose.onNodeWithContentDescription("Close search").assertExists()
        compose.onNodeWithContentDescription("Clear search").assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("alex")
        showListRow("Saved chat preview").assertIsDisplayed()
        showListRow("@alex.contact").assertIsDisplayed()
        showListRow("Alex launch details").assertIsDisplayed()
        compose.onNodeWithText("Unrelated preview").assertDoesNotExist()
        systemBack()
        assertSearchClosed()
    }

    private fun systemBack() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun assertSearchClosed() {
        compose.runOnIdle { assertFalse(vm.ui.value.listSearchOpen); assertEmptySearch() }
        compose.onNodeWithContentDescription("Close search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertExists()
        showListRow("Saved chat preview").assertIsDisplayed()
        showListRow("Unrelated preview").assertIsDisplayed()
        compose.onNodeWithText("@alex.contact").assertDoesNotExist()
        compose.onNodeWithText("Alex launch details").assertDoesNotExist()
    }

    private fun assertEmptySearch() {
        assertEquals("", vm.ui.value.userSearch)
        assertTrue(vm.ui.value.userResults.isEmpty())
        assertTrue(vm.ui.value.messageResults.isEmpty())
        assertFalse(vm.ui.value.searching)
    }
}
