package com.evofactory.homeboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/**
 * The remote's shortcut in every dialog: Left and Right on a row or a closed text field go straight
 * to the action buttons, however long the list. Set [down] on a list's last row when the actions
 * aren't right below it. Pass the button that's enabled: focus can't land on a greyed out one.
 */
fun Modifier.jumpToActions(left: FocusRequester, right: FocusRequester, down: FocusRequester? = null): Modifier =
    focusProperties {
        this.left = left
        this.right = right
        if (down != null) this.down = down
    }

/** The rows of a dialog's list, to come back to the one last on from the action buttons. */
class ListFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var lastKey: String? = null

    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    /** Goes on the row: makes it a place to come back to */
    fun row(key: String): Modifier = Modifier
        .focusRequester(requester(key))
        .onFocusChanged { if (it.isFocused) lastKey = key }

    /** The row last on if still listed, otherwise the first */
    fun current(keys: List<String>): FocusRequester =
        (lastKey?.takeIf { it in keys } ?: keys.firstOrNull())?.let(::requester) ?: FocusRequester.Default
}

/**
 * Window for tools with text inputs: opens at the top, level with the settings cog, because the
 * on-screen keyboard covers the bottom of the screen. Darkens everything behind it; Back or Esc
 * closes it. [info] shows dimmed at the right of the title, and so do [actions], e.g. buttons.
 * [fullWidth] is for long content, e.g. addresses. [tabs] take the title's place.
 */
@Composable
fun TopDialog(
    title: String,
    onDismiss: () -> Unit,
    info: String? = null,
    fullWidth: Boolean = false,
    tabs: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                // Esc (keyboards, the emulator) closes it like Back does
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) onDismiss()
                    true
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier.padding(top = TopPadding).then(if (fullWidth) Modifier.padding(horizontal = EdgePadding) else Modifier),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = (if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(640.dp)).padding(16.dp)) {
                    // Buttons are filled with surfaceVariant: a step up, or they'd vanish into the dialog
                    val raised = MaterialTheme.colorScheme.copy(surfaceVariant = RaisedSurface)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tabs != null) {
                            Row(modifier = Modifier.height(44.dp), content = tabs)
                        } else {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        if (info != null) {
                            Text(info, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        if (actions != null) {
                            MaterialTheme(colorScheme = raised) { actions() }
                        }
                    }
                    MenuDivider()
                    MaterialTheme(colorScheme = raised) {
                        content()
                    }
                }
            }
        }
    }
}
