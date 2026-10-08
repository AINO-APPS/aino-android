package app.aino.mobile.core.designsystem

import app.aino.mobile.core.designsystem.component.AvatarTile
import app.aino.mobile.core.designsystem.component.GroupAvatarMember
import app.aino.mobile.core.designsystem.component.collageMembers
import app.aino.mobile.core.designsystem.component.groupAvatarColors
import app.aino.mobile.core.designsystem.component.groupAvatarTiles
import app.aino.mobile.core.media.avatarSampleSize
import app.aino.mobile.core.media.centreSquare
import app.aino.mobile.core.navigation.webGroupInviteToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupAvatarLayoutTest {
    @Test
    fun tilesCoverTheSquareWithoutOverlap() {
        for (count in 1..6) {
            val tiles = groupAvatarTiles(count)
            assertEquals("area for $count", 1f, tiles.sumOf { (it.width * it.height).toDouble() }.toFloat(), 1e-4f)
            assertEquals(minOf(count, 4), tiles.size)
        }
        assertEquals(listOf(AvatarTile(0f, 0f, .5f, 1f), AvatarTile(.5f, 0f, .5f, .5f), AvatarTile(.5f, .5f, .5f, .5f)), groupAvatarTiles(3))
        assertEquals(emptyList<AvatarTile>(), groupAvatarTiles(0))
    }

    @Test
    fun collagePrefersPhotosAndCapsAtFour() {
        val members = listOf(
            GroupAvatarMember("A", null), GroupAvatarMember("B", "/b.png"), GroupAvatarMember("C", null),
            GroupAvatarMember("D", "/d.png"), GroupAvatarMember("E", null),
        )
        assertEquals(listOf("B", "D", "A", "C"), collageMembers(members).map { it.name })
    }

    @Test
    fun coloursAreStablePerKey() {
        assertEquals(groupAvatarColors("conv-12"), groupAvatarColors("conv-12"))
    }

    @Test
    fun squareCropAndSampling() {
        assertEquals(Triple(600, 200, 0), centreSquare(1000, 600))
        assertEquals(Triple(600, 0, 200), centreSquare(600, 1000))
        assertEquals(4, avatarSampleSize(4000, 3000, 640))
        assertEquals(1, avatarSampleSize(800, 800, 640))
    }

    @Test
    fun webGroupLinksAreRecognised() {
        assertEquals("AbCdEfGhIjKlMnOpQrSt", webGroupInviteToken("https://aino.example/chat/join/AbCdEfGhIjKlMnOpQrSt"))
        assertEquals("AbCdEfGhIjKlMnOpQrSt", webGroupInviteToken("/chat/join/AbCdEfGhIjKlMnOpQrSt?x=1"))
        assertNull(webGroupInviteToken("https://aino.example/chat/42"))
        assertNull(webGroupInviteToken("https://aino.example/chat/join/short"))
        assertNull(webGroupInviteToken("https://aino.example/chat/join/AbCdEfGhIjKlMnOpQrSt/extra"))
    }
}
