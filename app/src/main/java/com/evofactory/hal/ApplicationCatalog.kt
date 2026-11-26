package com.evofactory.hal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.tv.TvContract
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process catalog. Package broadcasts only mark dirty while hidden; all queries require a lease. */
internal class ApplicationCatalog private constructor(private val context: Context) {
    private val prefs = LauncherPrefs(context)
    var apps by mutableStateOf(prefs.installedApps)
        private set
    var appsReady by mutableStateOf(apps.isNotEmpty())
        private set
    var watchNext by mutableStateOf(emptyList<WatchNextCard>())
        private set
    var watchNextReady by mutableStateOf(false)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = mutableMapOf<Any, Pair<Boolean, Boolean>>()
    private var dirty = true
    private var appJob: Job? = null
    private var watchJob: Job? = null
    private var observer: ContentObserver? = null
    private val versions = apps.associate { it.key to it.imageVersion }.toMutableMap()
    private var eventsReliable = true

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart
                if (pkg != null) {
                    versions[pkg] = (versions[pkg] ?: 0) + 1
                    invalidateTileImages(pkg)
                } else {
                    apps.forEach { versions[it.key] = (versions[it.key] ?: 0) + 1 }
                    invalidateTileImages(null)
                }
                dirty = true
                refreshApps()
            }
        }
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Registered for this application process, no Activity retention and no hidden querying.
        try {
            ContextCompat.registerReceiver(context, receiver, packages, ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
            }, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: RuntimeException) {
            eventsReliable = false
            AppLog.w("Apps", "Package observation unavailable; reconciling on return", e)
        }
    }

    fun acquire(owner: Any, installed: Boolean, recommendations: Boolean) {
        owners[owner] = installed to (recommendations && context.canReadWatchNext())
        refreshApps()
        reconcileWatchNext()
    }

    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.values.none { it.first }) {
            appJob?.cancel(); appJob = null
            if (!eventsReliable) dirty = true
        }
        reconcileWatchNext()
    }

    private fun refreshApps() {
        if (!dirty || appJob?.isActive == true || owners.values.none { it.first }) return
        appJob = scope.launch {
            try {
                // If a package event arrives during the query, perform a fresh pass before publishing clean.
                do {
                    dirty = false
                    val result = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                    apps = result.map { it.copy(imageVersion = versions[it.key] ?: 0) }
                    appsReady = true
                    if (prefs.installedApps != apps) prefs.installedApps = apps
                } while (dirty)
            } catch (e: kotlinx.coroutines.CancellationException) { dirty = true; throw e }
            catch (e: RuntimeException) { dirty = true; AppLog.w("Apps", "Could not refresh installed apps", e) }
        }
    }

    private fun reconcileWatchNext() {
        val wanted = owners.values.any { it.second }
        if (wanted && observer == null) {
            val next = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { refreshWatchNext() }
            }
            try {
                context.contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, next)
                observer = next
            } catch (e: RuntimeException) { AppLog.w("WatchNext", "Provider observation unavailable", e) }
            refreshWatchNext()
        } else if (!wanted) {
            observer?.let { context.contentResolver.unregisterContentObserver(it) }
            observer = null
            watchJob?.cancel(); watchJob = null
        }
    }

    private fun refreshWatchNext() {
        watchJob?.cancel()
        watchJob = scope.launch {
            watchNext = withContext(Dispatchers.IO) { loadWatchNext(context) }
            watchNextReady = true
        }
    }

    companion object {
        private var instance: ApplicationCatalog? = null
        fun get(context: Context): ApplicationCatalog = instance ?: ApplicationCatalog(context.applicationContext).also { instance = it }
    }
}

@Composable
internal fun rememberApplicationCatalog(artwork: ArtworkSettings, installed: Boolean, active: Boolean = true): ApplicationCatalog {
    val context = LocalContext.current
    val catalog = remember { ApplicationCatalog.get(context) }
    val owner = remember { Any() }
    val recommendations = artwork.enabled && !artwork.useLocal && context.canReadWatchNext()
    VisibleEffect(catalog, installed, recommendations, active) {
        if (active) {
            withFrameNanos { }
            catalog.acquire(owner, installed, recommendations)
            try { awaitCancellation() } finally { catalog.release(owner) }
        }
    }
    return catalog
}
