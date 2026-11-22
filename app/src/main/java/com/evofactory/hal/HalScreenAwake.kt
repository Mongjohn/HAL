package com.evofactory.hal

import android.view.Window
import android.view.WindowManager

/**
 * HAL's single foreground ambient-mode policy, irrespective of how HAL was opened.
 * Home Helper only brings the activity forward; it must never own a separate wake lock,
 * change system screensaver settings, or keep other foreground apps awake.
 */
internal fun Window.keepHalScreenAwake(awake: Boolean) {
    if (awake) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
