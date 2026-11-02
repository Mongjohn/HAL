package com.evofactory.homeboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// The panel next to the camera (Home Assistant or the other cameras) is at least this wide; it
// also gets whatever width the camera leaves
private val SidePanelWidth = 260.dp

// App tiles from this 0-based slot on have the side panel above them, the earlier ones the camera
private const val FIRST_SLOT_UNDER_PANEL = 3

class MainActivity : ComponentActivity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private var installed by mutableStateOf<List<InstalledApp>?>(null)
    private var order by mutableStateOf(emptyList<String>())
    private var hidden by mutableStateOf(emptyList<String>())
    private var showHidden by mutableStateOf(false)
    private var pingOpen by mutableStateOf(false)
    private var networkStatusOpen by mutableStateOf(false)
    private var camerasOpen by mutableStateOf(false)
    private var cameras by mutableStateOf(emptyList<Camera>())
    private var cameraGridLive by mutableStateOf(true)
    private var minimizeVideoStreams by mutableStateOf(false)
    private var selectedCamera by mutableStateOf("")
    private var tab by mutableStateOf(HomeTab.Home)
    private var haPicker by mutableStateOf<HaPick?>(null)
    private var haServer by mutableStateOf(HaServer(""))
    private var haEntities by mutableStateOf(emptyList<String>())
    private var haActions by mutableStateOf(emptyList<String>())
    private var frigate by mutableStateOf(FrigateServer())
    private var frigateOpen by mutableStateOf(false)
    private var launcherSettingsOpen by mutableStateOf(false)
    private var stuckPlayersOpen by mutableStateOf(false)
    private var replaceHome by mutableStateOf(false)
    private var catchHomeKey by mutableStateOf(false)
    private var reopenAfterHome by mutableStateOf(false)
    private var showFreeMemory by mutableStateOf(false)
    private var exportOpen by mutableStateOf(false)
    private var importOpen by mutableStateOf(false)
    private var watchNext by mutableStateOf(emptyList<WatchItem>())
    private var watchNextReadAt by mutableStateOf(0L)
    private var hiddenWatching by mutableStateOf(emptyList<String>())

    // Home override's crash guard: the launcher counts as started once it has stayed up a moment
    private val confirmStarted = Runnable {
        running = true
        if (prefs.overrideLaunches != 0) prefs.overrideLaunches = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Last time's list: the tiles and the selection are there from the first frame
        installed = prefs.installedApps.ifEmpty { null }
        order = prefs.order
        hidden = prefs.hidden
        cameras = prefs.cameras
        cameraGridLive = prefs.cameraGridLive
        minimizeVideoStreams = prefs.minimizeVideoStreams
        selectedCamera = prefs.selectedCamera
        haServer = HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken)
        haEntities = prefs.haEntities
        haActions = prefs.haActions
        frigate = prefs.frigate
        hiddenWatching = prefs.hiddenWatching
        // Android stops asking by itself after it's been refused. Answering comes back to onResume.
        if (!canReadWatchNext()) requestPermissions(arrayOf(READ_TV_LISTINGS), 0)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val all = installed
                    val visible = remember(all, order, hidden) { all?.let { arrange(it, order, hidden) } }
                    val hiddenApps = remember(all, hidden) {
                        all?.let { apps -> hidden.mapNotNull { key -> apps.find { it.key == key } } }
                    }
                    val camera = cameras.find { it.id == selectedCamera } ?: cameras.firstOrNull()
                    val mainCameraFocus = remember { FocusRequester() }
                    val haSensorsFocus = remember { FocusRequester() }
                    val networkFocus = remember { FocusRequester() }
                    val cogFocus = remember { FocusRequester() }
                    val secondTabFocus = remember { FocusRequester() }
                    val homeTabFocus = remember { FocusRequester() }
                    val alertsTabFocus = remember { FocusRequester() }
                    var topBarSelected by remember { mutableStateOf(false) }
                    var homeTabSelected by remember { mutableStateOf(false) }
                    var firstTileSelected by remember { mutableStateOf(false) }
                    var goToFirstTile by remember { mutableIntStateOf(0) }
                    // Back never leaves the launcher, it moves the selection: the top bar to Home,
                    // anything else to the first tile, and the first tile to the cog
                    BackHandler {
                        when {
                            topBarSelected && !homeTabSelected -> runCatching { homeTabFocus.requestFocus() }
                            firstTileSelected -> runCatching { cogFocus.requestFocus() }
                            else -> goToFirstTile++
                        }
                    }
                    val actions = haActions.take(MAX_HA_ACTIONS)
                    val haConnection = rememberHaConnection(haServer, remember(haEntities, actions) { haEntities + actions })
                    val sideContent = haServer.isSet
                    // Set when a camera's picked in the grid: it's selected once it's on screen
                    var selectMainCamera by remember { mutableStateOf(false) }
                    LaunchedEffect(tab, selectMainCamera) {
                        if (!selectMainCamera || tab != HomeTab.Home) return@LaunchedEffect
                        withFrameNanos { }
                        runCatching { mainCameraFocus.requestFocus() }
                        selectMainCamera = false
                    }
                    val continueItems = remember(watchNext, watchNextReadAt, hiddenWatching, visible) {
                        watchRow(watchNext, watchNextReadAt, visible.orEmpty().map { it.key }, hiddenWatching)
                    }
                    val appRowFocus = remember { FocusRequester() }
                    val continueFocus = remember { FocusRequester() }
                    var continueActive by remember { mutableStateOf(false) }
                    val frigateFeed = rememberFrigateFeed(frigate, onCertificateAccepted = { saveFrigate(frigate.copy(certPin = it)) })
                    val alertsRowFocus = remember { FocusRequester() }
                    // From reaching the row until the remote is back on the tiles, wherever it goes meanwhile
                    var alertsRowShown by remember { mutableStateOf(false) }
                    val alertsRowFeed = frigateFeed?.takeIf { tab != HomeTab.Alerts }
                    val hasAlertsRow = alertsRowFeed != null && alertsRowFeed.rowAlerts.isNotEmpty()
                    LaunchedEffect(hasAlertsRow) { if (!hasAlertsRow) alertsRowShown = false }
                    var tilesRowSelected by remember { mutableStateOf(false) }
                    var otherTileSelected by remember { mutableStateOf(false) }
                    var noAlertsSelected by remember { mutableStateOf(false) }
                    val showNoAlertsTile = frigateFeed?.noRecentAlerts == true
                    // Worked out before the tile is removed, when focus is still known to be on it
                    val lostNoAlertsTile = remember(showNoAlertsTile) { !showNoAlertsTile && noAlertsSelected }
                    val alertsExist = frigateFeed != null && frigateFeed.rowAlerts.isNotEmpty()
                    // Above the rows, the row of alerts is what shows below
                    var middleSelected by remember { mutableStateOf(false) }
                    LaunchedEffect(hasAlertsRow, topBarSelected, middleSelected) {
                        if (hasAlertsRow && (topBarSelected || middleSelected)) alertsRowShown = true
                    }
                    // When alerts arrive or the Cameras tab opens, the selection on the first tile goes to the row
                    var alertsRowDue by remember { mutableStateOf(false) }
                    LaunchedEffect(alertsExist) { if (alertsExist) alertsRowDue = true }
                    LaunchedEffect(tab) { if (tab == HomeTab.Cameras) alertsRowDue = true }
                    LaunchedEffect(showNoAlertsTile) {
                        if (lostNoAlertsTile && (!alertsExist || tab == HomeTab.Alerts)) goToFirstTile++
                    }
                    LaunchedEffect(alertsRowDue, hasAlertsRow) {
                        if (!alertsRowDue || !hasAlertsRow) return@LaunchedEffect
                        // Not from another tile or the row below: those are in use
                        if (!otherTileSelected && !continueActive && (tilesRowSelected || lostNoAlertsTile)) {
                            alertsRowShown = true
                            withFrameNanos { } // let the row lay out, so its first card is focusable
                            runCatching { alertsRowFocus.requestFocus() }
                        }
                        alertsRowDue = false
                    }
                    // Where up goes from the bottom rows, from the item on screen in this 0-based slot
                    fun aboveRows(slot: Int) = when {
                        slot >= FIRST_SLOT_UNDER_PANEL && tab == HomeTab.Home && haServer.isSet -> haSensorsFocus
                        slot < FIRST_SLOT_UNDER_PANEL && tab == HomeTab.Home && camera != null -> mainCameraFocus
                        else -> FocusRequester.Default
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        HomeTopBar(
                            tab = tab,
                            onTabChange = { tab = it },
                            secondTabFocus = secondTabFocus,
                            homeTabFocus = homeTabFocus,
                            alertsTabFocus = alertsTabFocus,
                            onHomeTabSelected = { homeTabSelected = it },
                            showFreeMemory = showFreeMemory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = EdgePadding, top = TopPadding, end = EdgePadding)
                                .onFocusChanged { topBarSelected = it.hasFocus },
                            actions = {
                                // These get what the action buttons leave
                                StuckPlayersWarning(
                                    onClick = { stuckPlayersOpen = true },
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                HomeHelperWarning(
                                    replaceHome,
                                    onClick = { launcherSettingsOpen = true },
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (haServer.isSet) HaActionButtons(haConnection, actions)
                                NetworkButton(
                                    onClick = { networkStatusOpen = true },
                                    modifier = Modifier.focusRequester(networkFocus),
                                )
                            },
                        ) {
                            SettingsButton(
                                showHidden = showHidden,
                                hiddenCount = hiddenApps?.size ?: 0,
                                onShowHiddenChange = { showHidden = it },
                                onOpenCameras = { camerasOpen = true },
                                homeAssistantSet = haServer.isSet,
                                onOpenHomeAssistant = { haPicker = it },
                                onLogOutOfHomeAssistant = ::logOutOfHa,
                                onOpenFrigate = { frigateOpen = true },
                                onOpenPing = { pingOpen = true },
                                onOpenLauncherSettings = { launcherSettingsOpen = true },
                                modifier = Modifier.focusRequester(cogFocus),
                            )
                        }
                        if (tab == HomeTab.Cameras) {
                            CameraGrid(
                                cameras = cameras,
                                // Like the main camera: a clip opened from the alerts row may need its decoder
                                playSubstreams = cameraGridLive && !(minimizeVideoStreams && frigateFeed?.playing != null),
                                minimizeStreams = minimizeVideoStreams,
                                onSelect = {
                                    selectCamera(it)
                                    tab = HomeTab.Home
                                    selectMainCamera = true
                                },
                                onSetUp = { camerasOpen = true },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding).onFocusChanged { middleSelected = it.hasFocus },
                            )
                        } else if (tab == HomeTab.Alerts) {
                            FrigateAlertsGrid(
                                feed = frigateFeed,
                                onSetUp = { frigateOpen = true },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding),
                            )
                        } else {
                            // The camera, as large as fits above the app row, and Home Assistant
                            // next to it, which gets all the width the camera leaves
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(start = EdgePadding, top = EdgePadding, end = EdgePadding)
                                    .onFocusChanged { middleSelected = it.hasFocus },
                            ) {
                                val cameraWidth = minOf(
                                    maxHeight * 16f / 9f,
                                    if (sideContent) maxWidth - SidePanelWidth - EdgePadding else maxWidth,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(EdgePadding)) {
                                    Box(modifier = Modifier.width(cameraWidth).fillMaxHeight()) {
                                        // Stopped before a clip opens, so the clip can have its decoder
                                        if (camera != null && !(minimizeVideoStreams && frigateFeed?.playing != null)) {
                                            // Restarted when the panel appears or goes: the playing video
                                            // doesn't follow the camera's new size otherwise
                                            key(sideContent) {
                                                MainCamera(
                                                    camera = camera,
                                                    cameras = cameras,
                                                    minimizeStreams = minimizeVideoStreams,
                                                    onSelect = ::selectCamera,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(16f / 9f)
                                                        .focusRequester(mainCameraFocus)
                                                        .focusProperties { up = secondTabFocus },
                                                )
                                            }
                                        }
                                    }
                                    if (sideContent) {
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            HomeAssistantPanel(
                                                connection = haConnection,
                                                entityIds = haEntities,
                                                sensorsFocus = haSensorsFocus,
                                                // The button next to the cog
                                                upFocus = networkFocus,
                                                onLogIn = { haPicker = HaPick.Sensors },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // The tiles' grow room counts towards the margins above and below the row
                        Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                        SlidingRows(
                            revealed = continueActive,
                            aboveRevealed = alertsRowShown && hasAlertsRow,
                            above = {
                                if (alertsRowFeed != null) {
                                    FrigateAlertsRow(
                                        feed = alertsRowFeed,
                                        shown = alertsRowShown,
                                        firstCardFocus = alertsRowFocus,
                                        upFocus = ::aboveRows,
                                        downFocus = appRowFocus,
                                        onEnter = { alertsRowShown = true },
                                        onLeave = { goToFirstTile++ },
                                    )
                                }
                            },
                            first = {
                                AppRowSpace(Modifier.onFocusChanged { tilesRowSelected = it.hasFocus }) {
                                    if (visible != null && hiddenApps != null) AppRow(
                                        apps = visible,
                                        hiddenApps = hiddenApps,
                                        showHidden = showHidden,
                                        onShowHiddenChange = { showHidden = it },
                                        onHide = { app ->
                                            // Hidden apps leave the row order and queue up at the end
                                            saveOrder(visible.map { it.key } - app.key)
                                            saveHidden(hidden + app.key)
                                        },
                                        onUnhide = { app ->
                                            // Back at the end of the visible apps
                                            saveOrder(visible.map { it.key } + app.key)
                                            saveHidden(hidden - app.key)
                                        },
                                        onReorder = { list -> saveOrder(list.map { it.key }) },
                                        upFocus = { slot ->
                                            when {
                                                hasAlertsRow -> alertsRowFocus
                                                else -> aboveRows(slot)
                                            }
                                        },
                                        downFocus = if (continueItems.isNotEmpty()) continueFocus else FocusRequester.Cancel,
                                        rowFocus = appRowFocus,
                                        goToFirstTile = goToFirstTile,
                                        onFirstTileSelected = { firstTileSelected = it },
                                        onOtherTileSelected = { otherTileSelected = it },
                                        showNoAlertsTile = showNoAlertsTile,
                                        onNoAlertsSelected = { noAlertsSelected = it },
                                        onNoAlertsClick = {
                                            tab = HomeTab.Alerts
                                            runCatching { alertsTabFocus.requestFocus() }
                                        },
                                        modifier = Modifier.onFocusChanged { if (it.hasFocus) alertsRowShown = false },
                                    ) else AppRowPlaceholder()
                                }
                            },
                            second = {
                                ContinueWatchingRow(
                                    items = continueItems,
                                    apps = all.orEmpty(),
                                    firstCardFocus = continueFocus,
                                    upFocus = appRowFocus,
                                    onActiveChange = { continueActive = it },
                                    onHide = {
                                        hiddenWatching = hiddenWatching + it.hideKey
                                        prefs.hiddenWatching = hiddenWatching
                                    },
                                    onLeave = { goToFirstTile++ },
                                )
                            },
                        )
                        Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                    }
                    frigateFeed?.let { FrigateClipPlayer(it) }
                    if (camerasOpen) {
                        CamerasDialog(
                            cameras = cameras,
                            onChange = ::saveCameras,
                            playSubstreams = cameraGridLive,
                            onPlaySubstreamsChange = {
                                cameraGridLive = it
                                prefs.cameraGridLive = it
                            },
                            minimizeStreams = minimizeVideoStreams,
                            onMinimizeStreamsChange = {
                                minimizeVideoStreams = it
                                prefs.minimizeVideoStreams = it
                            },
                            onDismiss = { camerasOpen = false },
                        )
                    }
                    haPicker?.let { pick ->
                        HomeAssistantDialog(
                            pick = pick,
                            initialServer = haServer,
                            initialUsername = remember { prefs.haUsername },
                            initialEntities = if (pick == HaPick.Actions) haActions else haEntities,
                            loginRejected = haConnection.status == HaStatus.TokenRejected,
                            onSave = { server, username, entities ->
                                // A replaced username-and-password login is ended in Home Assistant
                                if (haServer.refreshToken != server.refreshToken) revokeHaLogin(haServer)
                                haServer = server
                                prefs.haUrl = server.url
                                prefs.haToken = server.token
                                prefs.haRefreshToken = server.refreshToken
                                prefs.haUsername = username
                                if (pick == HaPick.Actions) {
                                    haActions = entities
                                    prefs.haActions = entities
                                } else {
                                    haEntities = entities
                                    prefs.haEntities = entities
                                }
                            },
                            onDiscardLogin = ::revokeHaLogin,
                            onDismiss = { haPicker = null },
                        )
                    }
                    if (frigateOpen) {
                        FrigateDialog(
                            initial = frigate,
                            onSave = ::saveFrigate,
                            onDismiss = { frigateOpen = false },
                        )
                    }
                    if (launcherSettingsOpen) {
                        LauncherSettingsDialog(
                            catchHomeKey = catchHomeKey,
                            reopenAfterHome = reopenAfterHome,
                            autoTurnedOff = remember { prefs.replaceHomeAutoOff },
                            onCatchHomeKeyChange = { on ->
                                prefs.catchHomeKey = on
                                homeOverrideChanged()
                            },
                            onReopenAfterHomeChange = { on ->
                                prefs.reopenAfterHome = on
                                homeOverrideChanged()
                            },
                            showFreeMemory = showFreeMemory,
                            onShowFreeMemoryChange = { on ->
                                prefs.showFreeMemory = on
                                showFreeMemory = on
                            },
                            onExport = { exportOpen = true },
                            onImport = { importOpen = true },
                            onDismiss = { launcherSettingsOpen = false },
                        )
                    }
                    if (stuckPlayersOpen) {
                        StuckPlayersDialog(onDismiss = { stuckPlayersOpen = false })
                    }
                    if (exportOpen) {
                        ExportDialog(
                            file = SettingsFile(
                                cameras = cameras,
                                login = haServer.takeIf { it.isSet },
                                username = remember { prefs.haUsername },
                                sensors = haEntities.map { NamedEntity(it, haConnection.entities[it]?.name ?: it) },
                                actions = haActions.map { NamedEntity(it, haConnection.entities[it]?.name ?: it) },
                                frigate = frigate.takeIf { it.isSet },
                                appTiles = visible.orEmpty().map { NamedEntity(it.key, it.label) },
                            ),
                            onDismiss = { exportOpen = false },
                        )
                    }
                    if (importOpen) {
                        ImportDialog(onImport = ::applyImport, onDismiss = { importOpen = false })
                    }
                    if (networkStatusOpen) {
                        NetworkStatusDialog(
                            frigate = frigateFeed,
                            onOpenPing = { pingOpen = true },
                            onDismiss = { networkStatusOpen = false },
                        )
                    }
                    if (pingOpen) {
                        PingDialog(
                            initialHost = remember { prefs.pingHost },
                            onHostEntered = { prefs.pingHost = it },
                            onDismiss = { pingOpen = false },
                        )
                    }
                }
            }
        }
    }

    /** Ends the login; the address, username and chosen entities stay for logging in again */
    private fun logOutOfHa() {
        revokeHaLogin(haServer)
        haServer = HaServer(haServer.url)
        prefs.haToken = ""
        prefs.haRefreshToken = ""
    }

    private fun revokeHaLogin(server: HaServer) {
        lifecycleScope.launch { haRevoke(server) }
    }

    /** Adds what's in the file. A camera whose name is already taken gets "(imported)" after its name. */
    private fun applyImport(file: SettingsFile) {
        // The same camera, by its exact addresses, only takes the imported name. Each camera here
        // matches one in the file; any more copies in the file aren't added.
        val sameUrls = { a: Camera, b: Camera -> a.url == b.url && a.substreamUrl == b.substreamUrl }
        val renamed = mutableMapOf<String, String>()
        val new = mutableListOf<Camera>()
        file.cameras.forEach { camera ->
            val same = cameras.filter { sameUrls(it, camera) }
            val free = same.firstOrNull { it.id !in renamed }
            when {
                free != null -> renamed[free.id] = camera.name
                same.isEmpty() && new.none { sameUrls(it, camera) } -> new += camera
            }
        }
        val names = cameras.filter { it.id !in renamed }.map { it.name }.toMutableSet()
        fun uniqueName(wanted: String): String {
            var name = wanted
            while (name in names) name += " (imported)"
            names += name
            return name
        }
        val merged = cameras.map { camera -> renamed[camera.id]?.let { camera.copy(name = uniqueName(it)) } ?: camera } +
            new.map { it.copy(id = UUID.randomUUID().toString(), name = uniqueName(it.name)) }
        if (merged != cameras) saveCameras(merged)
        file.login?.let { server ->
            // A replaced username-and-password login is ended in Home Assistant
            if (haServer.refreshToken != server.refreshToken) revokeHaLogin(haServer)
            haServer = server
            prefs.haUrl = server.url
            prefs.haToken = server.token
            prefs.haRefreshToken = server.refreshToken
            prefs.haUsername = file.username
        }
        haEntities = (haEntities + file.sensors.map { it.id }).distinct()
        prefs.haEntities = haEntities
        haActions = (haActions + file.actions.map { it.id }).distinct()
        prefs.haActions = haActions
        file.frigate?.let(::saveFrigate)
        // The row becomes the apps in the file that are on this TV; the others can be unhidden later
        val apps = installed.orEmpty().map { it.key }
        val shown = file.appTiles.map { it.id }.filter { it in apps }
        if (shown.isNotEmpty()) {
            saveOrder(shown)
            saveHidden((hidden + apps).distinct() - shown.toSet())
        }
        Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
    }

    private fun saveFrigate(value: FrigateServer) {
        frigate = value
        prefs.frigate = value
    }

    private fun selectCamera(camera: Camera) {
        selectedCamera = camera.id
        prefs.selectedCamera = camera.id
    }

    private fun saveCameras(value: List<Camera>) {
        cameras = value
        prefs.cameras = value
        pruneCameraSnapshots(this, value.flatMap { listOf(it.url, it.substreamUrl) })
    }

    private fun saveOrder(value: List<String>) {
        order = value
        prefs.order = value
    }

    private fun saveHidden(value: List<String>) {
        hidden = value
        prefs.hidden = value
    }

    private fun homeOverrideChanged() {
        prefs.replaceHomeAutoOff = false
        updateReplaceHome()
    }

    private fun updateReplaceHome() {
        replaceHome = prefs.replaceHome
        catchHomeKey = prefs.catchHomeKey
        reopenAfterHome = prefs.reopenAfterHome
        showFreeMemory = prefs.showFreeMemory
    }

    override fun onResume() {
        super.onResume()
        // The home override may have switched itself off meanwhile
        updateReplaceHome()
        window.decorView.postDelayed(confirmStarted, 2_000)
        // Refresh in the background (apps may have been installed or removed meanwhile);
        // the current row stays on screen until the new list is ready
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadInstalledApps(this@MainActivity) }
            if (apps != installed) prefs.installedApps = apps
            installed = apps
        }
        // Shows watched meanwhile
        if (canReadWatchNext()) {
            lifecycleScope.launch {
                watchNext = withContext(Dispatchers.IO) { loadWatchNext(this@MainActivity) }
                watchNextReadAt = System.currentTimeMillis()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(confirmStarted)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
    }

    override fun onStop() {
        super.onStop()
        // Hidden apps are only revealed until you leave the launcher
        showHidden = false
    }

    companion object {
        /** Started properly and still alive; a crash takes the whole process, so this resets */
        @Volatile
        var running = false
            private set
    }
}
