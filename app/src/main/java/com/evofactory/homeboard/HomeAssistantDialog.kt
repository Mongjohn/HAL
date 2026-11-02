package com.evofactory.homeboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** What the Home Assistant settings choose: the panel's sensors, or the top bar's action buttons */
enum class HaPick { Sensors, Actions }

/**
 * Home Assistant settings, in two steps: the address and how to log in (username and password, or
 * a long-lived token), then, once connected, what to show: sensors, or (with [pick] Actions) the
 * scenes and scripts of the top bar, at most [MAX_HA_ACTIONS]. Once set
 * up, it opens on the second step, connecting with the saved login; if that fails, it goes back
 * to logging in. The password isn't kept: logging in gives the TV a refresh token, which shows in
 * the Home Assistant profile and is ended when it's replaced, or on Log out in the settings menu.
 */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
fun HomeAssistantDialog(
    pick: HaPick,
    initialServer: HaServer,
    initialUsername: String,
    initialEntities: List<String>,
    loginRejected: Boolean,
    onSave: (server: HaServer, username: String, entities: List<String>) -> Unit,
    onDiscardLogin: (HaServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialServer.url) }
    var useToken by remember { mutableStateOf(initialServer.token.isNotEmpty()) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(initialServer.token) }
    // From the saved login, or from logging in here, and whose login it is
    var refreshToken by remember { mutableStateOf(if (loginRejected) "" else initialServer.refreshToken) }
    var loginUsername by remember { mutableStateOf(initialUsername) }
    // Logged in here but not saved yet: the login is ended if the dialog closes without saving
    var unsavedLogin by remember { mutableStateOf<HaServer?>(null) }
    // Set while Home Assistant waits for the two-factor code
    var codeFlow by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialEntities) }
    // The connected Home Assistant and what can be picked on it; set = second step, choosing tiles
    var connected by remember { mutableStateOf<HaServer?>(null) }
    var available by remember { mutableStateOf<List<HaEntity>?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Logging in here, rather than using the saved login
    var showLogin by remember { mutableStateOf(!initialServer.isSet || loginRejected) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val urlField = remember { FocusRequester() }
    val firstLoginField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }
    val connectButton = remember { FocusRequester() }
    val closeLoginButton = remember { FocusRequester() }
    // Without a password, the saved login is used (if it's for this username)
    val reuseLogin = password.isEmpty() && refreshToken.isNotEmpty() && username.trim() == loginUsername
    val canConnect = normalizeHaUrl(url) != null && when {
        useToken -> token.isNotBlank()
        codeFlow != null -> code.isNotBlank()
        else -> (username.isNotBlank() && password.isNotEmpty()) || reuseLogin
    }

    DisposableEffect(Unit) { onDispose { unsavedLogin?.let(onDiscardLogin) } }

    fun connect() {
        val baseUrl = normalizeHaUrl(url) ?: return
        if (!canConnect || connecting) return
        keyboard?.hide()
        url = baseUrl
        status = if (useToken || reuseLogin) "Connecting to $baseUrl…" else "Logging in…"
        connecting = true
        scope.launch {
            try {
                val server = if (useToken) {
                    HaServer(baseUrl, token = token.trim())
                } else {
                    val flow = codeFlow
                    val step = when {
                        flow != null -> haSendCode(baseUrl, flow, code.trim())
                        password.isNotEmpty() -> haLogIn(baseUrl, username.trim(), password)
                        else -> null
                    }
                    when (step) {
                        is HaLoginStep.NeedsCode -> {
                            codeFlow = step.flowId
                            code = ""
                            status = step.error ?: "Enter the code from your authenticator app"
                            return@launch
                        }
                        is HaLoginStep.LoggedIn -> {
                            unsavedLogin?.let(onDiscardLogin)
                            unsavedLogin = HaServer(baseUrl, refreshToken = step.refreshToken)
                            refreshToken = step.refreshToken
                            loginUsername = username.trim()
                            password = ""
                            codeFlow = null
                        }
                        null -> Unit
                    }
                    HaServer(baseUrl, refreshToken = refreshToken)
                }
                status = "Connecting to $baseUrl…"
                available = haSession(server) { getStates() }
                    .filter { it.isPickable }
                    .sortedBy { it.name.lowercase() }
                connected = server
                status = null
            } catch (e: HaException) {
                // A failed code step can't be retried (e.g. too many wrong codes): log in again
                codeFlow = null
                status = e.message
                showLogin = true
                // The saved login no longer works: the password is needed
                if (e.tokenRejected && !useToken) refreshToken = ""
            } finally {
                connecting = false
            }
        }
    }

    TopDialog(
        title = when {
            connected == null || available == null -> "Home Assistant"
            pick == HaPick.Actions -> "Home Assistant action buttons"
            else -> "Home Assistant sensors"
        },
        onDismiss = onDismiss,
    ) {
        val entities = available
        val server = connected
        if ((entities == null || server == null) && !showLogin) {
            // Connecting with the saved login
            val closeButton = remember { FocusRequester() }
            Text(
                status ?: "Connecting…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeButton)) { Text("Close") }
            }
            LaunchedEffect(Unit) {
                closeButton.requestFocus()
                connect()
            }
        } else if (entities == null || server == null) {
            val fieldModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            val toConnect = if (canConnect) connectButton else closeLoginButton
            TvTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "Address, e.g. http://192.168.1.20:8123",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                onImeAction = {
                    keyboard?.hide()
                    firstLoginField.requestFocus()
                },
                modifier = fieldModifier.focusRequester(urlField).jumpToActions(left = closeLoginButton, right = toConnect),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log in with", style = MaterialTheme.typography.titleSmall)
                FilterChip(selected = !useToken, onClick = { useToken = false }) { Text("Username and password") }
                FilterChip(selected = useToken, onClick = { useToken = true }) { Text("Access token") }
            }
            when {
                useToken -> TvTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "Long-lived access token",
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        keyboard?.hide()
                        if (canConnect) connectButton.requestFocus()
                    },
                    modifier = fieldModifier.focusRequester(firstLoginField).jumpToActions(left = closeLoginButton, right = toConnect),
                )
                codeFlow != null -> {
                    TvTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = "Two-factor code",
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        onImeAction = ::connect,
                        modifier = fieldModifier.focusRequester(firstLoginField).jumpToActions(left = closeLoginButton, right = toConnect),
                    )
                    LaunchedEffect(Unit) { firstLoginField.requestFocus() }
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "Username",
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        onImeAction = {
                            keyboard?.hide()
                            passwordField.requestFocus()
                        },
                        // Side by side: Right goes to the password, Left comes back
                        modifier = Modifier.weight(1f).focusRequester(firstLoginField).focusProperties { left = closeLoginButton },
                    )
                    TvTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = if (reuseLogin) "Password (logged in already)" else "Password",
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            keyboard?.hide()
                            if (canConnect) connectButton.requestFocus()
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f).focusRequester(passwordField).focusProperties { right = toConnect },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Greyed out until everything needed is filled in. Stays enabled while connecting
                // (presses are ignored), so the remote keeps its place on it
                Button(onClick = ::connect, enabled = canConnect, modifier = Modifier.focusRequester(connectButton)) {
                    Text(
                        when {
                            useToken || reuseLogin -> "Connect"
                            codeFlow != null -> "Verify"
                            else -> "Log in"
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeLoginButton)) { Text("Close") }
            }
            Text(
                status ?: when {
                    useToken -> "Create the token in your Home Assistant profile → Security. It's long: type it " +
                        "with the Google TV app's keyboard on your phone, where you can paste it."
                    else -> "The password isn't stored: the TV keeps a login, listed in your Home Assistant " +
                        "profile → Security. A user just for the TV, without admin rights, is safest."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            LaunchedEffect(Unit) {
                when {
                    canConnect -> connectButton
                    normalizeHaUrl(url) != null && !useToken && username.isNotBlank() -> passwordField
                    else -> urlField
                }.requestFocus()
            }
        } else {
            var query by remember { mutableStateOf("") }
            val searchField = remember { FocusRequester() }
            val saveButton = remember { FocusRequester() }
            val closeButton = remember { FocusRequester() }
            // To come back to the item last on: Left from Save, Right from Close and the search
            val itemFocus = remember { ListFocus() }
            val limit = if (pick == HaPick.Actions) MAX_HA_ACTIONS else null
            // Every word typed, in the name, the full name (with the device's), the room or the id
            val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
            val shown = entities.filter { it.isAction == (pick == HaPick.Actions) }.filter { entity ->
                val text = "${entity.fullName} ${entity.name} ${entity.area.orEmpty()} ${entity.id}".lowercase()
                words.all { it in text }
            }
            // What was chosen before comes first, so it can be seen in a long list. Not what's
            // chosen now: items would jump around while choosing
            val listed = shown.sortedBy { it.id !in initialEntities }
            // Scenes, scripts, weather, then sensors by room (alphabetical), then sensors without a room
            val sensorsByRoom = listed.filter { it.domain == "sensor" }.groupBy { it.area }
            val sections = buildList {
                add("Scenes" to listed.filter { it.domain == "scene" })
                add("Scripts" to listed.filter { it.domain == "script" })
                add("Weather" to listed.filter { it.domain == "weather" })
                sensorsByRoom.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { add(it to sensorsByRoom.getValue(it)) }
                add((if (sensorsByRoom.keys.any { it != null }) "Other sensors" else "Sensors") to sensorsByRoom[null].orEmpty())
            }.filter { (_, items) -> items.isNotEmpty() }

            val full = limit != null && selected.size >= limit

            fun toggle(entity: HaEntity) {
                selected = when {
                    entity.id in selected -> selected - entity.id
                    // The current choice, not [full]: a press can come before the list recomposes
                    limit != null && selected.size >= limit -> selected
                    else -> selected + entity.id
                }
            }

            // The item last on, if still listed; otherwise the first in the order shown
            fun listFocus(): FocusRequester? =
                sections.flatMap { (_, items) -> items.map { it.id } }.takeIf { it.isNotEmpty() }?.let(itemFocus::current)

            TvTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
                onImeAction = {
                    keyboard?.hide()
                    listFocus()?.requestFocus()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(searchField)
                    .focusProperties {
                        left = closeButton
                        listFocus()?.let { right = it }
                    },
            )
            Text(
                when {
                    shown.isEmpty() -> "Nothing matches"
                    limit == null -> "OK selects · ${selected.size} chosen"
                    full -> "$limit of $limit chosen · unselect one to choose another"
                    else -> "OK selects · ${selected.size} of $limit chosen"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                sections.forEach { (title, items) ->
                    entitySection(title, items, selected, ::toggle) { entity ->
                        // Left goes back to the search, Right to Save
                        itemFocus.row(entity.id).jumpToActions(left = searchField, right = saveButton)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Only when logging in here: to change the login
                if (showLogin) {
                    OutlinedButton(onClick = {
                        available = null
                        connected = null
                    }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                // On the right, where Down from the list lands
                Button(
                    onClick = {
                        // Drops entities that no longer exist
                        val ids = entities.map { it.id }.toSet()
                        onSave(server, if (server.refreshToken.isEmpty()) "" else loginUsername, selected.filter { it in ids })
                        unsavedLogin = null
                        onDismiss()
                    },
                    // Left goes back into the list, where it was
                    modifier = Modifier.focusRequester(saveButton).focusProperties { listFocus()?.let { left = it } },
                ) { Text("Save") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeButton).focusProperties { listFocus()?.let { right = it } },
                ) { Text("Close") }
            }
            // Something chosen already: start on the list, to change it. Otherwise on the search.
            LaunchedEffect(Unit) {
                val first = listFocus()
                if (selected.isNotEmpty() && first != null) first.requestFocus() else searchField.requestFocus()
            }
        }
    }
}

private fun LazyListScope.entitySection(
    title: String,
    entities: List<HaEntity>,
    selected: List<String>,
    onToggle: (HaEntity) -> Unit,
    itemModifier: (HaEntity) -> Modifier,
) {
    if (entities.isEmpty()) return
    item(key = "section $title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    items(entities, key = { it.id }) { entity ->
        val checked = entity.id in selected
        // One line in small text, so more of a long list fits: the name, then the reading and the
        // full name (it tells apart entities named alike on different devices) and the id
        Surface(
            onClick = { onToggle(entity) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth().then(itemModifier(entity)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdiIcon(entity.icon, size = 18.dp)
                Text(
                    entity.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
                val detail = listOfNotNull(
                    entity.reading.takeUnless { entity.isAction },
                    entity.fullName.takeIf { it != entity.name },
                    entity.id,
                )
                Text(
                    detail.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(if (checked) "✓" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
