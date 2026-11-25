package com.evofactory.hal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val TAG = "Screensaver"

/** How long each camera shows, in seconds: the steps of − and + in the Cameras dialog */
val SCREENSAVER_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300)
const val SCREENSAVER_SECONDS_DEFAULT = 15

// A camera with no picture by then is skipped, so one that's down doesn't hold up the others
private const val NOT_LIVE_SKIP_MS = 20_000L

/**
 * The TV's screensaver, once chosen in Android's settings: the cameras full screen, one after the
 * other, in the camera list's order. Each shows for the time set in the Cameras dialog, counted from
 * its first picture. No sound. Any button ends it.
 *
 * In the launcher's process, so its players count towards the TV's decoders: with "Minimize
 * simultaneous video streams" on, each camera waits for the one before to let go of its decoder.
 * Not an activity, so it gives Compose a lifecycle of its own: started while dreaming.
 */
class CameraScreensaver : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val prefs = LauncherPrefs(this)
        val hidden = prefs.screensaverHiddenCameras.toSet()
        val cameras = prefs.cameras.filter { it.id !in hidden }
        val seconds = prefs.screensaverSeconds
        val minimizeStreams = prefs.minimizeVideoStreams
        val showProgress = prefs.screensaverShowProgress
        val theme = prefs.appearanceTheme
        AppLog.i(TAG, "Showing ${cameras.size} cameras, $seconds s each")
        setContentView(
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@CameraScreensaver)
                setViewTreeSavedStateRegistryOwner(this@CameraScreensaver)
                setContent {
                    HalTheme(theme = theme) {
                        CameraRotation(cameras, seconds, minimizeStreams, showProgress)
                    }
                }
            },
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        AppLog.i(TAG, "Stopped")
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}

@Composable
private fun CameraRotation(cameras: List<Camera>, seconds: Int, minimizeStreams: Boolean, showProgress: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameras.isEmpty()) {
            Text(
                // None set up, or all turned off for the screensaver
                "No cameras to show: add them or turn them on in HAL's Cameras settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        var index by remember { mutableIntStateOf(0) }
        var live by remember { mutableStateOf(false) }
        val camera = cameras[index]
        LaunchedEffect(index, live) {
            if (cameras.size < 2) return@LaunchedEffect
            delay(if (live) seconds * 1_000L else NOT_LIVE_SKIP_MS)
            if (!live) AppLog.i(TAG, "No picture from camera ${index + 1} after $NOT_LIVE_SKIP_MS ms, on to the next")
            live = false
            index = (index + 1) % cameras.size
        }
        CameraView(
            url = camera.url,
            useTcp = camera.useTcp,
            modifier = Modifier.fillMaxSize(),
            minimizeStreams = minimizeStreams,
            // No sound: never full screen in CameraView's sense
            waitForOtherPlayers = minimizeStreams,
            onLiveChange = { live = it },
            showProgress = showProgress,
            rounded = false,
            stretch = camera.stretch,
        )
        Text(
            camera.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** What Android's settings say about the screensaver. Null: Android didn't let the app read it. */
private class ScreensaverStatus(
    val enabled: Boolean?,
    // The chosen screensaver, null if none
    val chosen: ComponentName?,
    val readable: Boolean,
    // How long without input before it starts
    val startsAfterMs: Int?,
)

// Hidden settings: their names aren't in the SDK, but apps may read them
private const val SCREENSAVER_ENABLED = "screensaver_enabled"
private const val SCREENSAVER_COMPONENTS = "screensaver_components"
private const val SCREENSAVER_DEFAULT_COMPONENT = "screensaver_default_component"

private fun Context.readScreensaverStatus(): ScreensaverStatus {
    val resolver = contentResolver
    val (enabled, chosen, readable) = try {
        val enabled = Settings.Secure.getString(resolver, SCREENSAVER_ENABLED)?.let { it == "1" }
        // Comma-separated: the first one is the one shown. None chosen: Android shows the TV's default.
        fun first(key: String) = Settings.Secure.getString(resolver, key)
            ?.split(',')?.firstOrNull { it.isNotBlank() }?.let(ComponentName::unflattenFromString)
        val chosen = first(SCREENSAVER_COMPONENTS) ?: first(SCREENSAVER_DEFAULT_COMPONENT)
        Triple(enabled, chosen, true)
    } catch (e: SecurityException) {
        AppLog.w(TAG, "Can't read the screensaver settings", e)
        Triple(null, null, false)
    }
    val startsAfter = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
    return ScreensaverStatus(enabled, chosen, readable, startsAfter)
}

private const val TV_SETTINGS = "com.android.tv.settings"
private const val GOOGLE_TV_AMBIENT = "com.google.android.tv.settings.ambient"

private fun Context.opens(intent: Intent) = packageManager.resolveActivity(intent, 0)?.activityInfo
    ?.let { it.exported && !it.packageName.endsWith(".frameworkpackagestubs") } == true

/**
 * Google TV's Settings, which can't choose another screensaver: its Ambient mode is only Google's,
 * and its screen saver screen is switched off (Kitchen TV and emulator, 2026-11-04).
 */
fun Context.isGoogleTvSettings() = opens(Intent(GOOGLE_TV_AMBIENT))

/**
 * The screen of the TV's Settings that chooses the screensaver, or null if there's none to open.
 * Few TVs answer [Settings.ACTION_DREAM_SETTINGS], but their Settings app often lets other apps open
 * its screen saver screen by name.
 */
fun Context.screensaverScreen(): Intent? {
    if (isGoogleTvSettings()) return null
    return listOf(
        Intent(Settings.ACTION_DREAM_SETTINGS),
        // Sony's Android TV, e.g. Android 9
        Intent().setClassName(TV_SETTINGS, "com.sony.dtv.settings.device.display.daydream.DaydreamActivity"),
        // Android TV's own
        Intent().setClassName(TV_SETTINGS, "com.android.tv.settings.device.display.daydream.DaydreamActivity"),
    ).firstOrNull(::opens)
}

/** The screensaver screen if the TV has one to open, otherwise the main screen of its Settings */
fun Context.openScreensaverSettings() {
    val screen = screensaverScreen() ?: return openSystemSettings()
    try {
        startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLog.w(TAG, "Couldn't open the screensaver settings", e)
        openSystemSettings()
    }
}

/** Read-only Android selection status for the web settings page. */
internal fun Context.webScreensaverStatus(): org.json.JSONObject {
    val status = readScreensaverStatus()
    return org.json.JSONObject().put("readable", status.readable)
        .put("enabled", status.enabled ?: org.json.JSONObject.NULL)
        .put("component", status.chosen?.flattenToShortString() ?: org.json.JSONObject.NULL)
        .put("startsAfterMs", status.startsAfterMs ?: org.json.JSONObject.NULL)
}
