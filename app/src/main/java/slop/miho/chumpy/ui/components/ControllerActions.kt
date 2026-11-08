package slop.miho.chumpy.ui.components

import android.view.KeyEvent
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalView

internal val LocalControllerHomeFocusAction = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

fun Modifier.controllerContextMenu(action: (() -> Unit)?): Modifier = if (action == null) this else
    onPreviewKeyEvent { event ->
        if (normalizedControllerKey(event.nativeKeyEvent.keyCode) != KeyEvent.KEYCODE_MENU) false
        else {
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP && !event.nativeKeyEvent.isCanceled) action()
            true
        }
    }

fun Modifier.controllerFocusBoundary(
    enabled: Boolean,
    trap: Boolean = false,
    allowExit: () -> Boolean = { false },
    leftExit: (() -> Unit)? = null,
): Modifier = focusProperties {
    onEnter = { if (!enabled && !allowExit()) cancelFocusChange() }
    onExit = {
        if (trap && !allowExit()) {
            if (requestedFocusDirection == FocusDirection.Left && leftExit != null) leftExit()
            else cancelFocusChange()
        }
    }
}.focusGroup()

fun Modifier.controllerHorizontalFocusGroup(): Modifier = composed {
    val controller = LocalInputMode.current == InputMode.CONTROLLER
    val homeFocus = LocalControllerHomeFocusAction.current
    focusProperties {
        onExit = {
            if (controller) {
                if (requestedFocusDirection == FocusDirection.Left && homeFocus != null) homeFocus()
                else if (requestedFocusDirection == FocusDirection.Left ||
                    requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
            }
        }
    }.focusGroup()
}

fun Modifier.controllerScrollFallback(scroll: androidx.compose.foundation.ScrollState): Modifier = composed {
    val controller = LocalInputMode.current == InputMode.CONTROLLER
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    onKeyEvent { event ->
        val key = event.nativeKeyEvent
        val down = key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val up = key.keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (!controller || (!down && !up)) false
        else if (key.action == KeyEvent.ACTION_UP) true
        else if (key.action != KeyEvent.ACTION_DOWN) false
        else if (focus.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) true
        else if (if (down) scroll.canScrollForward else scroll.canScrollBackward) {
            scope.launch { scroll.scrollBy(scroll.viewportSize * if (down) 0.5f else -0.5f) }
            true
        } else false
    }
}

fun Modifier.controllerReadFocus(): Modifier = composed {
    if (LocalInputMode.current == InputMode.CONTROLLER) focusRing(inset = true, gap = 1.dp).focusable()
    else this
}

fun Modifier.controllerWindowInput(onBack: () -> Unit): Modifier = composed {
    val view = LocalView.current
    val activity = view.context.controllerActivity()
    DisposableEffect(view, activity) {
        view.setOnGenericMotionListener { _, event ->
            activity?.handleControllerMotion(event, view::dispatchKeyEvent) == true
        }
        onDispose { view.setOnGenericMotionListener(null); activity?.stopAxis() }
    }
    pointerInput(activity) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            activity?.stopAxis()
            activity?.controllerMode?.value = InputMode.TOUCH
        }
    }.onPreviewKeyEvent { event ->
        val original = event.nativeKeyEvent
        if (isControllerKey(original)) activity?.controllerMode?.value = InputMode.CONTROLLER
        val key = normalizedControllerKey(original.keyCode)
        if (key == original.keyCode) false else {
            activity?.controllerMode?.value = InputMode.CONTROLLER
            if (key == KeyEvent.KEYCODE_BACK) {
                if (original.action == KeyEvent.ACTION_UP && !original.isCanceled) onBack()
            } else view.dispatchKeyEvent(KeyEvent(original.downTime, original.eventTime, original.action,
                key, original.repeatCount, original.metaState, original.deviceId, original.scanCode, original.flags,
                android.view.InputDevice.SOURCE_DPAD))
            true
        }
    }
}

fun Modifier.restoreControllerFocus(menuOpen: Boolean): Modifier = composed {
    val requester = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    val controller = LocalInputMode.current == InputMode.CONTROLLER
    LaunchedEffect(menuOpen, controller) {
        if (wasOpen && !menuOpen && controller) {
            withFrameNanos { }
            requester.requestFocus()
        }
        wasOpen = menuOpen
    }
    focusRequester(requester)
}

private fun Context.controllerActivity(): ControllerActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ControllerActivity) return context
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    return context as? ControllerActivity
}
