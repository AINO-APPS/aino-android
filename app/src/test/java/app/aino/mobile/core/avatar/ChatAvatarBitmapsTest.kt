package app.aino.mobile.core.avatar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28])
class ChatAvatarBitmapsTest {
    private val primary = Color.rgb(30, 50, 70)
    private fun solid(color: Int) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    internal fun striped(wide: Boolean): Bitmap {
        val source = Bitmap.createBitmap(if (wide) 300 else 100, if (wide) 100 else 300, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.MAGENTA)
            val paint = Paint().apply { color = Color.RED }
            if (wide) drawRect(100f, 0f, 150f, 100f, paint) else drawRect(0f, 100f, 100f, 150f, paint)
            paint.color = Color.BLUE
            if (wide) drawRect(150f, 0f, 200f, 100f, paint) else drawRect(0f, 150f, 100f, 200f, paint)
        }
        return source
    }

    @Test fun portraitAndLandscapePhotosAreCenterCroppedNotStretchedOrSafeZoneRemasked() {
        for (wide in listOf(true, false)) {
            val bitmap = ChatAvatarBitmaps.user("Contact", primary, striped(wide))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
            assertEquals(Color.RED, bitmap.getPixel(if (wide) 24 else 128, if (wide) 128 else 24))
            assertEquals(Color.BLUE, bitmap.getPixel(if (wide) 232 else 128, if (wide) 128 else 232))
            assertEquals(Color.RED, bitmap.getPixel(if (wide) 100 else 128, if (wide) 128 else 100))
            assertEquals(Color.BLUE, bitmap.getPixel(if (wide) 156 else 128, if (wide) 128 else 156))
        }
    }

    @Test fun initialsAndBrandMatchUserAvatarRatherThanFirstLastNamePalette() {
        assertEquals("AL", avatarInitials(" Alice Lee Ng "))
        assertEquals(app.aino.mobile.core.designsystem.component.avatarInitials("Alice Lee Ng"), avatarInitials("Alice Lee Ng"))
        val bitmap = ChatAvatarBitmaps.user("Alice Lee Ng", primary)
        assertEquals(primary, bitmap.getPixel(128, 24))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertTrue(bitmap.sameAs(ChatAvatarBitmaps.user("Alice Lee", primary)))
        assertFalse(bitmap.sameAs(ChatAvatarBitmaps.user("Alice Ng", primary)))
    }

    @Test fun collagesUseSharedMemberOrderTilesGapAndOnlyOuterCircleMask() {
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
        for (count in 2..4) {
            val members = (0 until count).map { GroupAvatarMember("Member $it", "$it") }
            val metadata = GroupAvatarMetadata(null, members)
            val bitmap = ChatAvatarBitmaps.group("Team", metadata, "7", primary, colors.mapIndexed { i, c -> "$i" to solid(c) }.toMap())
            val samples = when (count) {
                2 -> listOf(64 to 128, 192 to 128)
                3 -> listOf(64 to 128, 192 to 64, 192 to 192)
                else -> listOf(64 to 64, 192 to 64, 64 to 192, 192 to 192)
            }
            samples.forEachIndexed { index, (x, y) -> assertEquals(colors[index], bitmap.getPixel(x, y)) }
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
            assertEquals(groupAvatarColors("7").first, bitmap.getPixel(128, 128))
            // Near the split the member tiles remain square, not separately circle-masked.
            assertEquals(colors[0], bitmap.getPixel(120, if (count == 4) 16 else 128))
        }
    }

    @Test fun groupPhotoWinsAndGlyphDoesNotDependOnTitleInitials() {
        val withPhoto = GroupAvatarMetadata("group", listOf(GroupAvatarMember("Alice", "member"), GroupAvatarMember("Bob", null)))
        assertTrue(ChatAvatarBitmaps.group("Team", withPhoto, "7", primary, mapOf("group" to striped(true)))
            .sameAs(ChatAvatarBitmaps.user("Team", primary, striped(true))))
        val empty = GroupAvatarMetadata(null, emptyList())
        val oneMember = GroupAvatarMetadata(null, listOf(GroupAvatarMember("Alice", null)))
        assertTrue(ChatAvatarBitmaps.group("Team", empty, "7", primary, emptyMap())
            .sameAs(ChatAvatarBitmaps.group("Different Title", oneMember, "7", primary, emptyMap())))
        assertFalse(ChatAvatarBitmaps.group("Team", empty, "7", primary, emptyMap())
            .sameAs(ChatAvatarBitmaps.user("Team", groupAvatarColors("7").first)))
    }
}
