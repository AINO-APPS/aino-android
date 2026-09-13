package app.aino.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.aino.mobile.core.auth.AuthViewModel
import app.aino.mobile.core.designsystem.theme.AinoTheme
import app.aino.mobile.core.navigation.AinoApp
import app.aino.mobile.core.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    private val authViewModel by viewModels<AuthViewModel> { AuthViewModel.factory(applicationContext) }
    private val updateViewModel by viewModels<UpdateViewModel> { UpdateViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AinoTheme {
                AinoApp(authViewModel, updateViewModel)
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        authViewModel.recordUserActivity()
    }
}
