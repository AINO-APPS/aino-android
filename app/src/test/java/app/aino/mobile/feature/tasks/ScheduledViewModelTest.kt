package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledViewModelTest {
    private lateinit var session: TicketSession

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }

    @After fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Expected UI state did not arrive", condition())
    }

    @Test fun carryingForwardRefreshesTheLoadedScheduleAfterTheWriteCompletes() {
        val carried = java.util.concurrent.atomic.AtomicBoolean()
        val today = LocalDate.now()
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/carry-forward" -> {
                    carried.set(true)
                    """{"carried":1}"""
                }
                request.path.startsWith("tasks/scheduled") -> {
                    val day = if (carried.get()) today else today.minusDays(1)
                    """{"tasks":[{"id":7,"title":"Ship","container":"scheduled","planned_date":"$day"}]}"""
                }
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val scheduled = ScheduledViewModel(repository, session)
        scheduled.loadScheduled()
        await { scheduled.ui.value.scheduled.singleOrNull()?.plannedDate == today.minusDays(1).toString() }

        scheduled.carryForward()

        await { scheduled.ui.value.scheduled.singleOrNull()?.plannedDate == today.toString() }
        assertEquals(today.toString(), scheduled.ui.value.scheduled.single().plannedDate)
    }

    @Test fun carryForwardRunsOncePerDayWithoutNeedingTheShell() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val repository = TaskRepository(ApiClient { request ->
            if (request.path == "tasks/carry-forward") requests.incrementAndGet()
            ApiResponse(200, emptyMap(), """{"carried":0}""".toByteArray())
        })
        val scheduled = ScheduledViewModel(repository, session)

        scheduled.carryForward()
        await { requests.get() == 1 }
        scheduled.carryForward()

        assertEquals(1, requests.get())
    }

    @Test fun scheduledFocusIsConsumedOnceWithoutBeingRestoredByReload() {
        val repository = TaskRepository(ApiClient { request ->
            ApiResponse(200, emptyMap(), (if (request.path.startsWith("tasks/scheduled")) """{"tasks":[]}""" else "{}").toByteArray())
        })
        val shell = TaskViewModel(repository, ticketSession = session)
        val scheduled = ScheduledViewModel(repository, session)
        shell.openSearch()
        scheduled.showScheduled("2026-11-03")
        await { scheduled.ui.value.scheduledLoaded && shell.ui.value.tab == TaskTab.Scheduled }
        assertEquals("2026-11-03", scheduled.ui.value.scheduledFocus)
        assertEquals(false, shell.ui.value.searchOpen)

        scheduled.consumeScheduledFocus()
        scheduled.loadScheduled()

        assertEquals(null, scheduled.ui.value.scheduledFocus)
        scheduled.consumeScheduledFocus()
        assertEquals(null, scheduled.ui.value.scheduledFocus)
    }

    @Test fun schedulingFromBacklogShowsTicketInScheduledAndRemovesItFromBacklog() {
        val day = LocalDate.now().toString()
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/7/schedule" ->
                    """{"id":7,"title":"Ship","container":"scheduled","planned_date":"$day","capabilities":{"can":{"schedule":{"allowed":true}}}}"""
                request.path.startsWith("tasks/backlog") ->
                    """{"tasks":[{"id":7,"title":"Ship","container":"backlog","capabilities":{"can":{"schedule":{"allowed":true}}}}]}"""
                request.path.startsWith("tasks/scheduled") -> """{"tasks":[]}"""
                request.path == "tasks/carry-forward" -> """{"carried":0}"""
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TaskViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        val scheduled = ScheduledViewModel(repository, session)
        shell.bind(1, "employee", null, agileEnabled = false)
        scheduled.loadScheduled()
        await { plan.ui.value.backlog.size == 1 && scheduled.ui.value.scheduledLoaded }

        scheduled.schedule(7, day)

        await { scheduled.ui.value.scheduled.size == 1 && plan.ui.value.backlog.isEmpty() }
        assertEquals(7L, scheduled.ui.value.scheduled.single().id)
        assertEquals(day, scheduled.ui.value.scheduled.single().plannedDate)
        assertEquals(0, plan.ui.value.backlogTotal)
    }

    @Test fun unschedulingRequiresConfirmationAndUndoRestoresThePlannedDay() {
        val day = LocalDate.now().toString()
        val planned = java.util.concurrent.atomic.AtomicBoolean(true)
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/7/unschedule" -> {
                    planned.set(false)
                    """{"id":7,"title":"Ship","container":"backlog","capabilities":{"can":{"schedule":{"allowed":true}}}}"""
                }
                request.path == "tasks/7/schedule" -> {
                    planned.set(true)
                    """{"id":7,"title":"Ship","container":"scheduled","planned_date":"$day","capabilities":{"can":{"schedule":{"allowed":true}}}}"""
                }
                request.path.startsWith("tasks/scheduled") ->
                    """{"tasks":[{"id":7,"title":"Ship","container":"scheduled","planned_date":"$day","capabilities":{"can":{"schedule":{"allowed":true}}}}]}"""
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val scheduled = ScheduledViewModel(repository, session)
        val notices = java.util.concurrent.CopyOnWriteArrayList<TaskNotice>()
        val collector = CoroutineScope(Dispatchers.Main).launch { session.notices.collect { notices += it } }
        try {
            scheduled.loadScheduled()
            await { scheduled.ui.value.scheduled.size == 1 }
            scheduled.unschedule(7, "Ship")
            assertTrue(planned.get())
            assertEquals("Move to Backlog", scheduled.ui.value.confirm?.title)

            scheduled.acceptConfirm()

            await { scheduled.ui.value.scheduled.isEmpty() && notices.any { it.actionLabel == "Undo" } }
            assertEquals(null, scheduled.ui.value.confirm)
            notices.first { it.actionLabel == "Undo" }.action!!.invoke()
            await { scheduled.ui.value.scheduled.singleOrNull()?.plannedDate == day }
            assertTrue(planned.get())
        } finally {
            collector.cancel()
        }
    }

    @Test fun overlaysLoadForTheVisibleWindowAndToggleIndependently() {
        val overlayPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path.startsWith("tasks/schedule-overlays") -> {
                    overlayPaths += request.path
                    """{"holidays":[{"date":"2026-11-03","name":"Holiday"}]}"""
                }
                request.path.startsWith("tasks/scheduled") -> """{"tasks":[]}"""
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val scheduled = ScheduledViewModel(repository, session)
        scheduled.loadScheduled()
        await { scheduled.ui.value.scheduledLoaded && !scheduled.ui.value.overlays.isEmpty }
        val (from, to) = scheduled.ui.value.window
        assertTrue(overlayPaths.single().contains("from=$from"))
        assertTrue(overlayPaths.single().contains("to=$to"))

        scheduled.toggleOverlayType("holiday")

        assertEquals(setOf("leave", "meeting"), scheduled.ui.value.overlayTypes)
        scheduled.loadScheduled()
        assertEquals(setOf("leave", "meeting"), scheduled.ui.value.overlayTypes)
        scheduled.toggleOverlayType("holiday")
        assertEquals(setOf("leave", "holiday", "meeting"), scheduled.ui.value.overlayTypes)
    }
}
