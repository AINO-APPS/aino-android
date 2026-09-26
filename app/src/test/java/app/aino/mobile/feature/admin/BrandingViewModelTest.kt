package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrandingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val captured = mutableListOf<ApiRequest>()
    private var refreshed = 0
    private var overridden = true

    private fun vm(role: String = "hr_admin") = BrandingViewModel(
        BrandingAdminRepository(capturingClient(captured) { r ->
            when {
                r.path == "branding" && r.method == "GET" -> """{"logo_url":"/uploads/l.png","accent_color":"#6366f1","org_name":"Acme"}"""
                r.path == "branding" -> """{"org_id":1,"logo_url":null,"accent_color":"#10b981"}"""
                r.path == "branding/email-templates" -> """{"templates":[
                    {"template_key":"leaveApproved","subject":"S","body_html":"B","enabled":true,"is_overridden":$overridden,"builtin_subject":"BS","builtin_body_html":"BB"},
                    {"template_key":"mention","subject":"M","body_html":"MB","enabled":true}]}"""
                r.path.endsWith("/preview") -> """{"subject":"x","html":"<p>rendered</p>"}"""
                else -> """{"ok":true}"""
            }
        }, boundary = { "B" }),
        onBrandingChanged = { refreshed++ },
        background = dispatcher,
    ).also { it.bind(role) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun saveBrandingRemovesTheLogoThenSavesTheAccentAndRefreshesAppWide() = runTest(dispatcher) {
        val vm = vm()
        vm.loadBranding(); runCurrent()
        assertEquals("#6366f1", vm.ui.value.draft.accent)
        vm.setAccent("10b981")
        vm.stageRemoveLogo()
        vm.saveBranding(); runCurrent()

        assertEquals(listOf("GET branding", "DELETE branding/logo", "PUT branding"), captured.calls())
        val saved = vm.ui.value.branding.data!!
        assertNull(saved.logoUrl)
        assertEquals("#10b981", saved.accentColor)
        assertEquals("Acme", saved.orgName)
        assertFalse(vm.ui.value.draft.dirty(saved))
        assertEquals(1, refreshed)
        assertEquals(AdminNotice(true, "Branding saved"), vm.ui.value.notice)
    }

    @Test
    fun invalidInputIsRejectedAndReadOnlyRolesCannotStage() = runTest(dispatcher) {
        val vm = vm()
        vm.loadBranding(); runCurrent()
        vm.stageLogo(StagedLogo("a.pdf", "application/pdf", byteArrayOf(1)))
        assertEquals("Only image files are allowed", vm.ui.value.notice?.text)
        assertFalse(vm.ui.value.draft.logoDirty())

        val readOnly = vm("manager")
        readOnly.loadBranding(); runCurrent()
        readOnly.setAccent("#ef4444")
        readOnly.stageRemoveLogo()
        assertFalse(readOnly.ui.value.draft.dirty(readOnly.ui.value.branding.data!!))
    }

    @Test
    fun templatesSelectFirstDebouncePreviewAndSaveTheDraft() = runTest(dispatcher) {
        val vm = vm()
        vm.loadTemplates(); runCurrent()
        assertEquals("leaveApproved", vm.ui.value.selectedKey)
        advanceTimeBy(BrandingViewModel.PREVIEW_DEBOUNCE_MS + 1); runCurrent()
        assertEquals("<p>rendered</p>", vm.ui.value.preview?.html)

        captured.clear()
        vm.editTemplate { it.copy(subject = "N") }
        vm.editTemplate { it.copy(subject = "Ne") }
        vm.editTemplate { it.copy(subject = "New") }
        advanceTimeBy(BrandingViewModel.PREVIEW_DEBOUNCE_MS + 1); runCurrent()
        assertEquals(listOf("POST branding/email-templates/leaveApproved/preview"), captured.calls())
        assertTrue(captured.single().text().contains("\"subject\":\"New\""))

        captured.clear()
        vm.saveTemplate(); runCurrent()
        assertEquals("PUT branding/email-templates/leaveApproved", captured.calls().first())
        assertEquals("""{"subject":"New","body_html":"B","enabled":true}""", captured.first().text())
        assertEquals("Template saved", vm.ui.value.notice?.text)
    }

    @Test
    fun switchingTemplatesDropsTheDraftAndRevertRefetches() = runTest(dispatcher) {
        val vm = vm()
        vm.loadTemplates(); runCurrent()
        vm.editTemplate { it.copy(body = "changed") }
        vm.selectTemplate("mention")
        assertEquals("MB", vm.ui.value.templateDraft?.body)

        vm.selectTemplate("leaveApproved")
        captured.clear()
        overridden = false
        vm.revertTemplate(); runCurrent()
        assertEquals(listOf("DELETE branding/email-templates/leaveApproved", "GET branding/email-templates"), captured.calls().take(2))
        assertFalse(vm.ui.value.selected!!.isOverridden)
        assertEquals("S", vm.ui.value.templateDraft?.subject)
        assertEquals("Template reverted to the built-in", vm.ui.value.notice?.text)
    }

    @Test
    fun blankSubjectIsRejectedWithTheServerCopy() = runTest(dispatcher) {
        val vm = vm()
        vm.loadTemplates(); runCurrent()
        vm.editTemplate { it.copy(subject = "  ") }
        captured.clear()
        vm.saveTemplate(); runCurrent()
        assertTrue(captured.none { it.method == "PUT" })
        assertEquals("subject is required (max 200 chars)", vm.ui.value.notice?.text)
    }
}
