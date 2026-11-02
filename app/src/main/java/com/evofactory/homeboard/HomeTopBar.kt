package com.evofactory.homeboard

import android.app.ActivityManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen above the apps shows */
enum class HomeTab(val label: String) {
    /** The main camera and Home Assistant */
    Home("Home"),

    /** All the cameras' last pictures */
    Cameras("Cameras"),

    /** The latest Frigate alerts */
    Alerts("Alerts"),
}

/** The top bar: tabs on the left; free memory, action buttons, the settings cog and the time on the right. */
@Composable
fun HomeTopBar(
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    secondTabFocus: FocusRequester,
    homeTabFocus: FocusRequester,
    alertsTabFocus: FocusRequester,
    onHomeTabSelected: (Boolean) -> Unit,
    showFreeMemory: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    settings: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.height(SettingsButtonSize),
        horizontalArrangement = Arrangement.spacedBy(PanelItemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeTab.entries.forEachIndexed { index, entry ->
            TabButton(
                entry.label,
                selected = entry == tab,
                onSelect = { onTabChange(entry) },
                modifier = when (index) {
                    0 -> Modifier.focusRequester(homeTabFocus).onFocusChanged { onHomeTabSelected(it.hasFocus) }
                    1 -> Modifier.focusRequester(secondTabFocus)
                    2 -> Modifier.focusRequester(alertsTabFocus)
                    else -> Modifier
                },
            )
        }
        // Measured last, so the cog and the clock always keep their room
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(PanelItemSpacing, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showFreeMemory) FreeMemory()
            actions()
        }
        settings()
        Clock()
    }
}

/**
 * A tab: the arrows only move the selection, OK opens it. The open tab is underlined; the one the
 * remote is on gets a light background.
 */
@Composable
fun TabButton(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxHeight(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        // Stays in place, so the underline doesn't move
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant,
            focusedContainerColor = colors.onSurface,
            focusedContentColor = colors.inverseOnSurface,
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        .height(TabUnderline)
                        // The text's color: it reverses with the tab when the remote is on it
                        .background(if (selected) LocalContentColor.current else Color.Transparent, RoundedCornerShape(TabUnderline / 2)),
                )
            }
        }
    }
}

private val TabUnderline = 4.dp

/** Something needs attention: a red pill, OK says more. [text] is short, it shares the bar with the action buttons. */
@Composable
fun TopBarWarning(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ErrorColor.copy(alpha = 0.2f),
            contentColor = ErrorColor,
            focusedContainerColor = ErrorColor,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The text's color: a red dot would vanish on the red fill when selected
            Box(Modifier.size(10.dp).background(LocalContentColor.current, CircleShape))
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Memory left on the TV, updated every second; in red when it's running out. Display only. */
@Composable
private fun FreeMemory() {
    val context = LocalContext.current
    val free by produceState(0L) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        while (true) {
            activityManager.getMemoryInfo(info)
            value = info.availMem
            delay(MEMORY_CHECK_INTERVAL_MS)
        }
    }
    val megabytes = free / (1024 * 1024)
    val color = if (megabytes < LOW_MEMORY_MB) ErrorColor else FaintText
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (megabytes >= 1024) "%.1f GB".format(megabytes / 1024f) else "$megabytes MB",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
        Text("free", style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

private const val MEMORY_CHECK_INTERVAL_MS = 1_000L
private const val LOW_MEMORY_MB = 300

/** The current time, dimmed, and under it the date in the TV's language; display only, the remote never lands on it */
@Composable
private fun Clock() {
    val context = LocalContext.current
    val timeFormat = DateFormat.getTimeFormat(context)
    val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), Locale.getDefault())
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            // Wake up just after the next minute starts
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(timeFormat.format(now), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(dateFormat.format(now), style = MaterialTheme.typography.labelMedium, color = FaintText, maxLines = 1)
    }
}
