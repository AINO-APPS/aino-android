package app.aino.mobile.feature.chat

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupManagementTest {
    private val owner = ConversationMember(1, "own", "Olive Owner", role = "owner")
    private val admin = ConversationMember(2, "adm", "Adam Admin", role = "admin")
    private val member = ConversationMember(3, "mem", "Mia Member", role = "member")

    // ── permissions (mirror of the server's canDo) ───────────────────────

    @Test
    fun ownerCanDoEverything() {
        val p = GroupPermissions("owner")
        assertTrue(p.canEditInfo && p.canAddMembers && p.canRemoveMembers && p.canManageLink)
        assertTrue(p.canChangeRoles && p.canTransferOwnership && p.canChangePolicies && p.canSend)
    }

    @Test
    fun adminsManageMembersButNotRolesOrPolicies() {
        val p = GroupPermissions("admin")
        assertTrue(p.canEditInfo && p.canAddMembers && p.canRemoveMembers && p.canManageLink)
        assertFalse(p.canChangeRoles)
        assertFalse(p.canTransferOwnership)
        assertFalse(p.canChangePolicies)
    }

    @Test
    fun membersFollowTheGroupPolicies() {
        assertFalse(GroupPermissions("member", addPolicy = "admins").canAddMembers)
        assertTrue(GroupPermissions("member", addPolicy = "all").canAddMembers)
        assertTrue(GroupPermissions("member", postPolicy = "all").canSend)
        assertFalse(GroupPermissions("member", postPolicy = "admins").canSend)
        assertTrue(GroupPermissions("admin", postPolicy = "admins").canSend)
        assertFalse(GroupPermissions("member").canRemoveMembers)
        assertFalse(GroupPermissions(null).canSend)
    }

    @Test
    fun nobodyRemovesTheOwnerOrThemselves() {
        val p = GroupPermissions("admin")
        assertFalse(p.canRemove(owner, currentUserId = 2))
        assertFalse(p.canRemove(admin, currentUserId = 2))
        assertTrue(p.canRemove(member, currentUserId = 2))
    }

    @Test
    fun freshMemberRoleWinsOverTheListRow() {
        val conversation = ChatConversation(10, isGroup = true, myRole = "member", postPolicy = "admins")
        val p = GroupPermissions.of(conversation, listOf(member.copy(role = "admin")), currentUserId = 3)
        assertEquals("admin", p.role)
        assertTrue(p.canSend)
        assertEquals("member", GroupPermissions.of(conversation, emptyList(), 3).role)
    }

    @Test
    fun membersSortOwnerAdminsThenMembersAlphabetically() {
        val sorted = sortMembers(listOf(member, ConversationMember(4, fullName = "Ben", role = "member"), admin, owner))
        assertEquals(listOf(1L, 2L, 4L, 3L), sorted.map { it.id })
        assertEquals("Owner", roleBadge("owner"))
        assertNull(roleBadge("member"))
    }

    // ── repository: routes and bodies ────────────────────────────────────

    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String = { """{"ok":true}""" }) =
        ChatRepository(ApiClient { request ->
            captured += request
            ApiResponse(200, emptyMap(), body(request).toByteArray())
        })

    @Test
    fun addAndRemoveGoThroughTheGroupUpdate() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured)
        repo.addMembers(10, listOf(5, 6, 5))
        repo.removeMember(10, 3)
        assertEquals("PUT", captured[0].method)
        assertEquals("chat/conversations/10/group", captured[0].path)
        assertEquals("""{"addUserIds":[5,6]}""", captured[0].body!!.decodeToString())
        assertEquals("""{"removeUserIds":[3]}""", captured[1].body!!.decodeToString())
    }

    @Test
    fun removingTheGroupPhotoSendsAnExplicitNull() {
        val captured = mutableListOf<ApiRequest>()
        repository(captured).removeGroupAvatar(10)
        assertEquals("""{"avatar":null}""", captured.single().body!!.decodeToString())
    }

    @Test
    fun groupPhotoIsAMultipartAvatarPart() {
        val captured = mutableListOf<ApiRequest>()
        val response = repository(captured) { """{"avatar":"/uploads/tenant_1/org_1/avatars/group_x.jpg"}""" }
            .uploadGroupAvatar(10, "group.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val request = captured.single()
        assertEquals("chat/conversations/10/avatar", request.path)
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
        assertTrue(request.body!!.decodeToString().contains("name=\"avatar\"; filename=\"group.jpg\""))
        assertEquals("/uploads/tenant_1/org_1/avatars/group_x.jpg", response.avatar)
    }

    @Test
    fun inviteLinkCalls() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { """{"enabled":true,"token":"AbCdEfGhIjKlMnOpQrSt","requiresApproval":true,"pendingRequests":2}""" }
        val state = repo.updateInviteLink(10, InviteLinkUpdate(enabled = true))
        repo.resetInviteLink(10)
        assertEquals("""{"enabled":true}""", captured[0].body!!.decodeToString())
        assertEquals("chat/conversations/10/invite-link/reset", captured[1].path)
        assertEquals(2, state.pendingRequests)
        assertTrue(state.requiresApproval)
    }

    @Test
    fun joiningAndResolvingRequests() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { req ->
            when {
                req.path.endsWith("/join") -> """{"conversationId":10,"pending":true}"""
                else -> """{"ok":true,"added":true}"""
            }
        }
        assertTrue(repo.joinByInvite("AbCdEfGhIjKlMnOpQrSt").pending)
        assertTrue(repo.resolveJoinRequest(10, 7, approve = true).added)
        repo.resolveJoinRequest(10, 8, approve = false)
        assertEquals("chat/invite/AbCdEfGhIjKlMnOpQrSt/join", captured[0].path)
        assertEquals("chat/conversations/10/join-requests/7/approve", captured[1].path)
        assertEquals("chat/conversations/10/join-requests/8/deny", captured[2].path)
    }

    @Test
    fun noActiveCallIsNull() {
        val captured = mutableListOf<ApiRequest>()
        assertNull(repository(captured) { "" }.activeGroupCall(10))
        val call = repository(captured) { """{"meetingId":7,"meetingCode":"ABC","callType":"video","participants":[{"id":1,"fullName":"Ana"}]}""" }
            .activeGroupCall(10)!!
        assertEquals("ABC", call.meetingCode)
        assertEquals("Ana", call.participants.single().fullName)
    }

    @Test
    fun serverRefusalSurfacesItsMessage() {
        val repo = ChatRepository(ApiClient { throw ApiError.Http(403, """{"error":"Only the owner can change roles"}""", "PUT", "role") })
        val error = runCatching { repo.setParticipantRole(10, 3, "admin") }.exceptionOrNull()
        assertEquals("Only the owner can change roles", error?.message)
    }

    @Test
    fun inviteUrlUsesTheWebOrigin() {
        assertEquals("https://aino.example/chat/join/tok", groupInviteUrl("https://aino.example/", "tok"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val members = """[{"id":1,"full_name":"Olive","role":"owner"},{"id":3,"full_name":"Mia","role":"member"}]"""

    private fun vm(captured: MutableList<ApiRequest>, onRole: String = """{"ok":true,"role":"admin"}"""): GroupSettingsViewModel =
        GroupSettingsViewModel(
            ChatRepository(ApiClient { request ->
                captured += request
                val body = when {
                    request.path.endsWith("/members") -> members
                    request.path.endsWith("/role") -> onRole
                    request.path.startsWith("chat/search") -> """[{"id":3,"full_name":"Mia"},{"id":9,"full_name":"Noor"}]"""
                    else -> """{"ok":true}"""
                }
                ApiResponse(200, emptyMap(), body.toByteArray())
            }),
            io = dispatcher,
        )

    @Test
    fun makeAdminUpdatesTheMemberAndRefreshesTheGroup() = runTest(dispatcher) {
        val captured = mutableListOf<ApiRequest>()
        var changed = 0
        val vm = vm(captured)
        vm.bind(10, currentUserId = 1, initialMembers = emptyList(), onGroupChanged = { changed++ }, onLeft = {})
        advanceUntilIdle()
        vm.setAdmin(ConversationMember(3, fullName = "Mia"), admin = true)
        advanceUntilIdle()
        assertEquals("""{"role":"admin"}""", captured.first { it.path.endsWith("/role") }.body!!.decodeToString())
        assertEquals("Mia is now an admin", vm.ui.value.message)
        assertEquals(1, changed)
    }

    @Test
    fun addCandidatesHideExistingMembersAndSelf() = runTest(dispatcher) {
        val captured = mutableListOf<ApiRequest>()
        val vm = vm(captured)
        vm.bind(10, currentUserId = 1, initialMembers = emptyList(), onGroupChanged = {}, onLeft = {})
        advanceUntilIdle()
        vm.searchCandidates("no", debounceMs = 0)
        advanceUntilIdle()
        assertEquals(listOf(9L), vm.ui.value.candidates.map { it.id })
    }

    @Test
    fun saveInfoSendsOnlyWhatChanged() = runTest(dispatcher) {
        val captured = mutableListOf<ApiRequest>()
        val vm = vm(captured)
        vm.bind(10, currentUserId = 1, initialMembers = emptyList(), onGroupChanged = {}, onLeft = {})
        advanceUntilIdle()
        val original = ChatConversation(10, isGroup = true, groupName = "Design", groupDescription = "Old")
        vm.saveInfo(" Design ", "New text", original)
        advanceUntilIdle()
        assertEquals("""{"description":"New text"}""", captured.first { it.path.endsWith("/group") }.body!!.decodeToString())
    }
}
