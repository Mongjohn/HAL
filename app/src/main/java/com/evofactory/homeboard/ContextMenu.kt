package com.evofactory.homeboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

data class MenuAction(val label: String, val onClick: () -> Unit)

enum class MenuPlacement {
    /** Centred above the anchor: tile menus */
    Above,

    /** Below the anchor, right-aligned with it */
    Below,

    /** Below the anchor, at the screen's right margin: the settings cog's menus */
    BelowAtRightMargin,
}

/**
 * Small popup menu: a title, a divider, then the actions. Choosing an action closes the menu
 * ([onDismiss]) and then runs it. Back closes it too.
 */
@Composable
fun ContextMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    placement: MenuPlacement = MenuPlacement.Above,
) {
    val rightMarginPx = with(LocalDensity.current) { EdgePadding.roundToPx() }
    Popup(
        popupPositionProvider = remember(placement, rightMarginPx) { AnchoredMenuPosition(placement, rightMarginPx) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val firstItem = remember { FocusRequester() }
        // The OK press that opened the menu may still be held (long press): its auto-repeats and
        // its release would land on the first item and select it. Ignore OK until a fresh press.
        var waitingForFreshPress by remember { mutableStateOf(true) }
        Surface(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.key !in ConfirmKeys) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    waitingForFreshPress = false
                }
                waitingForFreshPress
            },
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(containerColor = RaisedSurface),
        ) {
            Column(modifier = Modifier.width(300.dp).padding(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                MenuDivider()
                actions.forEachIndexed { index, action ->
                    ListItem(
                        selected = false,
                        onClick = {
                            onDismiss()
                            action.onClick()
                        },
                        headlineContent = { Text(action.label) },
                        modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                    )
                }
            }
        }
        LaunchedEffect(Unit) { firstItem.requestFocus() }
    }
}

@Composable
fun MenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
    )
}

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Positions the menu next to its anchor, kept inside the screen. */
private class AnchoredMenuPosition(
    private val placement: MenuPlacement,
    private val rightMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - GAP_PX).coerceAtLeast(GAP_PX)
        return when (placement) {
            MenuPlacement.Above -> IntOffset(
                x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(GAP_PX, maxX),
                y = (anchorBounds.top - popupContentSize.height - GAP_PX).coerceAtLeast(0),
            )
            MenuPlacement.Below -> IntOffset(
                x = (anchorBounds.right - popupContentSize.width).coerceIn(GAP_PX, maxX),
                y = anchorBounds.bottom + GAP_PX / 2,
            )
            MenuPlacement.BelowAtRightMargin -> IntOffset(
                x = windowSize.width - popupContentSize.width - rightMarginPx,
                y = anchorBounds.bottom + GAP_PX / 2,
            )
        }
    }

    private companion object {
        const val GAP_PX = 24
    }
}
