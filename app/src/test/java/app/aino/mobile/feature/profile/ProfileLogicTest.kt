package app.aino.mobile.feature.profile

import app.aino.mobile.core.designsystem.component.StatusGlyph
import app.aino.mobile.core.designsystem.component.avatarInitials
import app.aino.mobile.core.designsystem.component.profileStatusVisual
import app.aino.mobile.core.media.resolveServerMediaUrl
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLogicTest {
    @Test
    fun mirrorsTheServerProfileEditRules() {
        assertNull(validateProfileEdit("Vishnu V R", "vvronline"))
        assertEquals("Name and username are required", validateProfileEdit("  ", "vvronline"))
        assertEquals("Full name must be 100 characters or less", validateProfileEdit("x".repeat(101), "vvronline"))
        assertEquals("Username must be between 3 and 30 characters", validateProfileEdit("Vishnu", "vv"))
        assertEquals(
            "Username may only contain letters, numbers, dots, underscores and hyphens",
            validateProfileEdit("Vishnu", "vv online"),
        )
    }

    @Test
    fun usernameInputIsLowercasedWithoutWhitespaceLikeTheWebForm() {
        assertEquals("vvronline", normalizeUsernameInput("VVR Online"))
    }

    @Test
    fun mirrorsTheServerEmailRule() {
        assertNull(validateEmail("vishnu@aino.org.in"))
        assertEquals("Email is required", validateEmail("   "))
        assertEquals("Invalid email address", validateEmail("vishnu@aino"))
    }

    @Test
    fun passwordChecksUseTheWebCopy() {
        assertEquals("New passwords do not match", validateProfilePassword("abcdefgh", "abcdefgx"))
        assertEquals("Password must be at least 8 characters", validateProfilePassword("short", "short"))
        assertNull(validateProfilePassword("longenough", "longenough"))
    }

    @Test
    fun avatarsWithinTheWebLimitsUploadUntouched() {
        assertTrue(avatarUploadableAsIs("image/png", 1024))
        assertTrue(avatarUploadableAsIs("image/jpeg", MAX_AVATAR_BYTES))
        assertFalse(avatarUploadableAsIs("image/jpeg", MAX_AVATAR_BYTES + 1))
        assertFalse(avatarUploadableAsIs("image/heic", 1024))
        assertFalse(avatarUploadableAsIs("image/png", -1))
    }

    @Test
    fun signOutGuardMatchesProfileMenu() {
        assertEquals(SignOutPlan.SignOut, signOutPlan("logged_out", "office"))
        assertEquals(SignOutPlan.SignOut, signOutPlan(null, null))
        assertEquals(SignOutPlan.ClockOutThenSignOut, signOutPlan("on_floor", "remote"))
        assertEquals(SignOutPlan.ClockOutThenSignOut, signOutPlan("on_break", "hybrid"))
        val blocked = signOutPlan("on_floor", "office")
        assertTrue(blocked is SignOutPlan.Blocked)
        assertTrue((blocked as SignOutPlan.Blocked).message.startsWith("Please clock out from the Work Timer"))
    }

    @Test
    fun statusVisualsFollowTheNavbarMap() {
        assertEquals("Available", profileStatusVisual("available").label)
        assertEquals("Working", profileStatusVisual("available", "on_floor", "office").label)
        assertEquals("Working Remotely", profileStatusVisual("available", "on_floor", "remote").label)
        assertEquals(StatusGlyph.Minus, profileStatusVisual("dnd").glyph)
        assertEquals("Away", profileStatusVisual("brb").label)
        assertEquals("Away", profileStatusVisual("away").label)
        assertTrue(profileStatusVisual("offline").ring)
        assertEquals("In a Meeting", profileStatusVisual("in_meeting").label)
        // Unknown values fall back to available, like `STATUS_META_MAP[x] || available`.
        assertEquals(StatusGlyph.Check, profileStatusVisual("bogus").glyph)
    }

    @Test
    fun initialsAndAvatarUrls() {
        assertEquals("VV", avatarInitials("Vishnu V R"))
        assertEquals("?", avatarInitials("  "))
        assertEquals("https://next.aino.org.in/uploads/a.png", resolveServerMediaUrl("/uploads/a.png", "https://next.aino.org.in"))
        assertEquals("https://cdn.example/a.png", resolveServerMediaUrl("https://cdn.example/a.png", "https://next.aino.org.in"))
    }

    @Test
    fun labelsAndDates() {
        assertEquals("HR admin", roleLabel("hr_admin"))
        assertEquals("Android device", biometricPlatformLabel("android"))
        assertEquals("iPhone / iPad", biometricPlatformLabel("ios"))
        assertEquals("kiosk", biometricPlatformLabel("kiosk"))
        assertNotNull(localDate("2026-09-01T10:00:00Z", ZoneOffset.UTC))
        assertNotNull(localDate("2026-09-01T10:00:00.000+05:30", ZoneOffset.UTC))
        assertNull(localDate(null))
        assertNull(localDate("not a date"))
    }
}
