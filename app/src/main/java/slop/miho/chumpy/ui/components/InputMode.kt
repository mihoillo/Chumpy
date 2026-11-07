package slop.miho.chumpy.ui.components

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput

/**
 * How the app is being driven this very moment.
 */
enum class InputMode {
    /** Fingers on the glass: the bar sits in place, nothing dims, focus stays untouched. */
    TOUCH,

    /** A gamepad, a TV remote, a keyboard's dpad — the shell navigates by focus: the bar
     *  hides off the active screen, back summons it, and the focused element wears a ring. */
    CONTROLLER,
}

/**
 * The input the shell is answering right now — provided by whichever screen's root carries
 * [inputModeTracking]; everything below reads it instead of guessing.
 */
val LocalInputMode = compositionLocalOf { InputMode.TOUCH }

/**
 * Watches how the user drives the app and writes it into [mode]: a hardware key from a
 * controller-ish source or one of the navigation keys flips to [InputMode.CONTROLLER], any
 * finger landing on the glass flips back to [InputMode.TOUCH]. The switch is dynamic and
 * needs no setting — the last input wins, whatever it was.
 *
 * An observer only: nothing is ever consumed here, key events flow on to the focus
 * machinery and the back dispatcher untouched. The back key itself is left out of the
 * controller set on purpose — the system's own navigation buttons send it too, and a
 * plain phone must not slip into controller mode on every back tap.
 */
fun Modifier.inputModeTracking(mode: MutableState<InputMode>): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && isControllerKey(event.nativeKeyEvent)) {
            mode.value = InputMode.CONTROLLER
        }
        false
    }
        .pointerInput(mode) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                mode.value = InputMode.TOUCH
            }
        }

private fun isControllerKey(event: AndroidKeyEvent): Boolean {
    if (event.isFromSource(InputDevice.SOURCE_DPAD) ||
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
        event.isFromSource(InputDevice.SOURCE_JOYSTICK)
    ) {
        return true
    }
    return when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP,
        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        // Some remotes' OK key arrives as the numpad's enter — navigation all the same.
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_BUTTON_A,
        AndroidKeyEvent.KEYCODE_BUTTON_B,
        AndroidKeyEvent.KEYCODE_BUTTON_X,
        AndroidKeyEvent.KEYCODE_BUTTON_Y,
        AndroidKeyEvent.KEYCODE_BUTTON_SELECT,
        AndroidKeyEvent.KEYCODE_BUTTON_START,
        -> true
        else -> false
    }
}
