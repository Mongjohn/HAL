package com.evofactory.hal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A retained surface frame is distinct from readiness of the current playlist item. */
internal class VideoFrameState {
    var displayed by mutableStateOf(false)
        private set
    var currentItemReady by mutableStateOf(false)
        private set
    fun nextItem() { currentItemReady = false }
    fun rendered() { displayed = true; currentItemReady = true }
    fun reset() { displayed = false; currentItemReady = false }
}
