package com.evofactory.homeboard

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Adds or edits one camera: its name, then its main stream and optional substream, each with its
 * RTSP address and whether to use its audio track. The transport is in the Advanced tab. Test
 * connects to the streams as entered, without saving them.
 */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
fun CameraDialog(
    camera: Camera,
    isNew: Boolean,
    onSave: (Camera) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(camera.name) }
    var url by remember { mutableStateOf(camera.url) }
    var substream by remember { mutableStateOf(camera.substreamUrl) }
    var useTcp by remember { mutableStateOf(camera.useTcp) }
    var useAudio by remember { mutableStateOf(camera.useAudio) }
    var useSubstreamAudio by remember { mutableStateOf(camera.useSubstreamAudio) }
    var advanced by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val nameField = remember { FocusRequester() }
    val urlField = remember { FocusRequester() }
    val substreamField = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val fields = remember { ListFocus() }
    val canTest = isValidRtspUrl(url.trim()) && (substream.isBlank() || isValidRtspUrl(substream.trim()))
    val canSave = name.isNotBlank() && canTest
    val title = if (isNew) "Add camera" else "Edit camera"

    fun save() {
        if (!canSave) return
        keyboard?.hide()
        onSave(
            camera.copy(
                name = name.trim(),
                url = url.trim(),
                useTcp = useTcp,
                substreamUrl = substream.trim(),
                useAudio = useAudio,
                useSubstreamAudio = useSubstreamAudio,
            ),
        )
        onDismiss()
    }

    TopDialog(
        title = title,
        onDismiss = onDismiss,
        fullWidth = true,
        tabs = listOf(
            DialogTab(title, selected = !advanced) { advanced = false },
            DialogTab("Advanced", selected = advanced) { advanced = true },
        ),
        // Greyed out until there's a name and a valid rtsp:// address
        buttons = listOf(
            DialogButton("Test", enabled = canTest) { testing = true },
            DialogButton("Save", enabled = canSave, primary = true, focus = saveButton, onClick = ::save),
        ),
        contentFocus = {
            if (advanced) {
                fields.current(listOf("tcp", "udp"))
            } else {
                fields.current(listOf("name", "audio", "url", "substreamAudio", "substream"))
            }
        },
    ) {
        if (advanced) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Transport", style = MaterialTheme.typography.titleSmall)
                FilterChip(selected = useTcp, onClick = { useTcp = true }, modifier = fields.row("tcp").ringToTop(right = false)) { Text("TCP") }
                FilterChip(selected = !useTcp, onClick = { useTcp = false }, modifier = fields.row("udp").ringToTop(left = false)) { Text("UDP") }
            }
            Text(
                if (useTcp) {
                    "TCP: works through firewalls and VLANs. Lost packets are resent, which can add delay."
                } else {
                    "UDP: lowest delay; lost packets show as glitches. The camera must be able to send UDP " +
                        "to the TV. If no video arrives within 8 s, the player switches to TCP."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
            return@TopDialog
        }
        TvTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Name, e.g. Front door",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
            onImeAction = {
                keyboard?.hide()
                urlField.requestFocus()
            },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp).focusRequester(nameField).then(fields.row("name")).ringToTop()
                // The switches are at the end of the line in between: the remote would go past them
                .focusProperties { down = fields.requester("audio") },
        )
        StreamTitle(
            "Main Stream",
            useAudio,
            { useAudio = !useAudio },
            fields.row("audio").ringToTop().focusProperties {
                up = nameField
                down = urlField
            },
        )
        TvTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = "rtsp://user:password@192.168.1.10:554/stream",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            onImeAction = {
                keyboard?.hide()
                substreamField.requestFocus()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(urlField).then(fields.row("url")).ringToTop()
                .focusProperties {
                    up = fields.requester("audio")
                    down = fields.requester("substreamAudio")
                },
        )
        StreamTitle(
            "Sub Stream (for small previews)",
            useSubstreamAudio,
            { useSubstreamAudio = !useSubstreamAudio },
            fields.row("substreamAudio").ringToTop().focusProperties {
                up = urlField
                down = substreamField
            },
        )
        TvTextField(
            value = substream,
            onValueChange = { substream = it },
            placeholder = "Optional",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = {
                keyboard?.hide()
                if (canSave) saveButton.requestFocus()
            },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .focusRequester(substreamField).then(fields.row("substream")).ringToTop()
                .focusProperties { up = fields.requester("substreamAudio") },
        )
        LaunchedEffect(Unit) { nameField.requestFocus() }
    }
    if (testing) {
        CameraTestDialog(name.trim(), url.trim(), substream.trim(), useAudio, useSubstreamAudio, onDismiss = { testing = false })
    }
}

/** A stream's title over its address, with its "Use audio track" switch at the end of the line */
@Composable
private fun StreamTitle(title: String, useAudio: Boolean, onToggleAudio: () -> Unit, toggleModifier: Modifier) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        AudioToggle(useAudio, onToggleAudio, toggleModifier)
    }
}

/** "Use audio track" and a small switch, which OK turns on or off */
@Composable
private fun AudioToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onToggle,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurfaceVariant,
            focusedContainerColor = colors.onSurface,
            focusedContentColor = colors.inverseOnSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Use audio track", style = MaterialTheme.typography.bodyMedium)
            SwitchMark(on, LocalContentColor.current, knob = if (focused) colors.onSurface else colors.surfaceVariant)
        }
    }
}

/** rtsp:// or rtsps:// with a host, e.g. rtsp://user:pass@192.168.1.10:554/stream1 */
private fun isValidRtspUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    return uri.scheme?.lowercase() in setOf("rtsp", "rtsps") && !uri.host.isNullOrEmpty()
}
