package com.evofactory.homeboard

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

/** Adds or edits one camera: its name, RTSP address, optional substream address and transport. */
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
    val keyboard = LocalSoftwareKeyboardController.current
    val nameField = remember { FocusRequester() }
    val urlField = remember { FocusRequester() }
    val substreamField = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val cancelButton = remember { FocusRequester() }
    val canSave = name.isNotBlank() && isValidRtspUrl(url.trim()) && (substream.isBlank() || isValidRtspUrl(substream.trim()))

    val toSave = if (canSave) saveButton else cancelButton

    fun save() {
        if (!canSave) return
        keyboard?.hide()
        onSave(camera.copy(name = name.trim(), url = url.trim(), useTcp = useTcp, substreamUrl = substream.trim()))
        onDismiss()
    }

    TopDialog(title = if (isNew) "Add camera" else "Edit camera", onDismiss = onDismiss, fullWidth = true) {
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
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp).focusRequester(nameField).jumpToActions(left = cancelButton, right = toSave),
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
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp).focusRequester(urlField).jumpToActions(left = cancelButton, right = toSave),
        )
        TvTextField(
            value = substream,
            onValueChange = { substream = it },
            placeholder = "Substream (optional): plays live in the Cameras tab",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = {
                keyboard?.hide()
                if (canSave) saveButton.requestFocus()
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(substreamField).jumpToActions(left = cancelButton, right = toSave),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Transport", style = MaterialTheme.typography.titleSmall)
            FilterChip(selected = useTcp, onClick = { useTcp = true }) { Text("TCP") }
            FilterChip(selected = !useTcp, onClick = { useTcp = false }) { Text("UDP") }
            Spacer(Modifier.weight(1f))
            // Greyed out until there's a name and a valid rtsp:// address
            Button(onClick = ::save, enabled = canSave, modifier = Modifier.focusRequester(saveButton)) {
                Text("Save")
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelButton)) { Text("Cancel") }
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
        LaunchedEffect(Unit) { nameField.requestFocus() }
    }
}

/** rtsp:// or rtsps:// with a host, e.g. rtsp://user:pass@192.168.1.10:554/stream1 */
private fun isValidRtspUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    return uri.scheme?.lowercase() in setOf("rtsp", "rtsps") && !uri.host.isNullOrEmpty()
}
