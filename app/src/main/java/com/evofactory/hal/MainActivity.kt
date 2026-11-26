package com.evofactory.hal

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Surface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private var cameras by mutableStateOf(emptyList<Camera>())
    private var cameraGridLive by mutableStateOf(true)
    private var showHomeCamera by mutableStateOf(true)
    private var minimizeVideoStreams by mutableStateOf(false)
    private var webCameraTest by mutableStateOf<String?>(null)
    private var selectedCamera by mutableStateOf("")
    private var homeFocusRequest by mutableIntStateOf(0)
    private var tab by mutableStateOf(HomeTab.Home)
    private var dashboardEditorEnabled by mutableStateOf(false)
    private var dashboardEditorInfoOpen by mutableStateOf(false)
    private var dashboardLayouts by mutableStateOf(DashboardLayouts())
    private var haServer by mutableStateOf(HaServer(""))
    // Everything on the Assistant tab in its order, and the actions also in the top bar
    private var haEntities by mutableStateOf(emptyList<String>())
    private var haTopBar by mutableStateOf(emptyList<String>())
    // The Home Assistant dashboard the Assistant tab shows instead, if any
    private var haDisplay by mutableStateOf(HaDisplay())
    private var frigate by mutableStateOf(FrigateServer())
    private var stuckPlayersOpen by mutableStateOf(false)
    private var replaceHome by mutableStateOf(false)
    private var showFreeMemory by mutableStateOf(false)
    private var artworkSettings by mutableStateOf(ArtworkSettings())
    private var videoBackgrounds by mutableStateOf(VideoBackgroundSettings())
    private var cardOpacityOverride by mutableStateOf<Int?>(null)
    private var appearanceTheme by mutableStateOf(LauncherTheme.Glass)
    private var appsCardCollapse by mutableStateOf(AppsCardCollapse.Off)
    private var cycleUiColors by mutableStateOf(false)
    private var resumed by mutableStateOf(false)

    // Home override's crash guard: the launcher counts as started once it has stayed up a moment
    private val confirmStarted = Runnable {
        running = true
        if (prefs.overrideLaunches != 0) prefs.overrideLaunches = 0
    }

    @android.annotation.SuppressLint("RestrictedApi") // Android window dispatch override must delegate unconsumed keys to ComponentActivity.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        android.os.Trace.beginSection("HAL.key.dispatch")
        try {
            if (appsCardKeyHandler?.invoke(event) == true) return true
            return super.dispatchKeyEvent(event)
        } finally { android.os.Trace.endSection() }
    }

    /** Explicit activity intent, also usable with adb shell am start. Does not persist across launches. */
    private fun handleEditorIntent(intent: Intent) {
        if (intent.action == "com.evofactory.hal.ENABLE_WEB_EDITOR") {
            dashboardEditorEnabled = true
            dashboardEditorInfoOpen = true
            intent.action = Intent.ACTION_MAIN
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_FOCUS_HAL_HOME) homeFocusRequest++
        handleEditorIntent(intent)
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
        videoBackgrounds = prefs.videoBackgrounds
        cardOpacityOverride = prefs.dashboardCardOpacity
        appearanceTheme = prefs.appearanceTheme
        appsCardCollapse = prefs.appsCardCollapse
        cycleUiColors = prefs.cycleUiColors
        scheduleArtwork(this, artworkSettings)
        // Android stops asking by itself after it's been refused. Answering comes back to onResume.
        if (!canReadWatchNext()) requestPermissions(arrayOf(READ_TV_LISTINGS), 0)
        setContent {
            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> reloadWebConfiguration(key) }
                prefs.addChangeListener(listener)
                onDispose { prefs.removeChangeListener(listener) }
            }
            val appCatalog = rememberApplicationCatalog(artworkSettings, installed = true, active = resumed)
            // A first-run placeholder is never reported ready before discovery has completed.
            ReportDrawnWhen { appCatalog.appsReady && installed == appCatalog.apps }
            LaunchedEffect(appCatalog.apps, appCatalog.appsReady) { if (appCatalog.appsReady) installed = appCatalog.apps }
            val watchNext = appCatalog.watchNext
            LaunchedEffect(resumed) {
                if (!resumed) return@LaunchedEffect
                withFrameNanos { }
                val settings = prefs.artworkSettings
                if (settings != artworkSettings) {
                    artworkSettings = settings
                    scheduleArtwork(this@MainActivity, settings, settingsChanged = true)
                }
            }
            LaunchedEffect(resumed, artworkSettings) {
                if (resumed) {
                    // Home's retained dashboard gets its first frame before optional catalog work.
                    withFrameNanos { }
                    scheduleArtwork(this@MainActivity, artworkSettings)
                }
            }
            val cardOpacity = cardOpacityOverride ?: DefaultCardOpacity
            HalTheme(cardOpacity, rememberUiColorCycle(cycleUiColors, resumed), theme = appearanceTheme,
                focusedCardOpacity = videoBackgrounds.focusedCardOpacity, unfocusedCardOpacity = videoBackgrounds.unfocusedCardOpacity,
                dashboardForecast = videoBackgrounds.dashboardForecast, screensaverForecast = videoBackgrounds.screensaverForecast, cardForecast = videoBackgrounds.cardForecast, popupCardOpacity = videoBackgrounds.popupCardOpacity,
                popupCardColumns = videoBackgrounds.popupCardColumns) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val all = installed
                    val visible = remember(all, order, hidden) { all?.let { arrange(it, order, hidden) } }
                    val dockKeys = remember(visible, order, showAllAppsTile) {
                        dockTileKeys(visible.orEmpty().map { it.key }, order, showAllAppsTile)
                    }
                    val camera = if (showHomeCamera) cameras.find { it.id == selectedCamera } ?: cameras.firstOrNull() else null
                    val availableTabs = remember(cameras.isNotEmpty(), frigate.isSet) {
                        availableHomeTabs(cameras.isNotEmpty(), frigate.isSet)
                    }
                    val mainCameraFocus = remember { FocusRequester() }
                    val haSensorsFocus = remember { FocusRequester() }
                    val networkFocus = remember { FocusRequester() }
                    val cogFocus = remember { FocusRequester() }
                    val secondTabFocus = remember { FocusRequester() }
                    val homeTabFocus = remember { FocusRequester() }
                    val alertsTabFocus = remember { FocusRequester() }
                    val homeNavigationFocus = if (availableTabs.size > 1) homeTabFocus else networkFocus
                    var handledHomeRequest by remember { mutableIntStateOf(0) }
                    LaunchedEffect(availableTabs, tab) {
                        if (tab !in availableTabs) {
                            tab = HomeTab.Home
                            homeNavigationFocus.requestFocus()
                        }
                    }
                    var topBarSelected by remember { mutableStateOf(false) }
                    var homeTabSelected by remember { mutableStateOf(false) }
                    val bottomRow = true
                    var bottomRevealed by remember { mutableStateOf(false) }
                    val bottomShown = bottomRow || bottomRevealed
                    val bottomFraction by animateFloatAsState(if (bottomShown) 1f else 0f, label = "bottom rows")
                    // Back returns focus to the tabs.
                    BackHandler {
                        when {
                            topBarSelected && !homeTabSelected -> runCatching { homeNavigationFocus.requestFocus() }
                            !bottomShown -> if (!topBarSelected) runCatching { homeNavigationFocus.requestFocus() }
                            else -> runCatching { homeNavigationFocus.requestFocus() }
                        }
                    }
                    // The launcher top bar has its own action selection.
                    val actions = haTopBar.take(MAX_HA_ACTIONS)
                    val followed = remember(dashboardLayouts, haEntities, haTopBar) {
                        (haTopBar + dashboardLayouts.assistant?.entityIds.orEmpty() +
                            (dashboardLayouts.home?.entityIds ?: haEntities)).distinct()
                    }
                    val legacyBoards = listOfNotNull(
                        haDisplay.dashboard.takeIf { !prefs.assistantDashboardRetired && dashboardLayouts.assistant == null },
                        haDisplay.homeDashboard.takeIf { dashboardLayouts.home == null },
                    ).distinct()
                    val haConnection = rememberHaConnection(haServer, followed, legacyBoards, includeScreensaverWeather = true,
                        forecastDemand = forecastDemand(videoBackgrounds.dashboardForecast, videoBackgrounds.cardForecast, todaySummary = true))
                    val openDashboardSetup: () -> Unit = {
                        openDashboardEditor()
                    }
                    val legacyHomeBoard = haConnection.dashboard(haDisplay.homeDashboard)
                    val savedHome = dashboardLayouts.home
                    val homeLayout = remember(savedHome, dockKeys, haDisplay, legacyHomeBoard, haEntities) {
                        (savedHome ?: seedDashboardLayout(
                            legacyHomeBoard,
                            haDisplay.homeLayout.selectionCards(haEntities, haConnection.entities),
                            1, haDisplay.homeLayout.twoPerRow,
                        )).migrateAppsCard(dockKeys)
                    }
                    val legacyAssistantBoard = haConnection.dashboard(haDisplay.dashboard)
                    LaunchedEffect(homeLayout, dashboardLayouts.assistant, legacyHomeBoard, legacyAssistantBoard) {
                        val homeReady = savedHome != null || haDisplay.homeDashboard == null || legacyHomeBoard != null ||
                            haConnection.dashboardMissing(haDisplay.homeDashboard)
                        val assistantReady = dashboardLayouts.assistant != null || haDisplay.dashboard == null ||
                            legacyAssistantBoard != null || haConnection.dashboardMissing(haDisplay.dashboard)
                        if (!homeReady || !assistantReady) return@LaunchedEffect
                        val retired = dashboardLayouts.assistant ?: if (!prefs.assistantDashboardRetired &&
                            haDisplay.dashboard != null && haDisplay.dashboard != haDisplay.homeDashboard) {
                            seedDashboardLayout(legacyAssistantBoard, haDisplay.layout.selectionCards(haEntities, haConnection.entities),
                                3, haDisplay.layout.twoPerRow)
                        } else if (!prefs.assistantDashboardRetired && haDisplay.dashboard == null && savedHome != null) {
                            val missing = haEntities.filter { it !in homeLayout.entityIds }
                            missing.takeIf { it.isNotEmpty() }?.let {
                                seedDashboardLayout(null, haDisplay.layout.selectionCards(it, haConnection.entities), 3, haDisplay.layout.twoPerRow)
                            }
                        } else null
                        if (retired != null) {
                            val source = DashboardLayouts(assistant = retired, home = dashboardLayouts.home)
                            val migrated = source.retireAssistant(homeLayout)
                            prefs.saveDashboardLayouts(migrated, dashboardLayouts)
                            dashboardLayouts = migrated
                        }
                        prefs.assistantDashboardRetired = true
                    }
                    // The browser may read settings after a write but before the next composition.
                    fun currentHomeLayout(): DashboardLayout {
                        val current = dashboardLayouts.home
                        return if (current == null || current == savedHome) homeLayout else current.migrateAppsCard(dockKeys)
                    }
                    var focusHomeSurface by remember { mutableStateOf<Int?>(null) }
                    var homeFallback by remember { mutableIntStateOf(0) }
                    LaunchedEffect(tab) {
                        if (tab != HomeTab.Home) focusHomeSurface = null
                    }
                    LaunchedEffect(homeFallback) {
                        if (homeFallback == 0) return@LaunchedEffect
                        tab = HomeTab.Home
                        withFrameNanos { }
                        runCatching { homeNavigationFocus.requestFocus() }
                    }
                    LaunchedEffect(homeFocusRequest, dashboardLayouts.assistant) {
                        val request = homeFocusRequest
                        if (request == 0 || request == handledHomeRequest) return@LaunchedEffect
                        val destination = DashboardLayouts(home = homeLayout).visibleHomeFocusSurface(haConnection.entities)
                        focusHomeSurface = null
                        tab = HomeTab.Home
                        if (destination == "home") {
                            // The card owns the entire handoff; never focus the header on the way.
                            focusHomeSurface = request
                        } else {
                            withFrameNanos { }
                            runCatching { homeNavigationFocus.requestFocus() }
                            handledHomeRequest = request
                        }
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
                            if (pressed !in availableTabs) return false
                            if (event.type == KeyEventType.KeyDown) {
                                tab = pressed
                                val tabFocus = when (pressed) {
                                    HomeTab.Home -> homeNavigationFocus
                                    HomeTab.Cameras -> secondTabFocus
                                    HomeTab.Alerts -> alertsTabFocus
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
                    val panelRemoteScope = rememberCoroutineScope()
                    val launcherWindow = LocalWindowInfo.current
                    fun onPanelRemoteButton(event: KeyEvent, close: () -> Unit): Boolean {
                        val handled = isSettingsButton(event) || colorButtonTab(event)?.let { it in availableTabs } == true ||
                            cameraButtonIndex(event) != null ||
                            (channelStep(event) != null && tab == HomeTab.Home && camera != null)
                        if (!handled) return false
                        if (event.type == KeyEventType.KeyDown) {
                            val forwarded = KeyEvent(android.view.KeyEvent(event.nativeKeyEvent))
                            close()
                            panelRemoteScope.launch {
                                // Wait for Android to return the input window, rather than racing dismissal.
                                snapshotFlow { launcherWindow.isWindowFocused }.first { it }
                                withFrameNanos { }
                                onRemoteButton(forwarded)
                            }
                        }
                        return true
                    }
                    var currentArtwork by remember { mutableStateOf<ArtworkLabel?>(null) }
                    val video = rememberBackgroundVideo(videoBackgrounds, false, backgroundVideoWanted(videoBackgrounds,
                        false, resumed && !ArtworkScreensaver.showing, minimizeVideoStreams && (fullScreenCamera != null || frigateFeed?.playing != null)))
                    if ((cycleUiColors || appearanceTheme != LauncherTheme.Glass) && !artworkSettings.enabled) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    DashboardArtwork(
                        settings = artworkSettings,
                        visibleApps = remember(visible) { visible.orEmpty().associate { it.key to it.label } },
                        watchNext = watchNextByPackage,
                        active = resumed && !ArtworkScreensaver.showing,
                        onArtworkChanged = { currentArtwork = it },
                        dimPercent = videoBackgrounds.dashboardDim,
                        draw = { !video.hasFrame },
                        inputsReady = !artworkSettings.enabled || artworkSettings.useLocal || !canReadWatchNext() || appCatalog.watchNextReady,
                    )
                    if (resumed && !ArtworkScreensaver.showing) VideoBackground(video, videoBackgrounds.dashboardDim)
                    Column(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::onRemoteButton)) {
                        HomeTopBar(
                            tab = tab,
                            availableTabs = availableTabs,
                            onTabChange = { tab = it },
                            secondTabFocus = secondTabFocus,
                            homeTabFocus = homeTabFocus,
                            alertsTabFocus = alertsTabFocus,
                            homeFocusPending = homeFocusRequest != 0 && homeFocusRequest != handledHomeRequest,
                            onHomeTabSelected = { homeTabSelected = it },
                            showFreeMemory = showFreeMemory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .cycleUiColors(androidx.compose.ui.graphics.LayerOutsets(bottom = EdgePadding))
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
                                    onClick = ::openDashboardEditor,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (haServer.isSet) HaActionButtons(haConnection, actions)
                                (if (video.hasFrame) null
                                    else currentArtwork)?.takeIf { it.title.isNotBlank() }?.let {
                                    ArtworkCaption(it, Modifier.weight(1f, fill = false))
                                }
                                NetworkButton(
                                    onClick = { networkStatusOpen = true },
                                    modifier = Modifier.focusRequester(networkFocus),
                                )
                            },
                        ) {
                            SettingsButton(
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
                                onSetUp = ::openDashboardEditor,
                                selectCamera = gridCameraDue,
                                onCameraSelected = { gridCameraDue = null },
                                onCameraFocused = { gridFocusedCamera = it },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding),
                            )
                        } else if (tab == HomeTab.Alerts) {
                            FrigateAlertsGrid(
                                feed = frigateFeed,
                                onSetUp = ::openDashboardEditor,
                                modifier = Modifier.weight(1f).fillMaxWidth().cycleUiColors().padding(top = EdgePadding),
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
                                val waitingForApps = all == null && homeLayout.pages.any { page ->
                                    page.cards.any { it.apps != null && it.focusOnHome }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(EdgePadding)) {
                                    if (camera != null) CameraBox(cameraWidth)
                                    HaAssistantTab(
                                        connection = haConnection,
                                        onPanelRemoteKey = ::onPanelRemoteButton,
                                        onScreensaverViewChanged = { index ->
                                            if (prefs.screensaverHomeView != index) prefs.screensaverHomeView = index
                                        },
                                        homeAssistantSet = haServer.isSet,
                                        entityIds = emptyList(),
                                        dashboard = null,
                                        customLayout = homeLayout,
                                        homeFocusRequest = if (waitingForApps) 0 else focusHomeSurface ?: 0,
                                        onHomeFocusHandled = { if (focusHomeSurface == it) focusHomeSurface = null; handledHomeRequest = it },
                                        onHomeFocusUnavailable = { homeFallback++ },
                                        renderApps = { card, target, homeTarget, height, neighbor ->
                                            DashboardAppsCard(
                                                card = card,
                                                installed = all.orEmpty(),
                                                entities = haConnection.entities,
                                                target = target,
                                                homeTarget = homeTarget,
                                                height = height,
                                                collapse = appsCardCollapse,
                                                singleRowViewport = appearanceTheme == LauncherTheme.Calm,
                                                active = resumed,
                                                onKeyHandlerChanged = { handler ->
                                                    appsCardKeyHandler = handler?.let { keyHandler ->
                                                        { event -> resumed && keyHandler(event) }
                                                    }
                                                },
                                                lastApp = lastApp,
                                                neighbor = neighbor,
                                                onAppFocused = { lastApp = it },
                                                onOpenAllApps = { allAppsOpen = true },
                                            )
                                        },
                                        tabFocus = homeNavigationFocus,
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
                        if (alertsRowFeed != null && alertsRowFeed.rowAlerts.isNotEmpty()) Column(
                            Modifier
                                .cycleUiColors()
                                .onFocusChanged { if (!bottomRow) bottomRevealed = it.hasFocus }
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                    layout(placeable.width, (placeable.height * bottomFraction).roundToInt()) { placeable.place(0, 0) }
                                },
                        ) {
                            // The tiles' grow room counts towards the margins above and below the row
                            Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                            FrigateAlertsRow(
                                feed = alertsRowFeed,
                                shown = true,
                                firstCardFocus = alertsRowFocus,
                                upFocus = ::aboveRows,
                                downFocus = FocusRequester.Default,
                                onEnter = {},
                                onLeave = { runCatching { haSensorsFocus.requestFocus() } },
                            )
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
                    if (dashboardEditorEnabled) {
                        DashboardEditor(
                            haServer = haServer,
                            layouts = { dashboardLayouts.copy(home = currentHomeLayout()) },
                            savedLayouts = { dashboardLayouts },
                            installedApps = all.orEmpty(),
                            previousSurface = prefs::previousDashboardSurface,
                            legacyDisplay = haDisplay,
                            legacyEntities = haEntities,
                            cameraVisible = camera != null,
                            save = { next, previous, surface, restoring, encoded ->
                                prefs.saveDashboardLayouts(next, previous, surface, restoring, encoded)
                                dashboardLayouts = next
                            },
                            exportSettings = { settingsForExport(haConnection.entities, currentHomeLayout()) },
                            importSettings = { applyImport(it, currentHomeLayout()) },
                            videoBackgrounds = { videoBackgrounds },
                            saveVideos = { next -> videoBackgrounds = next; prefs.videoBackgrounds = next },
                            configurationChanged = {}, // Key-scoped preference observation owns the saved projection.
                            tvAction = { name, id ->
                                window.decorView.postDelayed({ when (name) {
                                    "accessibility" -> openSystemSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    "screensaver" -> openScreensaverSettings()
                                    "watch-next" -> requestPermissions(arrayOf(READ_TV_LISTINGS), 0)
                                    "camera-test" -> webCameraTest = id
                                    "restart" -> restartApp()
                                } }, 750)
                            },
                            showInfo = dashboardEditorInfoOpen,
                            onDismissInfo = { dashboardEditorInfoOpen = false },
                            onStartFailed = { message ->
                                dashboardEditorEnabled = false
                                dashboardEditorInfoOpen = false
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                    cameras.firstOrNull { it.id == webCameraTest }?.let { test ->
                        CameraTestDialog(test.name, test.url, test.substreamUrl, test.useAudio, test.useSubstreamAudio,
                            onDismiss = { webCameraTest = null })
                    }
                    if (allAppsOpen) {
                        AllAppsDrawer(all.orEmpty(), onDismiss = { allAppsOpen = false })
                    }
                    if (stuckPlayersOpen) {
                        StuckPlayersDialog(onDismiss = { stuckPlayersOpen = false })
                    }
                    if (networkStatusOpen) {
                        NetworkStatusDialog(
                            frigate = frigateFeed,
                            // A configured login also follows screensaver weather without dashboard rows.
                            homeAssistant = haConnection.takeIf { haServer.isSet },
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

    private fun revokeHaLogin(server: HaServer) {
        lifecycleScope.launch { haRevoke(server) }
    }

    private fun reloadWebConfiguration(key: String?) {
        val previousArtwork = artworkSettings
        if (key == null || key in cameraPreferenceKeys) {
            cameras = prefs.cameras
            selectedCamera = prefs.selectedCamera
            cameraGridLive = prefs.cameraGridLive
            showHomeCamera = prefs.showHomeCamera
            minimizeVideoStreams = prefs.minimizeVideoStreams
        }
        if (key == null || key.startsWith("ha_")) {
            haServer = HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken)
            haTopBar = prefs.haTopBar
            haEntities = prefs.haEntities
            haDisplay = prefs.haDisplay
        }
        if (key == null || key == "dashboard_layouts") dashboardLayouts = prefs.dashboardLayouts
        if (key == null || key.startsWith("frigate_")) frigate = prefs.frigate
        if (key == null || key in artworkPreferenceKeys) artworkSettings = prefs.artworkSettings
        if (key == null || key == "video_backgrounds") videoBackgrounds = prefs.videoBackgrounds
        if (key == null || key in appearancePreferenceKeys) {
            cardOpacityOverride = prefs.dashboardCardOpacity
            appearanceTheme = prefs.appearanceTheme
            appsCardCollapse = prefs.appsCardCollapse
            cycleUiColors = prefs.cycleUiColors
        }
        if (key == null || key in launcherPreferenceKeys) {
            showFreeMemory = prefs.showFreeMemory
            updateReplaceHome()
        }
        if (key == null || key in appPreferenceKeys || key == "show_all_apps_tile") {
            order = prefs.order; hidden = prefs.hidden; showAllAppsTile = prefs.showAllAppsTile
        }
        if (previousArtwork != artworkSettings) scheduleArtwork(this, artworkSettings, settingsChanged = true)
    }

    private fun settingsForExport(entities: Map<String, HaEntity>, home: DashboardLayout) = SettingsFile(
        cameras = cameras,
        login = haServer.takeIf { it.isSet },
        username = prefs.haUsername,
        sensors = haEntities.filterNot(::isHaAction).map { NamedEntity(it, entities[it]?.name ?: it) },
        actions = (haEntities.filter(::isHaAction) + haTopBar).distinct().map { NamedEntity(it, entities[it]?.name ?: it) },
        order = haEntities, topBar = haTopBar,
        dashboards = dashboardLayouts.copy(home = home),
        frigate = frigate.takeIf { it.isSet },
    )

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
            if (haServer != server) clearMediaImages()
            haServer = server
            prefs.saveHaLogin(server, file.username)
        }
        // Files from earlier versions: the sensors, then the actions, the first ones in the top bar
        val fileActions = file.actions.map { it.id }
        val imported = file.order ?: (file.sensors.map { it.id } + fileActions)
        val entities = (haEntities + imported).distinct()
        saveHaChoice(entities, (haTopBar + (file.topBar ?: fileActions.take(MAX_HA_ACTIONS))).distinct().filter(::isHaAction).take(MAX_HA_ACTIONS))
        val incoming = file.dashboards?.let { layouts ->
            when {
                layouts.assistant == null -> layouts
                layouts.home == null -> DashboardLayouts(home = layouts.assistant.copy(appsConfigured = false))
                else -> layouts.retireAssistant(layouts.home)
            }
        }
        val importedHome = incoming?.home
        if (incoming != null || file.appTiles.isNotEmpty()) {
            val importedApps = file.appTiles.map { it.id }.distinct()
            val home = when {
                importedHome != null -> importedHome
                importedApps.isNotEmpty() -> currentHome.replaceApps(importedApps)
                else -> dashboardLayouts.home
            }
            if (incoming != null) {
                saveDashboardLayouts(DashboardLayouts(home = home))
                prefs.assistantDashboardRetired = true
            } else saveDashboardLayouts(dashboardLayouts.copy(home = home))
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

    private fun updateReplaceHome() {
        replaceHome = prefs.replaceHome
        showFreeMemory = prefs.showFreeMemory
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_FOCUS_HAL_HOME) homeFocusRequest++
        handleEditorIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        videoBackgrounds = prefs.videoBackgrounds
        // The home override may have switched itself off meanwhile
        updateReplaceHome()
        window.decorView.postDelayed(confirmStarted, 2_000)
        // The composed refresh waits for the first retained frame and cancels when HAL leaves.
    }

    override fun onStart() {
        super.onStart()
        HalForegroundWork.visible = true
    }

    override fun onStop() {
        HalForegroundWork.visible = false
        if (!ArtworkScreensaver.showing) stopArtworkJobs(this)
        super.onStop()
    }

    override fun onPause() {
        resumed = false
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
        // Home is intercepted by the accessibility helper before Activity.dispatchKeyEvent.
        internal var appsCardKeyHandler: ((android.view.KeyEvent) -> Boolean)? = null

        /** Started properly and still alive; a crash takes the whole process, so this resets */
        @Volatile
        var running = false
            private set
    }
}
