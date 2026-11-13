package com.evofactory.hal

import android.database.ContentObserver
import android.media.tv.TvContract
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

// The panel next to the camera (Home Assistant or the other cameras) is at least this wide; it
// also gets whatever width the camera leaves

// App tiles from this 0-based slot on have the side panel above them, the earlier ones the camera
private const val FIRST_SLOT_UNDER_PANEL = 3

class MainActivity : ComponentActivity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private var installed by mutableStateOf<List<InstalledApp>?>(null)
    private var order by mutableStateOf(emptyList<String>())
    private var hidden by mutableStateOf(emptyList<String>())
    private var showAllAppsTile by mutableStateOf(true)
    private var allAppsOpen by mutableStateOf(false)
    private var pingOpen by mutableStateOf(false)
    private var networkStatusOpen by mutableStateOf(false)
    private var camerasOpen by mutableStateOf(false)
    private var cameras by mutableStateOf(emptyList<Camera>())
    private var cameraGridLive by mutableStateOf(true)
    private var showHomeCamera by mutableStateOf(true)
    private var minimizeVideoStreams by mutableStateOf(false)
    private var selectedCamera by mutableStateOf("")
    private var homeFocusRequest by mutableIntStateOf(0)
    private var tab by mutableStateOf(HomeTab.Home)
    private var dashboardEditorEnabled by mutableStateOf(false)
    private var dashboardEditorInfoOpen by mutableStateOf(false)
    private var dashboardLayouts by mutableStateOf(DashboardLayouts())
    private var haDialogOpen by mutableStateOf(false)
    private var haServer by mutableStateOf(HaServer(""))
    // Everything on the Assistant tab in its order, and the actions also in the top bar
    private var haEntities by mutableStateOf(emptyList<String>())
    private var haTopBar by mutableStateOf(emptyList<String>())
    // The Home Assistant dashboard the Assistant tab shows instead, if any
    private var haDisplay by mutableStateOf(HaDisplay())
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
    private var watchNext by mutableStateOf(emptyList<WatchNextCard>())
    private var artworkSettings by mutableStateOf(ArtworkSettings())
    private var artworkToken by mutableStateOf("")
    private var resumed by mutableStateOf(false)
    private var watchNextRefresh: Job? = null

    // Home override's crash guard: the launcher counts as started once it has stayed up a moment
    private val confirmStarted = Runnable {
        running = true
        if (prefs.overrideLaunches != 0) prefs.overrideLaunches = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_FOCUS_HAL_HOME) homeFocusRequest++
        // Last time's list: the tiles and the selection are there from the first frame
        installed = prefs.installedApps.ifEmpty { null }
        order = prefs.order
        hidden = prefs.hidden
        showAllAppsTile = prefs.showAllAppsTile
        cameras = prefs.cameras
        cameraGridLive = prefs.cameraGridLive
        showHomeCamera = prefs.showHomeCamera
        minimizeVideoStreams = prefs.minimizeVideoStreams
        selectedCamera = prefs.selectedCamera
        haServer = HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken)
        haEntities = prefs.haEntities
        haTopBar = prefs.haTopBar
        haDisplay = prefs.haDisplay
        dashboardLayouts = prefs.dashboardLayouts
        frigate = prefs.frigate
        artworkSettings = prefs.artworkSettings
        artworkToken = prefs.readArtworkToken()
        // Android stops asking by itself after it's been refused. Answering comes back to onResume.
        if (!canReadWatchNext()) requestPermissions(arrayOf(READ_TV_LISTINGS), 0)
        setContent {
            DisposableEffect(resumed, artworkSettings.enabled) {
                if (!resumed || !artworkSettings.enabled || !canReadWatchNext()) {
                    return@DisposableEffect onDispose {}
                }
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = refreshWatchNext()
                }
                val registered = try {
                    contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer)
                    true
                } catch (_: SecurityException) {
                    false
                }
                onDispose { if (registered) contentResolver.unregisterContentObserver(observer) }
            }
            MaterialTheme(colorScheme = AppColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val all = installed
                    val visible = remember(all, order, hidden) { all?.let { arrange(it, order, hidden) } }
                    val dockKeys = remember(visible, order, showAllAppsTile) {
                        dockTileKeys(visible.orEmpty().map { it.key }, order, showAllAppsTile)
                    }
                    val camera = if (showHomeCamera) cameras.find { it.id == selectedCamera } ?: cameras.firstOrNull() else null
                    val mainCameraFocus = remember { FocusRequester() }
                    val haSensorsFocus = remember { FocusRequester() }
                    val networkFocus = remember { FocusRequester() }
                    val cogFocus = remember { FocusRequester() }
                    val secondTabFocus = remember { FocusRequester() }
                    val homeTabFocus = remember { FocusRequester() }
                    val alertsTabFocus = remember { FocusRequester() }
                    val assistantTabFocus = remember { FocusRequester() }
                    var topBarSelected by remember { mutableStateOf(false) }
                    var homeTabSelected by remember { mutableStateOf(false) }
                    val bottomRow = tab != HomeTab.Assistant || (dashboardLayouts.assistant?.bottomRow ?: haDisplay.bottomRow)
                    var bottomRevealed by remember { mutableStateOf(false) }
                    val bottomShown = bottomRow || bottomRevealed
                    val bottomFraction by animateFloatAsState(if (bottomShown) 1f else 0f, label = "bottom rows")
                    // Back returns focus to the tabs.
                    BackHandler {
                        when {
                            topBarSelected && !homeTabSelected -> runCatching { homeTabFocus.requestFocus() }
                            !bottomShown -> if (!topBarSelected) runCatching { assistantTabFocus.requestFocus() }
                            else -> runCatching { homeTabFocus.requestFocus() }
                        }
                    }
                    // The launcher top bar has its own action selection.
                    val actions = haTopBar.take(MAX_HA_ACTIONS)
                    val followed = remember(dashboardLayouts, haEntities, haTopBar) {
                        (haTopBar + (dashboardLayouts.assistant?.entityIds ?: haEntities) +
                            (dashboardLayouts.home?.entityIds ?: haEntities.filterNot(::isHaAction))).distinct()
                    }
                    val legacyBoards = listOfNotNull(
                        haDisplay.dashboard.takeIf { dashboardLayouts.assistant == null },
                        haDisplay.homeDashboard.takeIf { dashboardLayouts.home == null },
                    ).distinct()
                    val haConnection = rememberHaConnection(haServer, followed, legacyBoards)
                    val openDashboardSetup: () -> Unit = {
                        if (!haServer.isSet || haConnection.status == HaStatus.TokenRejected) haDialogOpen = true
                        else openDashboardEditor()
                    }
                    val legacyHomeBoard = haConnection.dashboard(haDisplay.homeDashboard)
                    val homeLayout = remember(dashboardLayouts.home, dockKeys, haDisplay, legacyHomeBoard, haEntities) {
                        (dashboardLayouts.home ?: seedDashboardLayout(
                            legacyHomeBoard,
                            haDisplay.homeLayout.selectionCards(haEntities.filterNot(::isHaAction), haConnection.entities),
                            1, haDisplay.homeLayout.twoPerRow,
                        )).migrateAppsCard(dockKeys)
                    }
                    var focusHomeSurface by remember { mutableIntStateOf(0) }
                    var focusAssistantSurface by remember { mutableIntStateOf(0) }
                    LaunchedEffect(homeFocusRequest) {
                        if (homeFocusRequest == 0) return@LaunchedEffect
                        val assistantTarget = dashboardLayouts.assistant?.pages.orEmpty().any { page -> page.cards.any { it.focusOnHome } }
                        tab = if (assistantTarget) HomeTab.Assistant else HomeTab.Home
                        withFrameNanos { }
                        val fallback = if (assistantTarget) assistantTabFocus else homeTabFocus
                        runCatching { fallback.requestFocus() }
                        if (assistantTarget) focusAssistantSurface++ else focusHomeSurface++
                    }
                    var lastApp by remember { mutableStateOf<String?>(null) }
                    val watchNextByPackage = remember(watchNext) {
                        watchNextByApp(watchNext, prefs.hiddenWatchNext)
                    }
                    val frigateFeed = rememberFrigateFeed(frigate, onCertificateAccepted = { saveFrigate(frigate.copy(certPin = it)) })
                    val alertsRowFocus = remember { FocusRequester() }
                    val alertsRowFeed = frigateFeed?.takeIf { tab != HomeTab.Alerts && bottomRow }
                    var fullScreenCamera by remember { mutableStateOf<Camera?>(null) }
                    // Where up goes from the bottom rows, from the item on screen in this 0-based slot
                    fun aboveRows(slot: Int) = when {
                        // What's above moves sideways: the nearest card or the camera
                        tab == HomeTab.Home && (camera == null || slot >= FIRST_SLOT_UNDER_PANEL) -> haSensorsFocus
                        slot < FIRST_SLOT_UNDER_PANEL && tab == HomeTab.Home && camera != null -> mainCameraFocus
                        else -> FocusRequester.Default
                    }
                    // A number button from the Alerts or Assistant tab: the selection goes to the camera
                    var mainCameraDue by remember { mutableStateOf(false) }
                    LaunchedEffect(mainCameraDue, tab) {
                        if (!mainCameraDue || tab != HomeTab.Home) return@LaunchedEffect
                        withFrameNanos { } // let the Home tab lay out, so the camera is focusable
                        runCatching { mainCameraFocus.requestFocus() }
                        mainCameraDue = false
                    }
                    // A number button on the Cameras tab: the selection goes to that camera
                    var gridCameraDue by remember { mutableStateOf<String?>(null) }
                    // The Cameras tab's camera the selection was last on: channel up and down go on from it
                    var gridFocusedCamera by remember { mutableStateOf<String?>(null) }
                    // The colour buttons open the tabs, with the selection on the tab. A number button picks
                    // a camera: selected on the Cameras tab, as the Home tab's camera everywhere else. The
                    // dot button selects the cog.
                    fun onRemoteButton(event: KeyEvent): Boolean {
                        if (isSettingsButton(event)) {
                            if (event.type == KeyEventType.KeyDown) runCatching { cogFocus.requestFocus() }
                            return true
                        }
                        colorButtonTab(event)?.let { pressed ->
                            if (event.type == KeyEventType.KeyDown) {
                                tab = pressed
                                val tabFocus = when (pressed) {
                                    HomeTab.Home -> homeTabFocus
                                    HomeTab.Cameras -> secondTabFocus
                                    HomeTab.Alerts -> alertsTabFocus
                                    HomeTab.Assistant -> assistantTabFocus
                                }
                                runCatching { tabFocus.requestFocus() }
                            }
                            return true
                        }
                        // Channel up and down: the next or previous camera, on the Home tab wherever the
                        // selection is, and on the Cameras tab the selection moves to it
                        channelStep(event)?.let { step ->
                            if (tab != HomeTab.Home && tab != HomeTab.Cameras) return false
                            if (tab == HomeTab.Home && camera == null) return false
                            if (event.type == KeyEventType.KeyDown) {
                                if (tab == HomeTab.Cameras) {
                                    steppedCamera(cameras, gridFocusedCamera, step)?.let { gridCameraDue = it.id }
                                } else {
                                    steppedCamera(cameras, camera?.id, step)?.let(::selectCamera)
                                }
                            }
                            return true
                        }
                        val index = cameraButtonIndex(event) ?: return false
                        val picked = cameras.getOrNull(index)
                        if (event.type == KeyEventType.KeyDown && picked != null) {
                            if (tab == HomeTab.Cameras) {
                                gridCameraDue = picked.id
                            } else {
                                selectCamera(picked)
                                if (showHomeCamera && tab != HomeTab.Home) {
                                    tab = HomeTab.Home
                                    mainCameraDue = true
                                }
                            }
                        }
                        return true
                    }
                    DashboardArtwork(
                        settings = artworkSettings,
                        token = artworkToken,
                        visiblePackages = remember(visible) { visible.orEmpty().map { it.key }.toSet() },
                        watchNext = watchNextByPackage,
                        active = resumed,
                    )
                    Column(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::onRemoteButton)) {
                        HomeTopBar(
                            tab = tab,
                            onTabChange = { tab = it },
                            secondTabFocus = secondTabFocus,
                            homeTabFocus = homeTabFocus,
                            alertsTabFocus = alertsTabFocus,
                            assistantTabFocus = assistantTabFocus,
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
                                onOpenCameras = { camerasOpen = true },
                                onOpenHomeAssistant = { haDialogOpen = true },
                                onOpenFrigate = { frigateOpen = true },
                                onOpenLauncherSettings = { launcherSettingsOpen = true },
                                webEditorEnabled = dashboardEditorEnabled,
                                onToggleWebEditor = {
                                    if (dashboardEditorEnabled) {
                                        dashboardEditorEnabled = false
                                        dashboardEditorInfoOpen = false
                                    } else openDashboardEditor()
                                },
                                modifier = Modifier.focusRequester(cogFocus),
                            )
                        }
                        if (tab == HomeTab.Cameras) {
                            CameraGrid(
                                cameras = cameras,
                                // Like the main camera: a clip opened from the alerts row may need its decoder,
                                // and so may a camera shown full screen
                                playSubstreams = cameraGridLive &&
                                    !(minimizeVideoStreams && (frigateFeed?.playing != null || fullScreenCamera != null)),
                                minimizeStreams = minimizeVideoStreams,
                                spareDecoder = fullScreenCamera != null,
                                onSelect = { fullScreenCamera = it },
                                onSetUp = { camerasOpen = true },
                                selectCamera = gridCameraDue,
                                onCameraSelected = { gridCameraDue = null },
                                onCameraFocused = { gridFocusedCamera = it },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding),
                            )
                        } else if (tab == HomeTab.Alerts) {
                            FrigateAlertsGrid(
                                feed = frigateFeed,
                                onSetUp = { frigateOpen = true },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding),
                            )
                        } else if (tab == HomeTab.Assistant) {
                            HaAssistantTab(
                                connection = haConnection,
                                homeAssistantSet = haServer.isSet,
                                entityIds = haEntities,
                                dashboard = haDisplay.dashboard.takeIf { dashboardLayouts.assistant == null },
                                customLayout = dashboardLayouts.assistant,
                                homeFocusRequest = focusAssistantSurface,
                                layout = haDisplay.layout,
                                tabFocus = assistantTabFocus,
                                onSetUp = openDashboardSetup,
                                sectionColumns = haDisplay.sectionColumns,
                                toScreenEdge = !bottomShown,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding),
                            )
                        } else {
                            // The camera, stopped before a clip opens, so the clip can have its decoder
                            @Composable
                            fun CameraBox(width: Dp) {
                                Box(modifier = Modifier.width(width).fillMaxHeight()) {
                                    if (camera != null && !(minimizeVideoStreams && frigateFeed?.playing != null)) {
                                        // Restarted when the panel appears or goes: the playing video
                                        // doesn't follow the camera's new size otherwise
                                        key(Unit) {
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
                            }
                            BoxWithConstraints(
                                Modifier.weight(1f).fillMaxWidth()
                                    .padding(start = EdgePadding, top = EdgePadding, end = EdgePadding)
,
                            ) {
                                val columnWidth = dashboardColumnWidth(maxWidth)
                                val cameraWidth = minOf(maxHeight * 16f / 9f, maxWidth - columnWidth - EdgePadding)
                                    .coerceAtLeast(0.dp)
                                Row(horizontalArrangement = Arrangement.spacedBy(EdgePadding)) {
                                    if (camera != null) CameraBox(cameraWidth)
                                    HaAssistantTab(
                                        connection = haConnection,
                                        homeAssistantSet = haServer.isSet,
                                        entityIds = emptyList(),
                                        dashboard = null,
                                        customLayout = homeLayout,
                                        homeFocusRequest = focusHomeSurface,
                                        renderApps = { card, target, height, neighbor ->
                                            DashboardAppsCard(card, all.orEmpty(), target, height, lastApp, neighbor,
                                                { lastApp = it }, { allAppsOpen = true })
                                        },
                                        tabFocus = networkFocus,
                                        onSetUp = openDashboardSetup,
                                        inset = 0.dp,
                                        modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(haSensorsFocus),
                                    )
                                }
                            }
                        }
                        // Previews can hide on the Assistant tab until reached by focus. Its
                        // cards then take the whole height. They slide up from the screen's edge,
                        // placed below it while hidden, where only Down finds them.
                        if (alertsRowFeed?.rowAlerts?.isNotEmpty() == true) Column(
                            Modifier
                                .onFocusChanged { if (!bottomRow) bottomRevealed = it.hasFocus }
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                    layout(placeable.width, (placeable.height * bottomFraction).roundToInt()) { placeable.place(0, 0) }
                                },
                        ) {
                            // The tiles' grow room counts towards the margins above and below the row
                            Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                            Column {
                                    if (alertsRowFeed != null && alertsRowFeed.rowAlerts.isNotEmpty()) {
                                        FrigateAlertsRow(
                                            feed = alertsRowFeed,
                                            shown = true,
                                            firstCardFocus = alertsRowFocus,
                                            upFocus = ::aboveRows,
                                            downFocus = FocusRequester.Default,
                                            onEnter = {},
                                            onLeave = { runCatching { haSensorsFocus.requestFocus() } },
                                        )
                                    }

                            }
                            Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                        }
                    }
                    frigateFeed?.let { FrigateClipPlayer(it, minimizeVideoStreams) }
                    fullScreenCamera?.let {
                        FullScreenCamera(
                            it,
                            minimizeVideoStreams,
                            onCameraButton = { index -> cameras.getOrNull(index)?.let { picked -> fullScreenCamera = picked } },
                            onChannel = { step -> steppedCamera(cameras, it.id, step)?.let { picked -> fullScreenCamera = picked } },
                            onClose = { fullScreenCamera = null },
                        )
                    }
                    if (camerasOpen) {
                        CamerasDialog(
                            cameras = cameras,
                            onChange = ::saveCameras,
                            playSubstreams = cameraGridLive,
                            showHomeCamera = showHomeCamera,
                            onShowHomeCameraChange = {
                                showHomeCamera = it
                                prefs.showHomeCamera = it
                            },
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
                    if (haDialogOpen) {
                        HomeAssistantDialog(
                            initialServer = haServer,
                            initialUsername = remember { prefs.haUsername },
                            onEditDashboard = ::openDashboardEditor,
                            initialTopBar = haTopBar,
                            loginRejected = haConnection.status == HaStatus.TokenRejected,
                            onSave = { server, username, topBar ->
                                // A replaced username-and-password login is ended in Home Assistant
                                if (haServer.refreshToken != server.refreshToken) revokeHaLogin(haServer)
                                haServer = server
                                prefs.haUrl = server.url
                                prefs.haToken = server.token
                                prefs.haRefreshToken = server.refreshToken
                                prefs.haUsername = username
                                saveHaChoice(haEntities, topBar)
                            },
                            onLogOut = ::logOutOfHa,
                            onDiscardLogin = ::revokeHaLogin,
                            onDismiss = { haDialogOpen = false },
                        )
                    }
                    if (dashboardEditorEnabled) {
                        DashboardEditor(
                            haServer = haServer,
                            layouts = { dashboardLayouts.copy(home = homeLayout) },
                            installedApps = all.orEmpty(),
                            canRestore = { prefs.previousDashboardLayouts != null },
                            legacyDisplay = haDisplay,
                            legacyEntities = haEntities,
                            cameraVisible = camera != null,
                            save = ::saveDashboardLayouts,
                            restore = { prefs.previousDashboardLayouts?.let(::saveDashboardLayouts) },
                            showInfo = dashboardEditorInfoOpen,
                            onDismissInfo = { dashboardEditorInfoOpen = false },
                            onStartFailed = { message ->
                                dashboardEditorEnabled = false
                                dashboardEditorInfoOpen = false
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                    if (frigateOpen) {
                        FrigateDialog(
                            initial = frigate,
                            onSave = ::saveFrigate,
                            onDismiss = { frigateOpen = false },
                        )
                    }
                    if (allAppsOpen) {
                        AllAppsDrawer(all.orEmpty(), onDismiss = { allAppsOpen = false })
                    }
                    if (launcherSettingsOpen) {
                        LauncherSettingsDialog(
                            onEditDashboard = {
                                launcherSettingsOpen = false
                                openDashboardEditor()
                            },
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
                            artworkSettings = artworkSettings,
                            artworkToken = artworkToken,
                            onArtworkEnabled = { enabled ->
                                artworkSettings = artworkSettings.copy(enabled = enabled)
                                prefs.artworkSettings = artworkSettings
                            },
                            onSaveArtworkCatalog = { region, token ->
                                if (!prefs.saveArtworkToken(token)) {
                                    false
                                } else {
                                    artworkSettings = artworkSettings.copy(region = region)
                                    prefs.artworkSettings = artworkSettings
                                    artworkToken = token
                                    true
                                }
                            },
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
                                sensors = haEntities.filterNot(::isHaAction).map { NamedEntity(it, haConnection.entities[it]?.name ?: it) },
                                actions = (haEntities.filter(::isHaAction) + haTopBar).distinct().map { NamedEntity(it, haConnection.entities[it]?.name ?: it) },
                                order = haEntities,
                                topBar = haTopBar,
                                dashboards = dashboardLayouts.copy(home = homeLayout),
                                frigate = frigate.takeIf { it.isSet },
                            ),
                            onDismiss = { exportOpen = false },
                        )
                    }
                    if (importOpen) {
                        ImportDialog(onImport = { applyImport(it, homeLayout) }, onDismiss = { importOpen = false })
                    }
                    if (networkStatusOpen) {
                        NetworkStatusDialog(
                            frigate = frigateFeed,
                            // It only connects with something to show
                            homeAssistant = haConnection.takeIf { haServer.isSet && (followed.isNotEmpty() || legacyBoards.isNotEmpty()) },
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
    private fun applyImport(file: SettingsFile, currentHome: DashboardLayout) {
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
        // Files from earlier versions: the sensors, then the actions, the first ones in the top bar
        val fileActions = file.actions.map { it.id }
        val imported = file.order ?: (file.sensors.map { it.id } + fileActions)
        val entities = (haEntities + imported).distinct()
        saveHaChoice(entities, (haTopBar + (file.topBar ?: fileActions.take(MAX_HA_ACTIONS))).distinct().filter(::isHaAction).take(MAX_HA_ACTIONS))
        val incoming = file.dashboards
        val importedHome = incoming?.home
        val importedAssistant = incoming?.assistant
        if (incoming != null || file.appTiles.isNotEmpty()) {
            val importedApps = file.appTiles.map { it.id }.distinct()
            val home = when {
                importedHome != null -> importedHome
                importedApps.isNotEmpty() -> currentHome.replaceApps(importedApps)
                else -> currentHome
            }
            val assistant = importedAssistant ?: dashboardLayouts.assistant
            saveDashboardLayouts(DashboardLayouts(
                if (importedHome?.hasHomeFocus == true) assistant?.withoutHomeFocus() else assistant,
                if (importedAssistant?.hasHomeFocus == true) home.withoutHomeFocus() else home,
            ))
        }
        file.frigate?.let(::saveFrigate)
        Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
    }

    private fun saveDashboardLayouts(value: DashboardLayouts) {
        prefs.saveDashboardLayouts(value, dashboardLayouts)
        dashboardLayouts = value
    }

    private fun saveHaChoice(entities: List<String>, topBar: List<String>) {
        haEntities = entities
        haTopBar = topBar
        prefs.saveHaChoice(entities, topBar)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_FOCUS_HAL_HOME) homeFocusRequest++
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // The home override may have switched itself off meanwhile
        updateReplaceHome()
        window.decorView.postDelayed(confirmStarted, 2_000)
        // Refresh in the background (apps may have been installed or removed meanwhile);
        // the Apps card keeps its current tiles until the new list is ready
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadInstalledApps(this@MainActivity) }
            if (apps != installed) prefs.installedApps = apps
            installed = apps
        }
        refreshWatchNext()
    }

    private fun refreshWatchNext() {
        watchNextRefresh?.cancel()
        if (!resumed) return
        watchNextRefresh = if (canReadWatchNext()) {
            lifecycleScope.launch {
                watchNext = withContext(Dispatchers.IO) { loadWatchNext(this@MainActivity) }
            }
        } else {
            watchNext = emptyList()
            null
        }
    }

    override fun onPause() {
        resumed = false
        watchNextRefresh?.cancel()
        super.onPause()
        window.decorView.removeCallbacks(confirmStarted)
    }

    private fun openDashboardEditor() {
        dashboardEditorInfoOpen = true
        dashboardEditorEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
    }

    companion object {
        /** Started properly and still alive; a crash takes the whole process, so this resets */
        @Volatile
        var running = false
            private set
    }
}
