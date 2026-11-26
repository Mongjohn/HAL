package com.evofactory.hal

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@OptIn(UnstableApi::class)
internal class BackgroundVideoController(private val context: Context) {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    private val frames = VideoFrameState()
    val hasFrame get() = frames.displayed
    val itemHasFrame get() = frames.currentItemReady
    var current by mutableStateOf<BackgroundVideo?>(null)
        private set
    var epoch by mutableIntStateOf(0)
        private set
    var itemGeneration by mutableIntStateOf(0)
        private set
    var retryNeeded by mutableStateOf(false)
        private set
    var retryGeneration by mutableIntStateOf(0)
        private set
    private var settings = VideoBackgroundSettings()
    private var target: PlayerView? = null
    private var watch: PlayerWatch? = null
    private var pendingRandom = false

    fun start(next: VideoBackgroundSettings) {
        release()
        settings = next
        retryNeeded = false
        if (next.videos.isEmpty()) return
        val created = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(videoHttp)))
            .build()
        player = created
        watch = PlayerWatch(created, "Background video")
        created.volume = 0f
        created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        created.repeatMode = Player.REPEAT_MODE_ALL
        created.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== created) return
                current = settings.videos.firstOrNull { it.id == mediaItem?.mediaId }
                pendingRandom = current?.randomStart == true
                frames.nextItem()
                epoch++
                itemGeneration++
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (this@BackgroundVideoController.player !== created) return
                if (pendingRandom && player.playbackState == Player.STATE_READY) {
                    pendingRandom = false
                    val position = backgroundVideoStart(player.duration, player.isCurrentMediaItemSeekable, true, Random.nextDouble())
                    if (position > 0) player.seekTo(position)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (player !== created) return
                if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) failCurrent()
            }

            override fun onRenderedFirstFrame() {
                if (player === created) frames.rendered()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== created) return
                // Signed URLs may carry credentials; never log the URL or exception payload.
                AppLog.w("BackgroundVideo", "Video playback failed: ${error.errorCodeName}")
                failCurrent()
            }
        })
        created.setMediaItems(next.videos.map { MediaItem.Builder().setMediaId(it.id).setUri(it.url).build() })
        created.shuffleModeEnabled = next.shuffle
        if (next.shuffle && next.videos.size > 1) created.seekToDefaultPosition(Random.nextInt(next.videos.size))
        created.prepare()
        created.playWhenReady = true
    }

    fun failCurrent() {
        val active = player ?: return
        if (retryNeeded) return
        frames.reset()
        if (active.mediaItemCount > 1) {
            active.removeMediaItem(active.currentMediaItemIndex.coerceAtLeast(0))
            active.prepare()
            active.play()
        } else {
            release()
            retryNeeded = true
            retryGeneration++
        }
    }

    fun retry() { if (retryNeeded) start(settings) }

    fun advance() {
        val active = player ?: return
        if (active.mediaItemCount > 1) active.seekToNextMediaItem()
        else {
            pendingRandom = current?.randomStart == true
            active.seekTo(0)
        }
    }

    fun bind(view: PlayerView) {
        if (target === view) return
        val active = player ?: return
        frames.reset()
        epoch++
        PlayerView.switchTargetView(active, target, view)
        target = view
    }

    fun unbind(view: PlayerView) {
        if (target !== view) return
        player?.let { PlayerView.switchTargetView(it, target, null) }
        target = null
    }

    fun release() {
        target?.player = null
        target = null
        frames.reset()
        current = null
        player = null
        watch?.release()
        watch = null
        retryNeeded = false
    }
}

private val videoHttp by lazy {
    OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
}

/** A short paused handoff retains the decoder; a hidden HAL releases it within 750 ms. */
private object SharedBackgroundVideo {
    private var controller: BackgroundVideoController? = null
    private val ownership = BackgroundVideoOwnership()
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { controller?.release(); ownership.released() }

    fun controller(context: Context): BackgroundVideoController = controller
        ?: BackgroundVideoController(context.applicationContext).also { controller = it }

    fun acquire(token: Any, settings: VideoBackgroundSettings) {
        handler.removeCallbacks(release)
        val video = controller ?: return
        if (ownership.acquire(token, settings, video.player != null || video.retryNeeded)) {
            video.start(settings)
        } else video.player?.play()
    }

    fun relinquish(token: Any) {
        if (!ownership.relinquish(token)) return
        controller?.player?.pause()
        handler.postDelayed(release, 750)
    }
}

@Composable
internal fun rememberBackgroundVideo(settings: VideoBackgroundSettings, screensaver: Boolean, active: Boolean): BackgroundVideoController {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { SharedBackgroundVideo.controller(context) }
    val owner = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Revision/target-only changes do not change the playlist or its playback position.
    val playback = remember(settings.videos, settings.playlists, settings.shuffle, screensaver) {
        VideoBackgroundSettings(videos = settings.videosFor(screensaver).map { it.copy(background = false, screensaver = false) }, shuffle = settings.shuffle)
    }
    DisposableEffect(playback, active, lifecycle) {
        fun update() {
            if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) SharedBackgroundVideo.acquire(owner, playback)
            else SharedBackgroundVideo.relinquish(owner)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); SharedBackgroundVideo.relinquish(owner) }
    }
    VisibleEffect(controller.retryGeneration, playback, active, minimumState = Lifecycle.State.RESUMED) {
        if (active && controller.retryNeeded) { delay(60_000); controller.retry() }
    }
    VisibleEffect(controller.player, controller.epoch, controller.itemHasFrame, active, minimumState = Lifecycle.State.RESUMED) {
        if (controller.player != null && active && !controller.itemHasFrame) {
            delay(20_000)
            if (!controller.itemHasFrame) controller.failCurrent()
        }
    }
    VisibleEffect(controller.player, controller.itemGeneration, controller.itemHasFrame, settings.switchSeconds, active, minimumState = Lifecycle.State.RESUMED) {
        // Do not rotate away from an opening source before its first-frame watchdog can run.
        if (active && controller.player != null && controller.itemHasFrame && settings.switchSeconds > 0) {
            while (true) { delay(settings.switchSeconds * 1_000L); controller.advance() }
        }
    }
    return controller
}

@OptIn(UnstableApi::class)
@Composable
internal fun VideoBackground(controller: BackgroundVideoController, dimPercent: Int = 66, screensaver: Boolean = false) {
    controller.player ?: return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (controller.hasFrame) 1f else 0f }) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.background_video, null) as PlayerView).apply {
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    useController = false
                    keepScreenOn = false
                }
            },
            update = { controller.bind(it) },
            onRelease = { controller.unbind(it) },
            modifier = Modifier.fillMaxSize(),
        )
        ArtworkShadeOverlay(dimPercent, screensaver)
    }
}
