package com.evofactory.hal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal const val APPS_CARD_IDLE_MS = 30_000L

/** A wake press includes its repeats and release, so OK cannot launch the hidden tile. */
internal class AppsCardIdleState {
    var lastActivity by mutableLongStateOf(0L)
        private set
    var collapsed by mutableStateOf(false)
        private set
    private val wakeKeys = mutableSetOf<Int>()
    private val pressedKeys = mutableSetOf<Int>()

    fun reset(now: Long) {
        lastActivity = now
        collapsed = false
        wakeKeys.clear()
        pressedKeys.clear()
    }

    fun expire(now: Long) {
        if (pressedKeys.isEmpty() && now - lastActivity >= APPS_CARD_IDLE_MS) collapsed = true
    }

    fun onKey(key: Int, down: Boolean, up: Boolean, power: Boolean, now: Long): Boolean {
        if (power) return false
        lastActivity = now
        if (down) pressedKeys.add(key)
        if (up) pressedKeys.remove(key)
        if (key in wakeKeys) {
            if (up) wakeKeys.remove(key)
            return true
        }
        if (collapsed && down) {
            collapsed = false
            wakeKeys.add(key)
            return true
        }
        return false
    }
}
