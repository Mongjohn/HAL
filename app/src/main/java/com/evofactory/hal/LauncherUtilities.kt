package com.evofactory.hal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** Restart only after the web action has returned its acknowledgement. */
fun Context.restartApp() {
    startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
    Process.killProcess(Process.myPid())
}

/** Whether the service is on in the TV's Accessibility settings; follows changes to the setting */
@Composable
private fun rememberHomeOverrideServiceOn(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(context.isHomeOverrideServiceOn()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                on = context.isHomeOverrideServiceOn()
            }
        }
        val setting = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        context.contentResolver.registerContentObserver(setting, false, observer)
        on = context.isHomeOverrideServiceOn()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return on
}

/**
 * For the top bar, only when Home is set to open this launcher: shows that the accessibility helper
 * is turned off or, if it's on, that Android isn't running it. Either way Home opens the TV's own
 * home screen. Opens the web UI address popup.
 */
@Composable
fun HomeHelperWarning(replaceHome: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val serviceOn = rememberHomeOverrideServiceOn()
    // The helper connects a moment after the launcher starts: no warning flash meanwhile
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(HELPER_START_GRACE_MS)
        settled = true
    }
    if (!replaceHome || !settled || (serviceOn && HomeOverrideService.running)) return
    TopBarWarning(
        // Short: it shares the top bar with the action buttons. The web UI has the details.
        if (serviceOn) "Helper not running" else "Helper turned off",
        onClick = onClick,
        modifier = modifier,
    )
}

private const val HELPER_START_GRACE_MS = 3_000L
