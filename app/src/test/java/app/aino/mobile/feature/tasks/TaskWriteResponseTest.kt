package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskWriteResponseTest {
    private val original = """
        {"id":201,"title":"Old title","priority":"low","sprint_id":10,"container":"sprint",
         "workflow_state_id":101,"workflow_state_key":"todo",
         "capabilities":{"transitions":[{"to":102,"allowed":true}],"can":{"edit":{"allowed":true}}}}
    """.trimIndent()
    private val fresh = """
        {"id":201,"title":"Server title","priority":"high","sprint_id":10,"container":"sprint",
         "workflow_state_id":101,"workflow_state_key":"todo",
         "capabilities":{"transitions":[{"to":102,"allowed":false,"reason":"SPRINT_FROZEN"}],"can":{"edit":{"allowed":false,"reason":"ACCESS_DENIED"}}}}
    """.trimIndent()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun anEditReplacesTheWholeTicketWithTheFreshWriteResponse() {
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/201/detail" -> original
                request.path == "tasks/201" && request.method == "PUT" -> fresh
                request.path == "tasks/201/history" -> "[]"
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val vm = TaskViewModel(repository)
        val task = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Task>(original)
        vm.openDetail(task)
        waitFor { vm.ui.value.detail?.title == "Old title" }

        vm.updateFields(task, buildJsonObject { put("title", "Changed") })
        waitFor { vm.ui.value.detail?.title == "Server title" }

        assertEquals("high", vm.ui.value.detail?.priority)
        assertEquals(false, vm.ui.value.detail?.capabilities?.can?.edit?.allowed)
        assertEquals("SPRINT_FROZEN", vm.ui.value.detail?.capabilities?.transitions?.single()?.reason)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }
}
