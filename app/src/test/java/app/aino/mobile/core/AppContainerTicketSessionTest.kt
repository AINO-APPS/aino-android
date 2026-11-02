package app.aino.mobile.core

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.network.CacheOnlyApiClient
import app.aino.mobile.core.network.CachingApiClient
import app.aino.mobile.core.network.ResponseCache
import app.aino.mobile.feature.tasks.TaskRepository
import app.aino.mobile.feature.tasks.TaskViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerTicketSessionTest {
    private lateinit var container: AppContainer
    private val models = ViewModelStore()
    private var nextKey = 0
    @Volatile private var title = "Previous session"
    private val repository = TaskRepository(ApiClient { request ->
        val body = if (request.path in setOf("tasks/by-key/OLD-201", "tasks/201/detail")) {
            """{"id":201,"title":"$title","container":"backlog"}"""
        } else "{}"
        ApiResponse(200, emptyMap(), body.toByteArray())
    })

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = AppContainer.get(ApplicationProvider.getApplicationContext<Context>())
        container.setTicketSessionScope(null)
    }

    @After fun tearDown() {
        container.setTicketSessionScope(null)
        models.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: TaskRepository = this.repository) = TaskViewModel(
        repository, ticketSession = container.ticketSession.value,
    )
        .also { models.put("tasks-${nextKey++}", it) }

    @Test fun endingASessionDropsTicketsFromExistingAndNewScreens() {
        container.setTicketSessionScope(CacheScope(10, 1))
        val old = viewModel()
        old.openByKey("OLD-201")
        await { old.ui.value.detail?.id == 201L }

        container.setTicketSessionScope(null)
        container.setTicketSessionScope(CacheScope(10, 2))
        val next = viewModel()

        assertNull(old.ui.value.detail)
        assertTrue(old.ui.value.backlog.isEmpty())
        assertNull(next.ui.value.detail)
        assertTrue(next.ui.value.backlog.isEmpty())
    }

    @Test fun signingBackInAsTheSameUserLoadsFreshTickets() {
        val identity = CacheScope(10, 1)
        container.setTicketSessionScope(identity)
        val old = viewModel()
        old.openByKey("OLD-201")
        await { old.ui.value.detail?.title == "Previous session" }

        container.setTicketSessionScope(null)
        title = "Fresh from server"
        container.setTicketSessionScope(identity)
        val next = viewModel()
        assertTrue(next.ui.value.backlog.isEmpty())
        next.openByKey("OLD-201")

        await { next.ui.value.detail?.title == "Fresh from server" }
        assertNull(old.ui.value.detail)
    }

    @Test fun switchingTenantDoesNotExposeThePreviousTenantsTickets() {
        container.setTicketSessionScope(CacheScope(10, 1))
        val old = viewModel()
        old.openByKey("OLD-201")
        await { old.ui.value.detail?.title == "Previous session" }

        title = "Other tenant"
        container.setTicketSessionScope(CacheScope(20, 1))
        val next = viewModel()
        assertTrue(next.ui.value.backlog.isEmpty())
        assertNull(old.ui.value.detail)
        next.openByKey("OLD-201")

        await { next.ui.value.detail?.title == "Other tenant" }
        assertTrue(old.ui.value.backlog.isEmpty())
    }

    @Test fun refreshingTheSameSignedInIdentityKeepsItsTickets() {
        val identity = CacheScope(10, 1)
        container.setTicketSessionScope(identity)
        val vm = viewModel()
        vm.openByKey("OLD-201")
        await { vm.ui.value.detail?.id == 201L }

        container.setTicketSessionScope(identity)

        assertEquals("Previous session", vm.ui.value.detail?.title)
    }

    @Test fun aDifferentUserStartsANewSessionWithoutAnExplicitSignOut() {
        container.setTicketSessionScope(CacheScope(10, 1))
        val old = viewModel()
        old.openByKey("OLD-201")
        await { old.ui.value.detail?.id == 201L }

        container.setTicketSessionScope(CacheScope(10, 2))
        val next = viewModel()

        assertNull(old.ui.value.detail)
        assertTrue(old.ui.value.backlog.isEmpty())
        assertTrue(next.ui.value.backlog.isEmpty())
    }

    @Test fun anInFlightResponseCannotRepopulateAnEndedSession() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val slowRepository = TaskRepository(ApiClient {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            returned.countDown()
            ApiResponse(200, emptyMap(), """{"id":201,"title":"Late","container":"backlog"}""".toByteArray())
        })
        container.setTicketSessionScope(CacheScope(10, 1))
        val old = viewModel(slowRepository)
        old.openByKey("OLD-201")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            container.setTicketSessionScope(null)
            container.setTicketSessionScope(CacheScope(10, 2))
        } finally {
            release.countDown()
        }
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        val next = viewModel()
        next.openByKey("OLD-201")
        await { next.ui.value.detail?.id == 201L }

        assertNull(old.ui.value.detail)
        assertTrue(old.ui.value.backlog.isEmpty())
        assertEquals("Previous session", next.ui.value.detail?.title)
    }

    @Test fun signingOutCancelsPendingSearchRequests() {
        val searchRequests = AtomicInteger()
        container.setTicketSessionScope(CacheScope(10, 1))
        val vm = viewModel(TaskRepository(ApiClient {
            searchRequests.incrementAndGet()
            ApiResponse(200, emptyMap(), "[]".toByteArray())
        }))

        vm.setSearch("previous user")
        container.setTicketSessionScope(null)
        Thread.sleep(600)

        assertEquals(0, searchRequests.get())
        assertTrue(vm.ui.value.searchResults.isEmpty())
    }

    @Test fun aColdSessionRebuildsItsLastPlanFromThePerUserResponseCache() {
        val root = File("build", "ticket05-response-cache")
        root.deleteRecursively()
        try {
            val cache = ResponseCache(root).apply { scope = "10_1" }
            val recording = TaskRepository(CachingApiClient(ApiClient {
                ApiResponse(
                    200, mapOf("Content-Type" to listOf("application/json")),
                    """{"tasks":[{"id":201,"title":"Cached Plan","container":"backlog"}],"pagination":{"total":1,"hasMore":false}}"""
                        .toByteArray(),
                )
            }, cache))
            recording.loadPlanTasks()
            val reopened = ResponseCache(root).apply { scope = "10_1" }
            container.setTicketSessionScope(CacheScope(10, 1))
            val vm = viewModel(TaskRepository(CacheOnlyApiClient(reopened)))

            vm.loadBacklog()

            await { vm.ui.value.planLoaded }
            assertEquals(listOf("Cached Plan"), vm.ui.value.backlog.map { it.title })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for UI state", condition())
    }
}
