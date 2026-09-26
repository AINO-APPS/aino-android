package app.aino.mobile.feature.admin

import app.aino.mobile.core.branding.Branding
import app.aino.mobile.core.designsystem.tokens.DEFAULT_BRAND_ACCENT
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.designsystem.tokens.parseBrandAccent
import app.aino.mobile.core.designsystem.tokens.toHexRgb
import app.aino.mobile.core.designsystem.tokens.withBrandAccent
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingLogicTest {
    @Test
    fun hexInputMatchesTheWebSanitiser() {
        assertEquals("#abc", sanitizeHexInput("abc", "#111111"))
        assertEquals("#", sanitizeHexInput("", "#111111"))
        assertEquals("#A1B2C3", sanitizeHexInput("#A1B2C3", "#111111"))
        assertEquals("#111111", sanitizeHexInput("#zz", "#111111"))
        assertEquals("#111111", sanitizeHexInput("#1234567", "#111111"))
    }

    @Test
    fun logoValidationUsesServerLimits() {
        assertNull(validateLogo("image/svg+xml", MAX_LOGO_BYTES.toLong()))
        assertEquals("Logo must be under 2 MB", validateLogo("image/png", MAX_LOGO_BYTES + 1L))
        assertEquals("Only image files are allowed", validateLogo("application/pdf", 10))
        assertEquals("Only image files are allowed", validateLogo(null, 10))
    }

    @Test
    fun brandingDraftTracksStagedChangesOverTheSavedRow() {
        val saved = Branding(logoUrl = "/uploads/l.png", accentColor = "#6366F1")
        val draft = BrandingDraft.from(saved)
        assertFalse(draft.dirty(saved))
        assertEquals("/uploads/l.png", draft.effectiveLogo(saved))
        assertFalse(draft.copy(accent = "#6366f1").dirty(saved))
        assertTrue(draft.copy(accent = "#10b981").accentDirty(saved))
        assertNull(draft.copy(removeLogo = true).effectiveLogo(saved))
        val bytes = byteArrayOf(9)
        assertSame(bytes, draft.copy(logo = StagedLogo("n.png", "image/png", bytes)).effectiveLogo(saved))
        assertFalse(draft.copy(accent = "#12").accentValid)
        assertEquals(DEFAULT_BRAND_ACCENT, BrandingDraft.from(Branding()).accent)
    }

    @Test
    fun templateDraftDirtinessBuiltinSwapAndLabels() {
        val t = EmailTemplate("mention", "S", "B", enabled = true, isOverridden = true, builtinSubject = "BS", builtinBodyHtml = "BB")
        val d = TemplateDraft.from(t)
        assertFalse(d.dirty(t))
        assertTrue(d.copy(enabled = false).dirty(t))
        assertEquals(TemplateDraft("mention", "BS", "BB", true), d.withBuiltin(t))
        assertEquals("You were mentioned \u00b7 Customised \u00b7 Off", emailTemplateOptionLabel(t.copy(enabled = false)))
        assertEquals("customKey", emailTemplateLabel("customKey"))
        assertTrue(canEditBranding("hr_admin") && canEditBranding("super_admin") && canEditBranding("platform_admin"))
        assertFalse(canEditBranding("manager"))
    }

    @Test
    fun previewDocumentWrapsTheFragment() {
        val doc = emailPreviewDocument("<p>Hi</p>")
        assertTrue(doc.contains("name=\"viewport\"") && doc.contains("<body><p>Hi</p></body>"))
    }

    @Test
    fun accentParsingAndPaletteOverride() {
        assertEquals(Color(0xFF10B981), parseBrandAccent(" #10b981 "))
        assertNull(parseBrandAccent("#10b98"))
        assertNull(parseBrandAccent(null))
        assertEquals("#10b981", Color(0xFF10B981).toHexRgb())
        assertSame(WebColorsDark, WebColorsDark.withBrandAccent(null))
        assertSame(WebColorsDark, WebColorsDark.withBrandAccent("#2383E2"))
        assertSame(WebColorsDark, WebColorsDark.withBrandAccent("nope"))
        val branded = WebColorsDark.withBrandAccent("#ef4444")
        assertEquals(Color(0xFFEF4444), branded.primary)
        assertEquals(Color(0xFFEF4444), branded.accent)
        assertEquals(0.30f, branded.primaryGlow.alpha, 0.01f)
    }
}
