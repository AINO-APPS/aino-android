package app.aino.mobile.feature.manager

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerRequestDetailTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val captured = mutableListOf<String>()
    private var pendingOverride: String? = null
    private var allOverride: String? = null

    private val pending = """[{"id":5,"type":"overtime","status":"pending","requester_name":"Asha","reason":"Release",
        "created_at":"2026-10-01T10:00:00.000Z","metadata":{"date":"2026-09-30","hours":2.5}}]"""
    private val all = """[{"id":5,"type":"overtime","status":"pending","created_at":""},
        {"id":9,"type":"manual_entry","status":"approved","requester_name":"Ravi","created_at":"2026-10-01T10:00:00Z",
         "reviewed_at":"2026-10-02T08:30:00Z","metadata":{"date":"2026-09-29","clock_in":"09:00","clock_out":"18:00",
         "work_mode":"office","edit":true,"breaks":[{"start":"13:00","end":"13:30"}]}}]"""
    private val mine = """[{"id":11,"type":"leave","status":"rejected","approver_name":"Meera","reject_reason":"Busy week",
        "created_at":"2026-10-01T10:00:00Z","metadata":{"leave_type":"casual","date":"2026-10-10","duration":"full_day"}}]"""

    private fun viewModel() = ManagerViewModel(
        ManagerRepository(
            ApiClient { request: ApiRequest ->
                captured += "${request.method} ${request.path}"
                val body = when {
                    request.path.startsWith("manager/approvals?status=pending") -> pendingOverride ?: pending
                    request.path.startsWith("manager/approvals?status=all") -> allOverride ?: all
                    request.path.startsWith("manager/my-requests") -> mine
                    request.path.startsWith("manager/approvals/") -> """{"message":"ok"}"""
                    request.path.startsWith("manager/approvals") -> pending
                    else -> "[]"
                }
                ApiResponse(200, emptyMap(), body.toByteArray())
            },
        ),
        ioDispatcher = dispatcher,
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun overtimeHoursDecodeFromAJsonNumber() {
        val rows = json.decodeFromString<List<ApprovalRow>>(pending)
        assertEquals("2.5", rows.single().metadata?.hours)
        assertEquals("Release", rows.single().reason)
        assertEquals("3", json.decodeFromString<RequestMetadata>("""{"hours":"3"}""").hours)
        assertNull(json.decodeFromString<RequestMetadata>("""{"hours":null}""").hours)
    }

    @Test
    fun tabParamsFollowTheWebKeys() {
        assertEquals(ManagerTab.Approvals, ManagerTab.fromParam("approvals"))
        assertEquals(ManagerTab.Requests, ManagerTab.fromParam("requests"))
        assertEquals(ManagerTab.Attendance, ManagerTab.fromParam("team"))
        assertEquals(ManagerTab.Attendance, ManagerTab.fromParam("attendance"))
        assertEquals(ManagerTab.Analytics, ManagerTab.fromParam("Analytics"))
        assertNull(ManagerTab.fromParam(""))
        assertNull(ManagerTab.fromParam("payroll"))
    }

    @Test
    fun detailFieldsListEveryPopulatedValue() {
        val row = json.decodeFromString<List<ApprovalRow>>(all)[1]
        val fields = requestDetailFields(row, ZoneOffset.UTC).toMap()
        assertEquals("Manual entry", fields["Type"])
        assertEquals("Approved", fields["Status"])
        assertEquals("09:00", fields["Clock in"])
        assertEquals("18:00", fields["Clock out"])
        assertEquals("Office", fields["Work mode"])
        assertEquals("13:00 – 13:30", fields["Breaks"])
        assertEquals("Edit of an existing day", fields["Change"])
        assertTrue(fields.getValue("Reviewed").isNotBlank())
        assertFalse("Reason" in fields)

        val leave = requestDetailFields(json.decodeFromString<List<ApprovalRow>>(mine).single(), ZoneOffset.UTC).toMap()
        assertEquals("Casual", leave["Leave type"])
        assertEquals("Full day", leave["Duration"])
        assertEquals("Busy week", leave["Rejection reason"])
        assertEquals("Meera", leave["Approver"])
    }

    @Test
    fun timestampsFallBackToRawText() {
        assertNull(formatRequestTimestamp(""))
        assertEquals("yesterday", formatRequestTimestamp("yesterday"))
        assertTrue(formatRequestTimestamp("2026-10-01 10:00:00", ZoneOffset.UTC)!!.contains("2026"))
    }

    @Test
    fun deepLinkOpensAPendingApprovalWithActions() {
        val vm = viewModel()
        vm.openDeepLink("approvals", "5")
        val detail = vm.ui.value.detail!!
        assertEquals(ManagerTab.Approvals, vm.ui.value.tab)
        assertEquals(5L, detail.row?.id)
        assertFalse(detail.loading)
        assertTrue(detail.actionable)
        // Same link again (recomposition / resume) does not re-open a dismissed sheet.
        vm.closeRequest()
        vm.openDeepLink("approvals", "5")
        assertNull(vm.ui.value.detail)
    }

    @Test
    fun deepLinkToAnAlreadyHandledRequestShowsItsOutcome() {
        val vm = viewModel()
        vm.openDeepLink(null, "9")
        val detail = vm.ui.value.detail!!
        assertEquals("approved", detail.row?.status)
        assertFalse(detail.actionable)
        assertFalse(detail.notFound)
        assertTrue(captured.any { it.startsWith("GET manager/approvals?status=all") })
    }

    @Test
    fun deepLinkToAnUnknownRequestIsNotFound() {
        val vm = viewModel()
        vm.openDeepLink("approvals", "404")
        assertTrue(vm.ui.value.detail!!.notFound)
        assertNull(vm.ui.value.detail!!.row)
    }

    @Test
    fun myRequestsDeepLinkIsReadOnly() {
        val vm = viewModel()
        vm.openDeepLink("requests", "11")
        assertEquals(ManagerTab.Requests, vm.ui.value.tab)
        assertEquals(RequestSource.Mine, vm.ui.value.detail?.source)
        assertFalse(vm.ui.value.detail!!.actionable)
    }

    @Test
    fun aRequestCancelledWhileOpenLosesItsActions() {
        val vm = viewModel()
        vm.openDeepLink("approvals", "5")
        assertTrue(vm.ui.value.detail!!.actionable)
        // Requester withdrew it: approval_update { type: "leave", status: "cancelled" } → manager.refresh().
        pendingOverride = "[]"
        allOverride = "[]"
        vm.refresh()
        val detail = vm.ui.value.detail!!
        assertTrue(detail.notFound)
        assertNull(detail.row)
        assertFalse(detail.actionable)
    }

    @Test
    fun approvalsTabLinkWithoutARequestOnlySelectsTheTab() {
        val vm = viewModel()
        vm.openDeepLink("approvals", null)
        assertEquals(ManagerTab.Approvals, vm.ui.value.tab)
        assertNull(vm.ui.value.detail)
    }

    @Test
    fun approvingFromTheSheetClosesIt() {
        val vm = viewModel()
        vm.openDeepLink("approvals", "5")
        vm.approve(5)
        assertNull(vm.ui.value.detail)
        assertTrue("POST manager/approvals/5/approve" in captured)
    }

    // ── Own requests: only super_admin / platform_admin may decide them ─────

    private val ownPending = ApprovalRow(id = 30, type = "manual_entry", status = "pending", requesterId = 7, approverName = "Meera")

    @Test
    fun ownPendingRequestIsDecidableOnlyForSelfApprovers() {
        assertTrue(canDecideRequest(ownPending, "super_admin", 7))
        assertTrue(canDecideRequest(ownPending, "platform_admin", 7))
        assertFalse(canDecideRequest(ownPending, "hr_admin", 7))
        assertFalse(canDecideRequest(ownPending, "manager", 7))
        // Someone else's request keeps the existing rule for every approver role.
        assertTrue(canDecideRequest(ownPending, "manager", 8))
        assertTrue(canDecideRequest(ownPending, "hr_admin", 8))
        // Unknown viewer id: not treated as own.
        assertTrue(canDecideRequest(ownPending, "manager", null))
        assertFalse(canDecideRequest(ownPending.copy(status = "approved"), "super_admin", 7))
    }

    @Test
    fun ownRequestWaitingLabelNamesTheApprover() {
        assertEquals("Waiting for approval by Meera", awaitingApprovalLabel(ownPending, "manager", 7, RequestSource.Approvals))
        assertEquals("Waiting for approval", awaitingApprovalLabel(ownPending.copy(approverName = null), "hr_admin", 7, RequestSource.Mine))
        assertNull(awaitingApprovalLabel(ownPending, "super_admin", 7, RequestSource.Approvals))
        assertNull(awaitingApprovalLabel(ownPending, "manager", 8, RequestSource.Approvals))
        assertNull(awaitingApprovalLabel(ownPending.copy(status = "rejected"), "manager", 7, RequestSource.Mine))
    }

    @Test
    fun detailActionsFollowTheViewerRoleOnOwnRequests() {
        val queue = RequestDetail(30, RequestSource.Approvals, row = ownPending)
        val mine = RequestDetail(30, RequestSource.Mine, row = ownPending)
        val superAdmin = ManagerUiState(role = "super_admin", userId = 7)
        assertTrue(superAdmin.detailActionable(queue))
        assertTrue(superAdmin.detailActionable(mine))
        listOf("hr_admin", "manager").forEach { role ->
            val ui = ManagerUiState(role = role, userId = 7)
            assertFalse(ui.detailActionable(queue))
            assertFalse(ui.detailActionable(mine))
        }
        // Another member's pending request stays actionable for a manager.
        assertTrue(ManagerUiState(role = "manager", userId = 8).detailActionable(queue))
    }

    @Test
    fun selectAllSkipsOwnRequestsTheViewerCannotDecide() {
        val ownJson = """[{"id":5,"type":"overtime","status":"pending","requester_id":7},
            {"id":6,"type":"leave","status":"pending","requester_id":8}]"""
        pendingOverride = ownJson
        val vm = viewModel()
        vm.bind("manager", 7)
        vm.selectTab(ManagerTab.Approvals)
        vm.toggleApprovalSelectAll()
        assertEquals(setOf(6L), vm.ui.value.selectedApprovalIds)
    }

    @Test
    fun forbiddenSelfApprovalShowsTheServerMessage() {
        val vm = ManagerViewModel(
            ManagerRepository(
                ApiClient { request: ApiRequest ->
                    if (request.method == "POST") {
                        throw ApiError.Http(403, """{"error":"You cannot approve your own request"}""", request.method, request.path)
                    }
                    ApiResponse(200, emptyMap(), pending.toByteArray())
                },
            ),
            ioDispatcher = dispatcher,
        )
        vm.openDeepLink("approvals", "5")
        vm.approve(5)
        assertEquals("You cannot approve your own request", vm.ui.value.detail?.error)

        // Without the sheet open the message lands above the list.
        vm.closeRequest()
        vm.approve(5)
        assertEquals("You cannot approve your own request", vm.ui.value.approvals.error)
    }

    @Test
    fun bulkApproveClearsSelectionAndConfirms() {
        val vm = viewModel()
        vm.toggleApprovalSelect(5)
        vm.bulkApprove()
        assertTrue("POST manager/approvals/bulk" in captured)
        assertTrue(vm.ui.value.selectedApprovalIds.isEmpty())
        assertEquals("ok", vm.ui.value.bulkMessage)
        // Changing the selection again drops the stale confirmation.
        vm.toggleApprovalSelect(5)
        assertNull(vm.ui.value.bulkMessage)
    }
}