package slop.miho.chumpy

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import slop.miho.chumpy.ui.MainViewModel
import slop.miho.chumpy.ui.components.SystemBarsIconsDark
import slop.miho.chumpy.ui.main.MainShell
import slop.miho.chumpy.ui.theme.ChumpyTheme
import slop.miho.chumpy.ui.theme.LocalDarkTheme
import slop.miho.chumpy.ui.welcome.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            // The notifications permission rides the first config's landing — asked once,
            // ever. The ask belongs to the activity, not the shell: a fresh install adds
            // its first config from the welcome screen, where the shell is not yet alive.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            val notificationsAsk by viewModel.notificationsAsk.collectAsStateWithLifecycle()
            LaunchedEffect(notificationsAsk) {
                if (notificationsAsk) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            ChumpyTheme(settings = settings) {

                Surface(color = MaterialTheme.colorScheme.surface) {
                    val motion = MaterialTheme.motionScheme
                    // setupDone stays null while the database answers for the first time:
                    // the app holds on the theme until it knows which screen to show.
                    val setupDone by viewModel.setupDone.collectAsStateWithLifecycle()
                    AnimatedContent(
                        targetState = setupDone,
                        transitionSpec = {
                            if (initialState == null) {
                                // The app coming open: the hold's empty slot is zero-sized, and
                                // growing it out with the clip would unfold the window out of
                                // a corner. The system's own window fade has the launch
                                // covered — the shell lands whole, at once.
                                ContentTransform(
                                    targetContentEnter = fadeIn(snap()),
                                    initialContentExit = fadeOut(snap()),
                                    sizeTransform = SizeTransform(sizeAnimationSpec = { _, _ -> snap() }),
                                )
                            } else {
                                // Onboarding answered and the shell takes over: the slow
                                // fade with the settle-down from a touch shy of full.
                                (fadeIn(motion.slowEffectsSpec()) +
                                    scaleIn(motion.slowSpatialSpec(), initialScale = 0.92f))
                                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
                            }
                        },
                        label = "onboarding",
                    ) { onboarded ->
                        if (onboarded == true) {
                            MainShell(viewModel)
                        } else if (onboarded == false) {
                            // No wallpaper here — the bars answer to the theme alone. The
                            // main shell takes over the window the moment it composes.
                            SystemBarsIconsDark(dark = LocalDarkTheme.current)
                            WelcomeScreen(
                                onAddSubscription = viewModel::addSubscription,
                                onImport = viewModel::addManualProfiles,
                            )
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
