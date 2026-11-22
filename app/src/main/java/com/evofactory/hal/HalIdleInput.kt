package com.evofactory.hal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Dialogs have their own Window.Callback and do not call Activity.dispatchKeyEvent. */
@Composable
internal fun TrackHalDialogInput() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose {}
        val original = window.callback
        val keptAwake = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        window.keepHalScreenAwake(true)
        val tracked = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                MainActivity.idleKeyHandler?.invoke(event) == true || original.dispatchKeyEvent(event)

            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                MainActivity.idleTouchHandler?.invoke(event) == true || original.dispatchTouchEvent(event)
        }
        window.callback = tracked
        onDispose {
            if (window.callback === tracked) window.callback = original
            if (!keptAwake) window.keepHalScreenAwake(false)
        }
    }
}

// Popup windows have no DialogWindowProvider. Observe their keys before menu navigation.
internal fun Modifier.trackHalPopupInput(): Modifier = onPreviewKeyEvent {
    MainActivity.idleKeyHandler?.invoke(it.nativeKeyEvent) == true
}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.any { it.pressed && !it.previousPressed }
            val finished = event.changes.none { it.pressed }
            if (MainActivity.idlePointerHandler?.invoke(down, finished) == true) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}
