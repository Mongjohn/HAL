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
internal fun VisibleEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = rememberUpdatedState(block)
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { current.value(this) }
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

/** Only the launcher activity grants permission for catalog jobs; the Home helper never does. */
internal object HalForegroundWork {
    @Volatile var visible = false
}
