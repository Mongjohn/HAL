package com.evofactory.hal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** Home Assistant login and launcher top-bar actions. */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
fun HomeAssistantDialog(
    initialServer: HaServer,
    initialUsername: String,
    onEditDashboard: () -> Unit,
    initialTopBar: List<String>,
    loginRejected: Boolean,
    onSave: (server: HaServer, username: String, topBar: List<String>) -> Unit,
    onLogOut: () -> Unit,
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
    var topBar by remember { mutableStateOf(initialTopBar) }
    // The connected Home Assistant and what can be picked on it; set = second step, choosing
    var connected by remember { mutableStateOf<HaServer?>(null) }
    var available by remember { mutableStateOf<List<HaEntity>?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Logging in here, rather than using the saved login
    var showLogin by remember { mutableStateOf(!initialServer.isSet || loginRejected) }
    var actionQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val urlField = remember { FocusRequester() }
    val firstLoginField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }
    val connectButton = remember { FocusRequester() }
    val closeButton = remember { FocusRequester() }
    val actionSearch = remember { FocusRequester() }
    // To come back to the item last on, from the top row
    val loginFocus = remember { ListFocus() }
    val actionFocus = remember { ListFocus() }
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
                val states = haSession(server) { getStates() }
                available = states.sortedBy { it.name.lowercase() }
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

    // Ends the login, saved or made here, and goes back to logging in; the address and username stay
    fun logOut() {
        unsavedLogin?.let(onDiscardLogin)
        unsavedLogin = null
        if (initialServer.isSet) onLogOut()
        refreshToken = ""
        token = ""
        password = ""
        codeFlow = null
        connected = null
        available = null
        status = null
        showLogin = true
    }

    val entities = available
    val server = connected
    val logOutButton = DialogButton("Log out", onClick = ::logOut)

    if (entities == null || server == null) {
        if (!showLogin) {
            // Connecting with the saved login
            TopDialog(title = "Home Assistant", onDismiss = onDismiss, closeFocus = closeButton) {
                Text(
                    status ?: "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                LaunchedEffect(Unit) {
                    closeButton.requestFocus()
                    connect()
                }
            }
            return
        }
        val loginKeys = listOf("url", "with-password", "with-token") + when {
            useToken -> listOf("token")
            codeFlow != null -> listOf("code")
            else -> listOf("username", "password")
        }
        TopDialog(
            title = "Home Assistant",
            onDismiss = onDismiss,
            buttons = listOfNotNull(
                // Greyed out until everything needed is filled in. Stays enabled while connecting
                // (presses are ignored), so the remote keeps its place on it
                DialogButton(
                    when {
                        useToken || reuseLogin -> "Connect"
                        codeFlow != null -> "Verify"
                        else -> "Log in"
                    },
                    enabled = canConnect,
                    primary = true,
                    focus = connectButton,
                    onClick = ::connect,
                ),
                logOutButton.takeIf { initialServer.isSet },
            ),
            contentFocus = { loginFocus.current(loginKeys) },
        ) {
            val fieldModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
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
                modifier = fieldModifier.focusRequester(urlField).then(loginFocus.row("url")).ringToTop(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log in with", style = MaterialTheme.typography.titleSmall)
                FilterChip(
                    selected = !useToken,
                    onClick = { useToken = false },
                    modifier = loginFocus.row("with-password").ringToTop(right = false),
                ) { Text("Username and password") }
                FilterChip(
                    selected = useToken,
                    onClick = { useToken = true },
                    modifier = loginFocus.row("with-token").ringToTop(left = false),
                ) { Text("Access token") }
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
                    modifier = fieldModifier.focusRequester(firstLoginField).then(loginFocus.row("token")).ringToTop(),
                )
                codeFlow != null -> {
                    TvTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = "Two-factor code",
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        onImeAction = ::connect,
                        modifier = fieldModifier.focusRequester(firstLoginField).then(loginFocus.row("code")).ringToTop(),
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
                        modifier = Modifier.weight(1f).focusRequester(firstLoginField).then(loginFocus.row("username")).ringToTop(right = false),
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
                        modifier = Modifier.weight(1f).focusRequester(passwordField).then(loginFocus.row("password")).ringToTop(left = false),
                    )
                }
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
        }
        return
    }

    val sections = pickerSections(entities.filter { it.isAction }, actionQuery, initialTopBar)
    val keys = sections.flatMap { (_, items) -> items.map { it.id } }
    fun save() {
        onSave(server, if (server.refreshToken.isEmpty()) "" else loginUsername, topBar)
        unsavedLogin = null
        onDismiss()
    }
    TopDialog(
        title = "Home Assistant · Top bar actions",
        width = 720.dp,
        onDismiss = onDismiss,
        buttons = listOf(
            logOutButton,
            DialogButton("Edit dashboard in browser", onClick = {
                save()
                onEditDashboard()
            }),
            DialogButton("Save", primary = true, onClick = ::save),
        ),
        contentFocus = { actionFocus.currentOrNull(keys) ?: actionSearch },
        searchFocus = { actionSearch },
        hint = "${topBar.size} of $MAX_HA_ACTIONS in the top bar",
    ) {
        EntityPicker(
            sections = sections,
            query = actionQuery,
            onQueryChange = { actionQuery = it },
            search = actionSearch,
            rows = actionFocus,
            mark = { if (it.id in topBar) "✓ Top bar" else "" },
            onPress = { entity ->
                when {
                    entity.id in topBar -> topBar = topBar - entity.id
                    topBar.size < MAX_HA_ACTIONS -> topBar = topBar + entity.id
                }
            },
        )
    }
}

