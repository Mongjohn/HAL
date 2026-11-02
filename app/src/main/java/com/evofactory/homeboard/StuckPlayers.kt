package com.evofactory.homeboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val TAG = "StuckPlayers"
private const val RELEASE_STUCK_AFTER_MS = 5_000L

// Opening a decoder legitimately holds up the playback thread for seconds on older TVs
private const val OPEN_STUCK_AFTER_MS = 10_000L
private const val CHECK_INTERVAL_MS = 2_000L
private const val DIALOG_REFRESH_MS = 1_000L

/** When each stuck player stopped responding */
private val stuckSince = mutableStateListOf<Long>()

val anyPlayerStuck: Boolean get() = stuckSince.isNotEmpty()

/**
 * Watches a player's playback thread for its whole life, and raises the top bar warning if it hangs.
 * It can block in a call to the TV's decoder, with nothing to force it out, and only a new process
 * is sure to free it. While open, the thread must answer a ping. Once released, it must end: Media3
 * gives up waiting after a moment and leaves the decoder open.
 */
@OptIn(UnstableApi::class)
class PlayerWatch(private val player: ExoPlayer, private val label: String) {
    private val handler = Handler(Looper.getMainLooper())
    private val thread = player.playbackLooper.thread
    private val playback = Handler(player.playbackLooper)
    private val check = Runnable { check() }
    private var pingSentAt = 0L

    @Volatile
    private var pingAnswered = true
    private var releasedAt = 0L
    private var stuckAt = 0L

    init {
        check()
    }

    fun release() {
        releasedAt = SystemClock.elapsedRealtime()
        player.release()
    }

    private fun check() {
        val now = SystemClock.elapsedRealtime()
        when {
            releasedAt != 0L && !thread.isAlive -> {
                recovered("finished releasing after ${now - releasedAt} ms")
                return
            }
            releasedAt != 0L -> if (now - releasedAt >= RELEASE_STUCK_AFTER_MS) stuck(releasedAt, "hasn't released")
            pingAnswered -> {
                recovered("is responding again")
                pingAnswered = false
                pingSentAt = now
                playback.post { pingAnswered = true }
            }
            now - pingSentAt >= OPEN_STUCK_AFTER_MS -> stuck(pingSentAt, "stopped responding")
        }
        handler.postDelayed(check, CHECK_INTERVAL_MS)
    }

    private fun stuck(since: Long, what: String) {
        if (stuckAt != 0L) return
        stuckAt = since
        stuckSince += since
        val stack = thread.stackTrace.joinToString("\n") { "    at $it" }
        AppLog.w(TAG, "$label $what, playback thread:\n$stack")
    }

    private fun recovered(what: String) {
        if (stuckAt == 0L) return
        stuckSince -= stuckAt
        stuckAt = 0L
        AppLog.i(TAG, "$label $what")
    }
}

@Composable
fun StuckPlayersWarning(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val count = stuckSince.size
    if (count == 0) return
    TopBarWarning(
        if (count == 1) "Video player stuck" else "$count video players stuck",
        onClick = onClick,
        modifier = modifier,
    )
}

/** Says what's going on and offers the restart. Stays open if the players recover meanwhile, and says so. */
@Composable
fun StuckPlayersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val count = stuckSince.size
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(DIALOG_REFRESH_MS)
        }
    }
    val restart = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    TopDialog(title = "Video player stuck", onDismiss = onDismiss) {
        val paragraphs = if (count == 0) {
            listOf("The video players have recovered. There's nothing more to do.")
        } else {
            val waiting = formatWaiting(now - stuckSince.min())
            listOf(
                if (count == 1) {
                    "A video player stopped responding $waiting ago."
                } else {
                    "$count video players stopped responding, the first $waiting ago."
                },
                "This TV only has a few video decoders. A stuck player is probably still holding one, " +
                    "so cameras may not start, or may say the TV can't decode their video.",
                "Android can't force it to stop. It may still recover by itself, and this warning then goes away. " +
                    "Restarting the app frees it, and only takes a few seconds.",
            )
        }
        paragraphs.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Close") }
            if (count > 0) {
                OutlinedButton(onClick = { context.restartApp() }, modifier = Modifier.focusRequester(restart)) {
                    Text("Restart app")
                }
            }
        }
        LaunchedEffect(count == 0) { runCatching { (if (count == 0) close else restart).requestFocus() } }
    }
}

private fun formatWaiting(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    }
}
