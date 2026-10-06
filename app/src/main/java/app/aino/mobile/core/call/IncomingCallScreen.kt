package app.aino.mobile.core.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.UserAvatar
import kotlinx.coroutines.delay
import app.aino.mobile.core.designsystem.icons.HeroIcons

/**
 * Signal-style incoming call: blurred avatar backdrop, the caller's name and
 * the web's status line on top, a large avatar, and Decline / Accept (plus
 * "Answer without video" for video calls) at the bottom.
 */
@Composable
fun IncomingCallScreen(viewModel: IncomingCallViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val route = ui.route ?: return
    val withCallPermissions = rememberCallPermissions()
    LaunchedEffect(route.callId) {
        delay(remainingRingMillis(route.expiresAt))
        viewModel.expireIfRinging()
    }
    val video = route.callType == "video"
    // Answered from the notification: ask for mic/camera here, then answer (Signal `processIntent`).
    LaunchedEffect(route.callId, ui.answeredFromNotification) {
        if (ui.answeredFromNotification && ui.state == IncomingCallState.Ringing) withCallPermissions(video) { viewModel.answer() }
    }
    val answering = ui.answeredFromNotification && ui.state == IncomingCallState.Ringing
    val busy = answering || ui.state == IncomingCallState.Answering || ui.state == IncomingCallState.Declining
    Box(Modifier.fillMaxSize().blockTouches()) {
        BlurredAvatarBackdrop(route.callerAvatar)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.padding(top = 48.dp))
            Text(route.callerName.ifBlank { "Unknown" }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                when {
                    busy && ui.state != IncomingCallState.Declining -> "Answering..."
                    video -> "Incoming video call..."
                    else -> "Incoming voice call..."
                },
                Modifier.padding(top = 8.dp),
                color = Color.White.copy(alpha = .8f),
                fontSize = 16.sp,
            )
            ui.error?.let { Text(it, Modifier.padding(top = 12.dp), color = Color(0xFFFCA5A5), fontSize = 14.sp, textAlign = TextAlign.Center) }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                UserAvatar(route.callerName.ifBlank { "?" }, route.callerAvatar, 112.dp, background = Color(0xFF3A3A3A))
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(bottom = 64.dp), color = Color.White)
            } else {
                if (video) {
                    LabeledCallButton(HeroIcons.VideoCameraSlash, "Answer without video", Color.White.copy(alpha = .2f), size = 56.dp, onClick = {
                        withCallPermissions(false) { viewModel.answer(withoutVideo = true) }
                    })
                    Spacer(Modifier.padding(top = 24.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledCallButton(HeroIcons.PhoneXMark, "Decline", CallRed, onClick = viewModel::decline)
                    LabeledCallButton(if (video) HeroIcons.VideoCamera else HeroIcons.Phone, "Accept", CallGreen, onClick = {
                        withCallPermissions(video) { viewModel.answer() }
                    })
                }
            }
        }
    }
}
