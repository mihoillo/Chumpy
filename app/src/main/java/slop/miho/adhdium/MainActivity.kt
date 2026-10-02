package slop.miho.adhdium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import slop.miho.adhdium.ui.MainViewModel
import slop.miho.adhdium.ui.components.SystemBarsIconsDark
import slop.miho.adhdium.ui.main.MainShell
import slop.miho.adhdium.ui.theme.AdhdiumTheme
import slop.miho.adhdium.ui.theme.LocalDarkTheme
import slop.miho.adhdium.ui.welcome.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            AdhdiumTheme(settings = settings) {
                val profile by viewModel.profile.collectAsStateWithLifecycle()

                Surface(color = MaterialTheme.colorScheme.surface) {
                    val motion = MaterialTheme.motionScheme
                    AnimatedContent(
                        targetState = profile != null,
                        transitionSpec = {
                            (fadeIn(motion.slowEffectsSpec()) + scaleIn(motion.slowSpatialSpec(), initialScale = 0.92f))
                                .togetherWith(fadeOut(motion.fastEffectsSpec()))
                        },
                        label = "onboarding",
                    ) { onboarded ->
                        if (onboarded) {
                            MainShell(viewModel)
                        } else {
                            // No wallpaper here — the bars answer to the theme alone. The
                            // main shell takes over the window the moment it composes.
                            SystemBarsIconsDark(dark = LocalDarkTheme.current)
                            WelcomeScreen(onImport = viewModel::importConfig)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The same instance the compose tree holds: the stats loops sample once a second
        // while this is true.
        ViewModelProvider(this)[MainViewModel::class.java].setAppInForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // The activity leaving the screen is the app going background: the loops take
        // their slow stride and the history keeps trickling in behind the scenes.
        ViewModelProvider(this)[MainViewModel::class.java].setAppInForeground(false)
    }
}
