package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTicketViewModelTest {
    private lateinit var session: TicketSession
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }
    @After fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    private fun scope(projectId: Long = 3) = PlanScope(
        projectScoped = true, selectedProjectId = projectId,
        projects = listOf(ProjectOption(id = projectId, type = "task_list", planTabs = listOf("list", "board", "calendar"))),
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Expected project tickets did not arrive", condition())
    }

    @Test fun upcomingDrainsEveryProjectPageAndGroupsOpenStartDatesWithOverdueFirst() {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repository = TaskRepository(ApiClient { request ->
            paths += request.path
            val body = if (request.path.contains("offset=0")) """{
                "tasks":[
                    {"id":1,"title":"Overdue deadline","project_id":3,"start_date":"2026-11-07","due_date":"2026-11-08"},
                    {"id":2,"title":"Started long ago","project_id":3,"start_date":"2025-01-01"},
                    {"id":3,"title":"Done","project_id":3,"start_date":"2026-11-09","completed_at":"2026-11-09T12:00:00Z"},
                    {"id":4,"title":"Other project","project_id":4,"start_date":"2026-11-09"}
                ],"pagination":{"offset":0,"hasMore":true}
            }""" else """{
                "tasks":[
                    {"id":5,"title":"Today","project_id":3,"start_date":"2026-11-09"},
                    {"id":6,"title":"Tomorrow","project_id":3,"start_date":"2026-11-10"},
                    {"id":7,"title":"No start","project_id":3,"start_date":null,"planned_date":"2026-11-09","due_date":"2026-11-12"},
                    {"id":8,"title":"Cancelled","project_id":3,"workflow_state_key":"cancelled"}
                ],"pagination":{"offset":500,"hasMore":false}
            }"""
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        session.writePlanScope(scope())
        val vm = ProjectTicketViewModel(repository, session) { LocalDate.of(2026, 11, 9) }
        await { vm.ui.value.loaded && !vm.ui.value.loading }

        assertEquals(listOf("overdue", "2026-11-09", "2026-11-10", "no-start"), vm.ui.value.upcoming.map { it.key })
        assertEquals(listOf(listOf(2L, 1L), listOf(5L), listOf(6L), listOf(7L)), vm.ui.value.upcoming.map { it.tickets.map(Task::id) })
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L, 7L, 8L), vm.ui.value.tickets.map(Task::id))
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.startsWith("tasks?") && it.contains("scope=list") && it.contains("project_id=3") })
        assertTrue(paths.last().contains("offset=500"))
    }

    @Test fun switchingProjectsDiscardsALateCollectionResponse() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val repository = TaskRepository(ApiClient { request ->
            val project = if (request.path.contains("project_id=3")) {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                firstFinished.countDown()
                3
            } else 4
            ApiResponse(200, emptyMap(), """{"tasks":[{"id":$project,"title":"Project $project","project_id":$project}]}""".toByteArray())
        })
        val vm = ProjectTicketViewModel(repository, session)
        session.writePlanScope(scope())
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        try {
            session.writePlanScope(scope(4))
            await { vm.ui.value.loaded && vm.ui.value.tickets.singleOrNull()?.id == 4L }
            releaseFirst.countDown()
            assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
            assertEquals(4L, vm.ui.value.planScope.selectedProjectId)
            assertEquals(listOf(4L), vm.ui.value.tickets.map(Task::id))
        } finally {
            releaseFirst.countDown()
        }
    }

    @Test fun listFiltersAndSortReloadTheProjectCollectionAndFailureCanBeRetried() {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        var fail = false
        val repository = TaskRepository(ApiClient { request ->
            paths += request.path
            if (fail) error("Offline")
            ApiResponse(200, emptyMap(), """{"tasks":[{"id":7,"title":"Ship","project_id":3}]}""".toByteArray())
        })
        session.writePlanScope(scope())
        val vm = ProjectTicketViewModel(repository, session)
        await { vm.ui.value.loaded }
        session.writePlanScope(scope().copy(filters = TaskFilters(priority = "high", status = "review")))
        await { !vm.ui.value.loading && paths.last().contains("priority=high") }
        vm.setQuery(ProjectTicketQuery(sort = "due_date", direction = "desc", title = "Ship & review"))
        await { !vm.ui.value.loading && paths.last().contains("sort=due_date") }
        assertTrue(paths.last().contains("title=Ship+%26+review"))
        assertTrue(paths.last().contains("status=review"))
        fail = true
        vm.refresh()
        await { vm.ui.value.error == "Offline" }
        fail = false
        vm.refresh()
        await { !vm.ui.value.loading && vm.ui.value.error == null }
        assertEquals(listOf(7L), vm.ui.value.tickets.map(Task::id))
    }

    @Test fun metadataRefreshDuringAReadDoesNotLeaveTheCollectionLoadingForever() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val repository = TaskRepository(ApiClient {
            if (reads.incrementAndGet() > 1) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            ApiResponse(200, emptyMap(), """{"tasks":[{"id":7,"title":"Ship","project_id":3}]}""".toByteArray())
        })
        session.writePlanScope(scope())
        val vm = ProjectTicketViewModel(repository, session)
        await { vm.ui.value.loaded }
        vm.refresh()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            session.writePlanScope(scope().copy(assignableUsers = listOf(AssignableUser(id = 9, username = "Member"))))
        } finally {
            release.countDown()
        }
        await { vm.ui.value.loaded && !vm.ui.value.loading }
        assertEquals(listOf(7L), vm.ui.value.tickets.map(Task::id))
    }
}
