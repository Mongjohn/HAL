package com.evofactory.homeboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val TAG = "HomeOverride"

/** The TVs' own home screens; the service only receives events from these (res/xml/home_override_service.xml) */
private val StockLaunchers = setOf(
    "com.google.android.tvlauncher", // Android TV
    "com.google.android.apps.tv.launcherx", // Google TV
    "com.google.android.leanbacklauncher", // older Android TV
)

// Crash guard: this many launches without the launcher starting properly switches the override off
private const val MAX_FAILED_LAUNCHES = 3
private const val FAILED_LAUNCH_WINDOW_MS = 30_000L

private const val CONNECT_CHECK_DELAY_MS = 1_500L

/**
 * Optional "replace the home screen": the Home button opens this launcher instead of the TV's own
 * home screen, and when that one comes up anyway (leaving an app, after a reboot), this launcher
 * is opened on top of it.
 *
 * Does nothing unless one of the two is switched on in HomeBoard settings (off by default) and enabled by the user in
 * the TV's Accessibility settings. Only looks at the Home button and at which app's window opened,
 * nothing else on screen.
 */
class HomeOverrideService : AccessibilityService() {
    private val prefs by lazy { LauncherPrefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateKeyCatching() }

    // The stock launchers' home screen activities. Their other windows, e.g. Google TV's settings
    // panel, are left alone so the TV's settings stay reachable.
    private val homeActivities by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }
            .filter { it.packageName in StockLaunchers }
            .map { it.name }
            .toSet()
    }

    override fun onServiceConnected() {
        running = true
        AppLog.i(TAG, "Home button helper running")
        prefs.addChangeListener(prefsListener)
        updateKeyCatching()
        // After a reboot or an update the stock home screen may already be up, and opens no new
        // window. Checked after a moment: right after connecting, the active window isn't known yet.
        handler.postDelayed({
            if (prefs.reopenAfterHome && rootInActiveWindow?.packageName?.toString() in StockLaunchers) bringLauncherBack()
        }, CONNECT_CHECK_DELAY_MS)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        AppLog.i(TAG, "Home button helper stopped")
        prefs.removeChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !prefs.reopenAfterHome) return
        if (event.packageName?.toString() !in StockLaunchers) return
        if (event.className?.toString() !in homeActivities) return
        bringLauncherBack()
    }

    /**
     * Android can only pass this service all keys or none, and a held volume button may stop
     * repeating with all of them. So it asks for them only while catching the Home button.
     */
    private fun updateKeyCatching() {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val flags = if (prefs.catchHomeKey) info.flags or flag else info.flags and flag.inv()
        if (flags == info.flags) return
        info.flags = flags
        serviceInfo = info
        AppLog.i(TAG, "Catching the Home button: ${flags and flag != 0}")
    }

    /**
     * Catches the Home button before the TV acts on it. Android holds back apps that open something
     * within 5 s of Home being pressed (its "app switch" protection), so reacting to the stock home
     * screen once it's up would leave it on screen for 5 s. This way it never opens.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !prefs.catchHomeKey) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !bringLauncherBack()) {
            // Override just switched off by the crash guard, or the launcher couldn't be opened:
            // do what Home normally does
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        return true
    }

    override fun onInterrupt() = Unit

    /** Opens this launcher; false if the override is off (or the crash guard just switched it off) */
    private fun bringLauncherBack(): Boolean {
        if (!prefs.replaceHome) return false
        if (!MainActivity.running && tooManyFailedLaunches()) {
            // The launcher keeps failing to start: give the TV its own home screen back
            prefs.stopReplacingHome()
            prefs.replaceHomeAutoOff = true
            return false
        }
        // No transition animation: keeps the stock home screen's flash as short as possible
        return try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
            )
            true
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Couldn't open the launcher", e)
            // Never leave the Home button doing nothing
            false
        }
    }

    /** Crash guard: counts launches until MainActivity has started properly (it then resets the count) */
    private fun tooManyFailedLaunches(): Boolean {
        val now = System.currentTimeMillis()
        val since = prefs.overrideLaunchesSince
        if (now < since || now - since > FAILED_LAUNCH_WINDOW_MS) {
            prefs.overrideLaunches = 0
            prefs.overrideLaunchesSince = now
        }
        prefs.overrideLaunches += 1
        return prefs.overrideLaunches > MAX_FAILED_LAUNCHES
    }

    companion object {
        /**
         * Whether Android is running the service right now. It shares the launcher's process, so this
         * is exact; being on in Accessibility settings doesn't mean it runs.
         */
        var running by mutableStateOf(false)
            private set
    }
}

/** Whether the user has turned on the home override's accessibility service in the TV's settings */
fun Context.isHomeOverrideServiceOn(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val service = ComponentName(this, HomeOverrideService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == service }
}
