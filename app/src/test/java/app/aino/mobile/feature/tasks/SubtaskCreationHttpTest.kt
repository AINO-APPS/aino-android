package app.aino.mobile.feature.tasks

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.network.OkHttpApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SubtaskCreationHttpTest {
    private val child = """{"id":8,"title":"Small step","is_subtask":true,"parent_task_id":7,"project_id":3,"sprint_id":10,"work_item_type_id":9}"""
    private val posts = CopyOnWriteArrayList<RecordedRequest>()
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private lateinit var session: TicketSession
    private lateinit var model: TicketDetailViewModel
    @Volatile private var allowed = true
    @Volatile private var created = false
    @Volatile private var reject = false
    @Volatile private var holdPost: CountDownLatch? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path == "/api/tasks/7/subtasks" -> {
                        posts.add(request)
                        holdPost?.await(5, TimeUnit.SECONDS)
                        if (reject) {
                            allowed = false
                            return MockResponse().setResponseCode(409).setBody(
                                """{"code":"CANCELLED_TERMINAL","error":"Cancelled tickets cannot be changed."}""",
                            )
                        }
                        created = true
                        child
                    }
                    request.path == "/api/tasks/7/detail" -> """{
                        "id":7,"title":"Parent","project_id":3,"sprint_id":10,"work_item_type_id":2,
                        "capabilities":{"can":{"canAddSubtask":{"allowed":$allowed,"reason":"CANCELLED_TERMINAL"}}},
                        "subtask_summary":{"total":${if (created) 1 else 0},"done":0},
                        "subtasks":[${if (created) child else ""}]
                    }"""
                    request.path?.endsWith("/history") == true -> "[]"
                    request.path?.endsWith("/worklogs") == true -> """{"worklogs":[]}"""
                    request.path == "/api/sprints/10/board" -> """{"sprint":{"id":10},"columns":[],"tickets":[]}"""
                    else -> "{}"
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        session = TicketSession()
        model = TicketDetailViewModel(7, TaskRepository(OkHttpApiClient(baseUrl = server.url("/api").toString())), session)
        models.put("detail", model)
        await { !model.ui.value.detailLoading && model.ui.value.detail != null }
    }

    @After fun tearDown() {
        holdPost?.countDown()
        models.clear()
        session.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun createUsesTheParentHttpEndpointAndRefreshesItsInheritedSubtaskEvenWithNoPriorChildren() {
        assertEquals(0, model.ui.value.detail?.subtaskSummary?.total)
        model.beginAddSubtask()
        model.setSubtaskTitle("  Small step  ")
        holdPost = CountDownLatch(1)
        model.createSubtask()
        await { posts.size == 1 }
        model.createSubtask()
        assertTrue(model.ui.value.subtaskBusy)
        holdPost!!.countDown()
        await { !model.ui.value.subtaskBusy && model.ui.value.detail?.subtaskSummary?.total == 1 }
        assertEquals(1, posts.size)
        assertEquals("POST", posts.single().method)
        assertEquals("""{"title":"Small step"}""", posts.single().body.readUtf8())
        val saved = model.ui.value.detail!!.subtasks!!.single()
        assertTrue(saved.isSubtask)
        assertEquals(7L, saved.parentTaskId)
        assertEquals(3L, saved.projectId)
        assertEquals(10L, saved.sprintId)
        assertEquals(9L, saved.workItemTypeId)
        assertNull(model.ui.value.subtaskTitle)
    }

    @Test fun staleCancellationRefusalKeepsTheDraftExplainsTheErrorAndRefreshesCapabilities() {
        model.beginAddSubtask()
        model.setSubtaskTitle("Keep this draft")
        reject = true
        model.createSubtask()
        await { model.ui.value.subtaskError != null && !model.ui.value.subtaskBusy &&
            model.ui.value.detail?.capability(TaskCapability.AddSubtask)?.allowed == false }
        assertEquals("Keep this draft", model.ui.value.subtaskTitle)
        assertEquals("Cancelled tickets cannot be changed.", model.ui.value.subtaskError)
        assertEquals(0, model.ui.value.detail?.subtaskSummary?.total)
        model.createSubtask()
        assertEquals(1, posts.size)
        model.dismissAddSubtask()
        assertNull(model.ui.value.subtaskTitle)
    }

    @Test fun deniedOrMissingCapabilityDoesNotOpenCreationOrSendARequest() {
        allowed = false
        model.refreshDetail()
        await { model.ui.value.detail?.capability(TaskCapability.AddSubtask)?.allowed == false }
        model.beginAddSubtask()
        assertNull(model.ui.value.subtaskTitle)
        model.createSubtask()
        assertTrue(posts.isEmpty())
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }
}
