package com.evofactory.hal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/** Suspend producers, not their remembered values/layout/focus, while the owning window is hidden. */
@Composable
internal fun VisibleEffect(vararg keys: Any?, minimumState: Lifecycle.State = Lifecycle.State.STARTED, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = rememberUpdatedState(block)
    LaunchedEffect(lifecycle, minimumState, *keys) {
        lifecycle.repeatOnLifecycle(minimumState) { current.value(this) }
    }
}

@Composable
internal fun <T> produceVisibleState(
    initialValue: T, vararg keys: Any?, once: Boolean = false,
    producer: suspend ProduceStateScope<T>.() -> Unit,
): State<T> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = rememberUpdatedState(producer)
    return produceState(initialValue, lifecycle, once, *keys) {
        val target = this
        var completed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!once || !completed) {
                current.value(target)
                completed = true
            }
        }
    }
}

/** Launcher visibility; catalog work may also be owned by the visible artwork DreamService. */
internal object HalForegroundWork {
    @Volatile var visible = false
}
