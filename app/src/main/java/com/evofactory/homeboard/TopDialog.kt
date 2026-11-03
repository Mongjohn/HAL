package com.evofactory.homeboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

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
    fun current(keys: List<String>): FocusRequester = currentOrNull(keys) ?: FocusRequester.Default

    /** Like [current], but null when the list is empty: for [TopDialog]'s contentFocus */
    fun currentOrNull(keys: List<String>): FocusRequester? =
        (lastKey?.takeIf { it in keys } ?: keys.firstOrNull())?.let(::requester)
}

/** A tab in a dialog's top row: the arrows only move the selection, OK opens it */
class DialogTab(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/**
 * A button in a dialog's top row, after the title or tabs and before the X. [primary] is filled,
 * the others are outlined. A greyed out one is skipped by the remote. [focus] lets the dialog
 * select it, e.g. when it opens.
 */
class DialogButton(
    val label: String,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val focus: FocusRequester? = null,
    val onClick: () -> Unit,
)

/** A dialog's content, which can reach its top row. Not a ColumnScope, so it's reachable inside rows too. */
interface TopDialogScope {
    /** The X, which closes the dialog */
    val close: FocusRequester

    /**
     * On a row of a list or a text field: Left goes to the X and Right to the first tab (or button),
     * the two ends of the top row, from where the same key, carrying on round, comes back. For items side by side,
     * [left] goes on the first one and [right] on the last.
     */
    fun Modifier.ringToTop(left: Boolean = true, right: Boolean = true): Modifier

    /** On the search field above a list: Down goes back to the item last selected in the list, not its first */
    fun Modifier.downToContent(): Modifier
}

private class TopDialogScopeImpl(override val close: FocusRequester) : TopDialogScope {
    // Read when the remote moves, so it follows tabs and buttons as they come and go
    var firstTop: FocusRequester = close
    var contentFocus: () -> FocusRequester? = { null }

    override fun Modifier.downToContent(): Modifier = focusProperties { contentFocus()?.let { down = it } }

    override fun Modifier.ringToTop(left: Boolean, right: Boolean): Modifier = focusProperties {
        if (left) this.left = close
        if (right) this.right = firstTop
    }
}

/**
 * Every dialog: opens at the top, level with the settings cog, because the on-screen keyboard covers
 * the bottom of the screen. Darkens everything behind it; Back or Esc [onDismiss]es it.
 *
 * The top row holds the [title] (or the [tabs] in its place), [info] dimmed, the [buttons], then
 * an X that [onClose]s it. From the content, Left goes to the X and Right to the first tab (or
 * button) (see [TopDialogScope.ringToTop]); Left from the first tab and Right from the X go back to
 * the content, to [contentFocus], the item last selected there, as does Down from the top row, unless the content has a search field at
 * its top ([searchFocus]): Down goes to the search, and from there to the item last selected (see
 * [TopDialogScope.downToContent]). [hint] says what OK does, at the bottom, faint. [fullWidth] is for long content, e.g.
 * addresses. [atTop] starts at the very top of the screen, for a dialog that needs all its height.
 */
@Composable
fun TopDialog(
    title: String,
    onDismiss: () -> Unit,
    info: String? = null,
    // A smaller line under the title, in the secondary color
    subtitle: String? = null,
    fullWidth: Boolean = false,
    atTop: Boolean = false,
    tabs: List<DialogTab> = emptyList(),
    buttons: List<DialogButton> = emptyList(),
    // When the X shouldn't do what Back does, e.g. Back goes up a folder
    onClose: () -> Unit = onDismiss,
    contentFocus: () -> FocusRequester? = { null },
    searchFocus: () -> FocusRequester? = { null },
    hint: String? = null,
    // To select the X from outside, e.g. when the dialog opens
    closeFocus: FocusRequester = remember { FocusRequester() },
    content: @Composable TopDialogScope.() -> Unit,
) {
    val tabFocus = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    // By label, for the buttons that bring none
    val buttonFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun focusOf(button: DialogButton) = button.focus ?: buttonFocus.getOrPut(button.label) { FocusRequester() }
    val close = closeFocus
    val firstButton = buttons.indexOfFirst { it.enabled }
    val firstTop = tabFocus.firstOrNull() ?: buttons.getOrNull(firstButton)?.let(::focusOf) ?: close

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
                modifier = Modifier.padding(top = if (atTop) AtTopGap else TopPadding).then(if (fullWidth) Modifier.padding(horizontal = EdgePadding) else Modifier),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = (if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(640.dp))
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = if (atTop) AtTopInnerPadding else 16.dp),
                ) {
                    // Buttons are filled with surfaceVariant: a step up, or they'd vanish into the dialog
                    val raised = MaterialTheme.colorScheme.copy(surfaceVariant = RaisedSurface)
                    // Into the content, or else around the top row
                    fun Modifier.downIn() = focusProperties { (searchFocus() ?: contentFocus())?.let { down = it } }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tabs.isNotEmpty()) {
                            Row(modifier = Modifier.height(44.dp)) {
                                tabs.forEachIndexed { index, tab ->
                                    TabButton(
                                        tab.label,
                                        selected = tab.selected,
                                        onSelect = tab.onSelect,
                                        modifier = Modifier.focusRequester(tabFocus[index]).downIn().focusProperties {
                                            if (index == 0) left = contentFocus() ?: close
                                        },
                                    )
                                }
                            }
                        } else if (subtitle != null) {
                            Column {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        if (info != null) {
                            Text(info, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        MaterialTheme(colorScheme = raised) {
                            buttons.forEachIndexed { index, button ->
                                val modifier = Modifier.focusRequester(focusOf(button)).downIn().focusProperties {
                                    if (tabs.isEmpty() && index == firstButton) left = contentFocus() ?: close
                                }
                                if (button.primary) {
                                    Button(onClick = button.onClick, enabled = button.enabled, modifier = modifier) { Text(button.label) }
                                } else {
                                    OutlinedButton(onClick = button.onClick, enabled = button.enabled, modifier = modifier) { Text(button.label) }
                                }
                            }
                            // An outlined button like the others, so the X has their colors; round
                            OutlinedButton(
                                onClick = onClose,
                                shape = OutlinedButtonDefaults.shape(CircleShape),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier
                                    // As tall as the buttons, and as wide: buttons have a minimum width
                                    .size(CloseSize)
                                    .focusRequester(close)
                                    .downIn()
                                    .focusProperties {
                                        right = contentFocus() ?: firstTop
                                        // The only thing up here
                                        if (firstTop == close) contentFocus()?.let { left = it }
                                    }
                                    .semantics { contentDescription = "Close" },
                            ) {
                                MdiIcon("mdi:close", size = 22.dp, color = LocalContentColor.current)
                            }
                        }
                    }
                    MenuDivider()
                    MaterialTheme(colorScheme = raised) {
                        Column {
                            val scope = remember { TopDialogScopeImpl(close) }
                            scope.firstTop = firstTop
                            scope.contentFocus = contentFocus
                            scope.content()
                        }
                    }
                    if (hint != null) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = FaintText,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

// The X: a circle as tall as the buttons
private val CloseSize = 40.dp

// A dialog at the top: as much space above it as between its edge and its buttons
private val AtTopGap = 12.dp

// With the title row's own 8 dp, about half the usual space above the buttons
private val AtTopInnerPadding = 4.dp
