package com.evofactory.hal

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val storage = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        storage.registerOnSharedPreferenceChangeListener(listener)
        onDispose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Preferences also change through an already enabled web editor while this dream is visible.
    val settings = remember(revision) { prefs.videoBackgrounds }
    val artwork = remember(revision) { prefs.artworkSettings }
    val layout = remember(revision) { prefs.dashboardLayouts.home }
    val display = remember(revision) { prefs.haDisplay }
    val selected = remember(revision) { prefs.haEntities }
    val server = remember(revision) { HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken) }
    val template = display.homeDashboard.takeIf { layout == null }
    val topBar = remember(revision) { prefs.haTopBar }
    val followed = remember(layout, selected, topBar) { (topBar + (layout?.entityIds ?: selected)).distinct() }
    val connection = rememberHaConnection(server, followed, listOfNotNull(template), includeScreensaverWeather = true)
    val board = connection.dashboard(template)
    val view = remember(revision) { prefs.screensaverHomeView }
    val players = screensaverViewPlayers(layout, board, display.homeLayout,
        selected.takeIf { template == null }.orEmpty(), view, connection.entities)
    val weather = screensaverWeather(followed, connection.entities).takeIf { connection.status == HaStatus.Connected }
    val apps = remember(revision) { arrange(prefs.installedApps, prefs.order, prefs.hidden).associate { it.key to it.label } }
    var watchNext by remember { mutableStateOf(emptyList<WatchNextCard>()) }
    var watchNextReady by remember(artwork) {
        mutableStateOf(!artwork.enabled || artwork.useLocal || !context.canReadWatchNext())
    }
    var feedRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(artwork.enabled, artwork.useLocal) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { feedRevision++ }
        }
        val registered = artwork.enabled && !artwork.useLocal && context.canReadWatchNext() && runCatching {
            context.contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer)
            true
        }.getOrDefault(false)
        onDispose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(artwork, feedRevision) {
        watchNext = if (artwork.enabled && !artwork.useLocal && context.canReadWatchNext())
            withContext(Dispatchers.IO) { loadWatchNext(context) } else emptyList()
        watchNextReady = true
        scheduleArtwork(context, artwork)
    }
    val native = remember(watchNext, revision) { watchNextByApp(watchNext, prefs.hiddenWatchNext) }
    val video = rememberBackgroundVideo(settings, true, true)
    var frame by remember(artwork) { mutableStateOf<ArtworkFrame?>(SharedArtworkRotation.forSettings(artwork).frame) }
    val theme = remember(revision) { prefs.appearanceTheme }
    val cycling = remember(revision) { prefs.cycleUiColors }
    HalTheme(prefs.dashboardCardOpacity ?: DefaultCardOpacity, rememberUiColorCycle(cycling, true), theme = theme) {
        Box(Modifier.fillMaxSize()) {
            DashboardArtwork(artwork, apps, native, active = true, onArtworkChanged = {},
                onFrameChanged = { frame = it }, draw = { false }, inputsReady = watchNextReady)
            ArtworkScreensaverContent(frame.takeIf { artwork.enabled }, weather, players, connection, video, settings.screensaverDim)
        }
    }
}
