package app.aino.mobile.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Web `/profile/face` (`FaceEnrollment.tsx`). Enrolling needs the web's
 * face-api.js descriptor, which Android cannot produce, so this page shows the
 * status, lets the user clear it, and points enrolment to the web/desktop app.
 */
@Composable
fun FaceEnrollmentScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    LaunchedEffect(Unit) { viewModel.loadFace() }
    val face = ui.face
    val busy = ProfileForm.Face in ui.busy

    ProfilePage("Face Enrollment", onBack) {
        Text(
            "Enrol your face so you can clock in when your organization requires attendance verification. " +
                "Your photo never leaves this device — only a numerical representation is stored.",
            color = colors.textSecondary, fontSize = 14.sp,
        )

        val enrolled = face?.enrolled == true
        val tint = if (enrolled) colors.success else colors.warning
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().background(tint.copy(alpha = .12f), shape).border(1.dp, tint.copy(alpha = .3f), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                face == null && ui.faceLoading -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                enrolled -> {
                    Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(20.dp), tint = tint)
                    Text("Enrolled" + (localDate(face?.enrolledAt)?.let { " on $it" } ?: ""), color = colors.text, fontWeight = FontWeight.SemiBold)
                }
                else -> {
                    Icon(Icons.Outlined.RemoveModerator, null, Modifier.size(20.dp), tint = tint)
                    Text("Not enrolled yet", color = colors.text, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        ui.notices[ProfileForm.Face]?.let { NoticeText(it) }

        // Replaces "Start Enrollment" / "Re-enroll" (face capture is web/desktop only).
        Row(
            Modifier.fillMaxWidth().background(colors.primaryGlow, shape).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = colors.primary)
            Text(
                (if (enrolled) "To re-enroll" else "To enroll") +
                    ", open AINO on the web or desktop app and go to Profile → Face Enrollment. " +
                    "Face capture is not available in the Android app.",
                color = colors.text, fontSize = 13.sp,
            )
        }

        if (enrolled) {
            ProfileButton("Clear Enrollment", "Clearing…", busy, { viewModel.askClearFace(true) }, danger = true, icon = Icons.Outlined.Delete)
        }

        ProfileSection("Tips for a good enrollment") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Face the camera straight on with even lighting.",
                    "Remove sunglasses, hats, or masks that cover your face.",
                    "Keep only one face in frame.",
                ).forEach { Text("•  $it", color = colors.textSecondary, fontSize = 13.sp) }
            }
        }
    }

    if (ui.faceClearConfirming) {
        ProfileConfirmDialog(
            title = "Clear Enrollment",
            message = "Clear your face enrollment? You will need to re-enroll to clock in when attendance verification is on.",
            confirmText = "Clear Enrollment",
            danger = true,
            onConfirm = viewModel::clearFace,
            onCancel = { viewModel.askClearFace(false) },
        )
    }
}
