package com.evofactory.homeboard

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_CHANNEL_DOWN
import android.view.KeyEvent.KEYCODE_CHANNEL_UP
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_1
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import android.view.KeyEvent.KEYCODE_NUMPAD_DOT
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_PROG_BLUE
import android.view.KeyEvent.KEYCODE_PROG_GREEN
import android.view.KeyEvent.KEYCODE_PROG_RED
import android.view.KeyEvent.KEYCODE_PROG_YELLOW
import androidx.compose.ui.input.key.KeyEvent

/** The tab a colour button opens: red, green, yellow and blue, in the tabs' order. Null for other keys. */
fun colorButtonTab(event: KeyEvent): HomeTab? = when (event.nativeKeyEvent.keyCode) {
    KEYCODE_PROG_RED -> HomeTab.Home
    KEYCODE_PROG_GREEN -> HomeTab.Cameras
    KEYCODE_PROG_YELLOW -> HomeTab.Alerts
    KEYCODE_PROG_BLUE -> HomeTab.Assistant
    else -> null
}

/** The camera a number button picks, 0-based: 1 is the first camera and 0 the tenth. Null for other keys. */
fun cameraButtonIndex(event: KeyEvent): Int? = when (val code = event.nativeKeyEvent.keyCode) {
    in KEYCODE_1..KEYCODE_9 -> code - KEYCODE_1
    in KEYCODE_NUMPAD_1..KEYCODE_NUMPAD_9 -> code - KEYCODE_NUMPAD_1
    KEYCODE_0, KEYCODE_NUMPAD_0 -> 9
    else -> null
}

/** The remote's channel up (1) and down (-1): the next or previous camera. Null for other keys. */
fun channelStep(event: KeyEvent): Int? = when (event.nativeKeyEvent.keyCode) {
    KEYCODE_CHANNEL_UP -> 1
    KEYCODE_CHANNEL_DOWN -> -1
    else -> null
}

/** The camera [step]s from the one with [id] in [cameras], round the ends; from none, the first or the last */
fun steppedCamera(cameras: List<Camera>, id: String?, step: Int): Camera? {
    if (cameras.isEmpty()) return null
    val index = cameras.indexOfFirst { it.id == id }
    return if (index < 0) (if (step > 0) cameras.first() else cameras.last()) else cameras[(index + step).mod(cameras.size)]
}

/** The dot button, next to 0 on the remote's number pad: selects the settings cog */
fun isSettingsButton(event: KeyEvent): Boolean = event.nativeKeyEvent.keyCode.let { it == KEYCODE_NUMPAD_DOT || it == KEYCODE_PERIOD }
