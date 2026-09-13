package app.aino.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.aino.mobile.core.auth.AuthViewModel
import app.aino.mobile.core.designsystem.theme.AinoTheme
import app.aino.mobile.core.navigation.AinoApp

class MainActivity : ComponentActivity() {
    private val authViewModel by viewModels<AuthViewModel> { AuthViewModel.factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AinoTheme {
                AinoApp(authViewModel)
            }
        }
    }
}
