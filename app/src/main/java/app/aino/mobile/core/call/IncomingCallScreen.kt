package app.aino.mobile.core.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun IncomingCallScreen(viewModel: IncomingCallViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val route = ui.route ?: return
    LaunchedEffect(route.callId) {
        delay(remainingRingMillis(route.expiresAt))
        viewModel.expireIfRinging()
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0E1C)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(104.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text(route.callerName.take(2).uppercase().ifBlank { "?" }, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(route.callerName.ifBlank { "Incoming call" }, Modifier.padding(top = 24.dp), color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (route.callType == "video") Icons.Outlined.Videocam else Icons.Outlined.Call, null, tint = Color.White.copy(alpha = .7f))
            Text(" Incoming ${route.callType} call", color = Color.White.copy(alpha = .7f))
        }
        ui.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error) }
        when (ui.state) {
            IncomingCallState.WaitingForMedia -> Text(
                "Answered. Secure media connection will be enabled in the WebRTC stage.",
                Modifier.padding(top = 28.dp), color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center,
            )
            IncomingCallState.Ended -> Button(onClick = { viewModel.clear(); onClose() }, Modifier.padding(top = 28.dp)) { Text("Close") }
            else -> Row(Modifier.padding(top = 42.dp), horizontalArrangement = Arrangement.spacedBy(42.dp)) {
                Button(onClick = viewModel::decline, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE03E3E)), modifier = Modifier.size(68.dp)) {
                    Icon(Icons.Outlined.CallEnd, "Decline", tint = Color.White)
                }
                Button(onClick = viewModel::answer, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)), modifier = Modifier.size(68.dp)) {
                    Icon(Icons.Outlined.Call, "Answer", tint = Color.White)
                }
            }
        }
    }
}