/**
 * [entities] matching every word of [query] (in the name, the full name, the room or the id), in
 * sections: the weather, the rooms (alphabetical), then by type those without a room. Those in
 * [first] come first in each.
 */
private fun pickerSections(entities: List<HaEntity>, query: String, first: List<String>): List<Pair<String, List<HaEntity>>> {
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    val listed = entities
        .filter { entity ->
            val text = "${entity.fullName} ${entity.name} ${entity.area.orEmpty()} ${entity.id}".lowercase()
            words.all { it in text }
        }
        .sortedBy { it.id !in first }
    val others = listed.filter { it.domain != "weather" }
    val byRoom = others.groupBy { it.area }
    val anyRoom = byRoom.keys.any { it != null }
    val noRoom = byRoom[null].orEmpty()
    return buildList {
        add("Weather" to listed.filter { it.domain == "weather" })
        byRoom.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { add(it to byRoom.getValue(it)) }
        noRoom.map { haEntityType(it.id) }.distinct().sortedBy { TypeOrder.indexOf(it).takeIf { i -> i >= 0 } ?: TypeOrder.size }.forEach { type ->
            add(sensorTypeTitle(type, anyRoom) to noRoom.filter { haEntityType(it.id) == type })
        }
    }.filter { (_, items) -> items.isNotEmpty() }
}

// Types without a room, in this order; any other after them
private val TypeOrder = listOf("sensor", "binary_sensor", "scene", "script", "button")

/** The Entities or Actions tab: a search, then [sections] of entities, each with its [mark] at the end. */
@Composable
private fun TopDialogScope.EntityPicker(
    sections: List<Pair<String, List<HaEntity>>>,
    query: String,
    onQueryChange: (String) -> Unit,
    search: FocusRequester,
    rows: ListFocus,
    mark: (HaEntity) -> String,
    onPress: (HaEntity) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val keys = sections.flatMap { (_, items) -> items.map { it.id } }
    TvTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Search",
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Search,
        onImeAction = {
            keyboard?.hide()
            rows.currentOrNull(keys)?.requestFocus()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(search)
            .ringToTop()
            .downToContent(),
    )
    if (keys.isEmpty()) {
        Text("Nothing matches", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 4.dp)) {
        sections.forEach { (title, items) ->
            entitySection(title, items, mark, onPress) { entity -> rows.row(entity.id).ringToTop() }
        }
    }
}

private fun LazyListScope.entitySection(
    title: String,
    entities: List<HaEntity>,
    mark: (HaEntity) -> String,
    onPress: (HaEntity) -> Unit,
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
        // One line in small text, so more of a long list fits: the name, then the reading and the
        // full name (it tells apart entities named alike on different devices) and the id
        Surface(
            onClick = { onPress(entity) },
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
                Text(mark(entity), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
