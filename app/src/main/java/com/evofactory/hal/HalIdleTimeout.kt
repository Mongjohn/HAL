package com.evofactory.hal

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Read the configured ambient deadline; never write global power/screensaver settings. */
@Composable
internal fun rememberHalIdleTimeout(): Long {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = halIdleTimeout(
        runCatching { Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull(),
        runCatching { Settings.Secure.getLong(resolver, "sleep_timeout") }.getOrNull(),
    )
    var timeout by remember { mutableLongStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { timeout = read() }
        }
        val registrations = listOf(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), Settings.Secure.getUriFor("sleep_timeout"))
        registrations.forEach { uri -> runCatching { resolver.registerContentObserver(uri, false, observer) } }
        timeout = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return timeout
}
