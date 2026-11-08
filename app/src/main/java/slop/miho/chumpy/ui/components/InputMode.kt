package slop.miho.chumpy.ui.components

import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.compositionLocalOf

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

val LocalInputMode = compositionLocalOf { InputMode.TOUCH }

internal fun isControllerKey(event: AndroidKeyEvent): Boolean {
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
