package com.evofactory.hal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val DEFAULT_HAL_IDLE_MS = 300_000L

internal fun halIdleTimeout(screenOff: Long?, sleep: Long?): Long =
    minOf(screenOff?.takeIf { it > 0 } ?: DEFAULT_HAL_IDLE_MS, sleep?.takeIf { it > 0 } ?: Long.MAX_VALUE)

/** Shared by the activity, its dialog windows, and intercepted Home. Uses monotonic time. */
internal class HalIdleState(now: Long) {
    var active by mutableStateOf(false)
        private set
    var waking by mutableStateOf(false)
        private set
    private val changes = MutableStateFlow(0L)
    val inputChanges = changes.asStateFlow()
    private var lastInput = now
    private val heldKeys = mutableSetOf<Int>()
    private val wakingKeys = mutableSetOf<Int>()
    private var pointerHeld = false
    private var wakingPointer = false

    fun reset(now: Long) {
        active = false
        waking = false
        heldKeys.clear()
        wakingKeys.clear()
        pointerHeld = false
        wakingPointer = false
        activity(now)
    }

    fun activity(now: Long) { lastInput = now; changes.value++ }

    fun waitMillis(now: Long, timeout: Long): Long? =
        if (active || heldKeys.isNotEmpty() || pointerHeld) null else (timeout - (now - lastInput)).coerceAtLeast(0)

    fun expire(now: Long, timeout: Long): Boolean {
        if (waitMillis(now, timeout) != 0L) return false
        active = true
        return true
    }

    fun onKey(code: Int, down: Boolean, up: Boolean, power: Boolean, now: Long): Boolean {
        if (power) return false
        val consume = active || wakingKeys.isNotEmpty() || wakingPointer
        if (consume) {
            active = false
            if (down) wakingKeys.add(code)
        }
        if (down) heldKeys.add(code)
        if (up) { heldKeys.remove(code); wakingKeys.remove(code) }
        waking = wakingKeys.isNotEmpty() || wakingPointer
        activity(now)
        return consume
    }

    fun onPointer(down: Boolean, finished: Boolean, now: Long): Boolean {
        val consume = active || wakingPointer || wakingKeys.isNotEmpty()
        if (consume) { active = false; if (down) wakingPointer = true }
        if (down) pointerHeld = true
        if (finished) { pointerHeld = false; wakingPointer = false }
        waking = wakingKeys.isNotEmpty() || wakingPointer
        activity(now)
        return consume
    }
}
