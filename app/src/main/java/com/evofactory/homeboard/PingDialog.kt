package com.evofactory.homeboard

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PING_COUNT = 5

private const val ADDRESS_CHECK_INTERVAL_MS = 1_000L

/** This TV's IPv4 address, in words, for the ping utility's title */
private fun Context.addressText(): String {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork ?: return "No network"
    val addresses = connectivity.ipv4Addresses(network)
    val usable = addresses.firstOrNull { !it.isLinkLocalAddress }
    return when {
        usable != null -> "This TV: ${usable.hostAddress}"
        addresses.isNotEmpty() -> "This TV: ${addresses.first().hostAddress} (self-assigned)"
        else -> "No IP address"
    }
}

/** Pings an IPv4 address 5 times and shows the system ping output as it arrives. */
@Composable
fun PingDialog(initialHost: String, onHostEntered: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Stays current if the network changes while this is open
    val address by produceState(context.addressText(), context) {
        while (true) {
            value = context.addressText()
            delay(ADDRESS_CHECK_INTERVAL_MS)
        }
    }
    var host by remember { mutableStateOf(initialHost) }
    var status by remember { mutableStateOf<String?>(null) }
    val output = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val pingButton = remember { FocusRequester() }
    val closeButton = remember { FocusRequester() }
    val hostField = remember { FocusRequester() }
    val valid = isValidIpv4(host.trim())

    fun start() {
        val target = host.trim()
        if (!isValidIpv4(target)) return
        keyboard?.hide()
        // Ping is disabled while it runs: wait on Close, then come back to Ping to run it again
        closeButton.requestFocus()
        onHostEntered(target)
        job?.cancel()
        output.clear()
        status = "Pinging $target…"
        running = true
        job = scope.launch {
            try {
                val exitCode = ping(target) { line -> output += line }
                status = when (exitCode) {
                    0 -> "$target is reachable"
                    1 -> "No reply from $target"
                    else -> "Ping failed"
                }
            } finally {
                running = false
            }
            withFrameNanos { } // let Ping re-enable before focusing it
            pingButton.requestFocus()
        }
    }

    TopDialog(title = "Ping utility", onDismiss = onDismiss, info = address) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTextField(
                value = host,
                onValueChange = { host = it },
                placeholder = "IP address, e.g. 192.168.1.1",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onImeAction = ::start,
                modifier = Modifier.weight(1f).focusRequester(hostField).jumpToActions(left = closeButton, right = if (valid && !running) pingButton else closeButton),
            )
            // Greyed out (and skipped by the remote) until the address is a valid IPv4
            // address, and while a ping runs
            Button(
                onClick = ::start,
                enabled = valid && !running,
                modifier = Modifier.focusRequester(pingButton),
            ) {
                Text("Ping")
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeButton)) { Text("Close") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (output.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(12.dp)) {
                output.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
        // With a remembered address, start on Ping so one press re-runs it; otherwise on the field
        LaunchedEffect(Unit) { if (valid) pingButton.requestFocus() else hostField.requestFocus() }
    }
}

/** Four numbers 0–255 separated by dots, e.g. 192.168.1.1 */
private fun isValidIpv4(address: String): Boolean {
    val parts = address.split('.')
    return parts.size == 4 && parts.all { part ->
        part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255
    }
}

/**
 * Runs the system `ping` 5 times, passing each output line to [onLine] on the main thread.
 * Returns ping's exit code: 0 = got replies, 1 = no reply, other = error.
 */
private suspend fun ping(host: String, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
    val process = ProcessBuilder("ping", "-c", "$PING_COUNT", "-W", "2", "-w", "15", host)
        .redirectErrorStream(true)
        .start()
    try {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> withContext(Dispatchers.Main) { onLine(line) } }
        }
        process.waitFor()
    } finally {
        // Also stops ping if the dialog is closed while it runs
        process.destroy()
    }
}
