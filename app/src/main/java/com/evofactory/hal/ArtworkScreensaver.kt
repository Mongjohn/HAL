package com.evofactory.hal

import android.content.Context
import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Android owns activation, wake and sleep; this service owns only its visible presentation. */
class ArtworkScreensaver : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private var content: ComposeView? = null
    private var dreaming by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        // HAL supplies its own image/video shade. Android still owns the sleep deadline.
        isScreenBright = true
        instance = this
        showing = true
        content = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ArtworkScreensaver)
            setViewTreeSavedStateRegistryOwner(this@ArtworkScreensaver)
            setContent { if (dreaming) SystemArtworkScreensaver(this@ArtworkScreensaver) }
        }
        setContentView(content!!)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        registry.currentState = Lifecycle.State.RESUMED
        scheduleArtwork(this, LauncherPrefs(this).artworkSettings)
    }

    private fun stopPresentation() {
        dreaming = false
        if (instance === this) { instance = null; showing = false }
        registry.currentState = Lifecycle.State.CREATED
        if (!HalForegroundWork.visible) stopArtworkJobs(this)
    }

    override fun onDreamingStopped() {
        stopPresentation()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        stopPresentation()
        content?.disposeComposition()
        content = null
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        stopPresentation()
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object {
        internal var showing by mutableStateOf(false)
            private set
        private var instance: ArtworkScreensaver? = null
        private var homeHeld = false

        /** Accessibility sees Home before the dream. Wake without launching HAL underneath it. */
        internal fun consumeHome(event: KeyEvent): Boolean {
            // Recover if Android dropped a previous release; a fresh Home press must still work.
            if (instance == null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) homeHeld = false
            if (instance == null && !homeHeld) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                homeHeld = true
                instance?.wakeUp()
            }
            if (event.action == KeyEvent.ACTION_UP) homeHeld = false
            return true
        }
    }
}

@Composable
private fun SystemArtworkScreensaver(context: Context) {
    val prefs = remember { LauncherPrefs(context) }
    // Preferences also change through an already enabled web editor while this dream is visible.
    val settings = rememberPreferenceSection(prefs, setOf("video_backgrounds")) { prefs.videoBackgrounds }
    val artwork = rememberPreferenceSection(prefs, artworkPreferenceKeys) { prefs.artworkSettings }
    val layout = rememberPreferenceSection(prefs, setOf("dashboard_layouts")) { prefs.dashboardLayouts.home }
    val display = rememberPreferenceSection(prefs, haDisplayPreferenceKeys) { prefs.haDisplay }
    val selected = rememberPreferenceSection(prefs, haSelectionPreferenceKeys) { prefs.haEntities }
    val server = rememberPreferenceSection(prefs, setOf("ha_url", "ha_token", "ha_refresh_token")) { HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken) }
    val template = display.homeDashboard.takeIf { layout == null }
    val topBar = rememberPreferenceSection(prefs, haSelectionPreferenceKeys) { prefs.haTopBar }
    val followed = remember(layout, selected, topBar) { (topBar + (layout?.entityIds ?: selected)).distinct() }
    val connection = rememberHaConnection(server, followed, listOfNotNull(template), includeScreensaverWeather = true,
        forecastDemand = forecastDemand(settings.screensaverForecast))
    val board = connection.dashboard(template)
    val view = rememberPreferenceSection(prefs, setOf("screensaver_home_view")) { prefs.screensaverHomeView }
    val players = screensaverViewPlayers(layout, board, display.homeLayout,
        selected.takeIf { template == null }.orEmpty(), view, connection.entities)
    val weather = screensaverWeather(followed, connection.entities).takeIf { connection.status == HaStatus.Connected }
    val apps = rememberPreferenceSection(prefs, appPreferenceKeys) { arrange(prefs.installedApps, prefs.order, prefs.hidden).associate { it.key to it.label } }
    val catalog = rememberApplicationCatalog(artwork, installed = false)
    val watchNext = catalog.watchNext
    val watchNextReady = !artwork.enabled || artwork.useLocal || !context.canReadWatchNext() || catalog.watchNextReady
    LaunchedEffect(artwork) { scheduleArtwork(context, artwork) }
    val hiddenWatchNext = rememberPreferenceSection(prefs, setOf("hidden_watching")) { prefs.hiddenWatchNext }
    val native = remember(watchNext, hiddenWatchNext) { watchNextByApp(watchNext, hiddenWatchNext) }
    val video = rememberBackgroundVideo(settings, true, true)
    var caption by remember { mutableStateOf<ArtworkLabel?>(null) }
    var frame by remember(artwork) { mutableStateOf<ArtworkFrame?>(SharedArtworkRotation.forSettings(artwork).frame) }
    val theme = rememberPreferenceSection(prefs, setOf("appearance_theme")) { prefs.appearanceTheme }
    val cycling = rememberPreferenceSection(prefs, setOf("cycle_ui_colors")) { prefs.cycleUiColors }
    val opacity = rememberPreferenceSection(prefs, setOf("dashboard_card_opacity")) { prefs.dashboardCardOpacity }
    HalTheme(opacity ?: DefaultCardOpacity, rememberUiColorCycle(cycling, true), theme = theme,
        focusedCardOpacity = settings.focusedCardOpacity, unfocusedCardOpacity = settings.unfocusedCardOpacity,
        dashboardForecast = settings.dashboardForecast, screensaverForecast = settings.screensaverForecast) {
        Box(Modifier.fillMaxSize()) {
            DashboardArtwork(artwork, apps, native, active = true, onArtworkChanged = { caption = it },
                onFrameChanged = { frame = it }, draw = { false }, inputsReady = watchNextReady)
            ArtworkScreensaverContent(frame.takeIf { artwork.enabled }, weather, players, connection, video, settings.screensaverDim, caption.takeIf { artwork.enabled })
        }
    }
}
