package slop.miho.chumpy.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import slop.miho.chumpy.R

fun Context.hasUsableCamera(): Boolean = runCatching {
    getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.isNotEmpty()
}.getOrDefault(false)

fun Context.externalAction(action: () -> Unit) {
    try { action() }
    catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_LONG).show() }
    catch (_: SecurityException) { Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_LONG).show() }
}
