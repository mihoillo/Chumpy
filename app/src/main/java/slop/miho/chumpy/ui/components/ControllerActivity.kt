package slop.miho.chumpy.ui.components

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs

open class ControllerActivity : ComponentActivity() {
    val controllerMode by lazy {
        mutableStateOf(if (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION) InputMode.CONTROLLER else InputMode.TOUCH)
    }
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var axisKey = 0
    private var axisDeviceId = -1
    private var keyDispatch: (KeyEvent) -> Boolean = ::dispatchToActivity
    private val heldDirections = mutableSetOf<Int>()
    private val inputManager by lazy { getSystemService(android.hardware.input.InputManager::class.java) }
    private val devices = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (deviceId == axisDeviceId) stopAxis()
            heldDirections.clear()
        }
    }
    private val repeatAxis = object : Runnable {
        override fun run() {
            if (axisKey == 0) return
            pulse(axisKey)
            repeatHandler.postDelayed(this, 140)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isControllerKey(event)) controllerMode.value = InputMode.CONTROLLER
        if (event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) heldDirections.add(event.keyCode)
            else if (event.action == KeyEvent.ACTION_UP) heldDirections.remove(event.keyCode)
            stopAxis()
        }
        val code = normalizedControllerKey(event.keyCode)
        if (code == event.keyCode) return super.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action,
            code, event.repeatCount, event.metaState, event.deviceId, event.scanCode,
            event.flags, InputDevice.SOURCE_DPAD))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopAxis()
            controllerMode.value = InputMode.TOUCH
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return if (handleControllerMotion(event, ::dispatchToActivity)) true else super.dispatchGenericMotionEvent(event)
    }

    internal fun handleControllerMotion(event: MotionEvent, dispatch: (KeyEvent) -> Boolean): Boolean {
        if ((!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) ||
            event.action != MotionEvent.ACTION_MOVE) {
            return false
        }
        fun axis(axis: Int): Float {
            val value = event.getAxisValue(axis)
            val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
            return if (abs(value) > maxOf(flat, 0.45f)) value else 0f
        }
        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        val code = controllerDirection(if (hatX != 0f || hatY != 0f) hatX else axis(MotionEvent.AXIS_X),
            if (hatX != 0f || hatY != 0f) hatY else axis(MotionEvent.AXIS_Y))
        if (code == 0) {
            stopAxis()
            return true
        }
        controllerMode.value = InputMode.CONTROLLER
        if (heldDirections.isNotEmpty()) return true
        if (axisKey != code) {
            stopAxis()
            keyDispatch = dispatch
            axisDeviceId = event.deviceId
            axisKey = code
            pulse(code)
            repeatHandler.postDelayed(repeatAxis, 400)
        }
        return true
    }

    private fun pulse(key: Int) {
        val now = SystemClock.uptimeMillis()
        keyDispatch(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, 0,
            KeyEvent.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_DPAD))
        keyDispatch(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, 0,
            KeyEvent.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_DPAD))
    }

    internal fun stopAxis() {
        axisKey = 0
        axisDeviceId = -1
        keyDispatch = ::dispatchToActivity
        repeatHandler.removeCallbacks(repeatAxis)
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun dispatchToActivity(event: KeyEvent): Boolean = super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { stopAxis(); heldDirections.clear() }
    }

    override fun onPause() {
        stopAxis()
        heldDirections.clear()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(devices, repeatHandler)
    }

    override fun onStop() {
        stopAxis()
        inputManager.unregisterInputDeviceListener(devices)
        super.onStop()
    }
}

internal fun normalizedControllerKey(code: Int): Int = when (code) {
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_1 -> KeyEvent.KEYCODE_DPAD_CENTER
    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> KeyEvent.KEYCODE_BACK
    KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_4 -> KeyEvent.KEYCODE_MENU
    else -> code
}

internal fun controllerDirection(x: Float, y: Float): Int = when {
    !x.isFinite() || !y.isFinite() || maxOf(abs(x), abs(y)) <= 0.45f -> 0
    abs(x) > abs(y) -> if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    else -> if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
}
