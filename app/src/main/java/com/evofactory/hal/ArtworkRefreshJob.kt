package com.evofactory.hal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Dns
import java.util.concurrent.TimeUnit

private const val DAILY_ARTWORK_JOB = 112
private const val IMMEDIATE_ARTWORK_JOB = 113
private val refreshLock = Mutex()
private val catalogChanges = MutableStateFlow(0L)
internal val artworkCatalogChanges = catalogChanges.asStateFlow()

internal fun scheduleArtwork(context: Context, settings: ArtworkSettings, settingsChanged: Boolean = false) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (!settings.enabled || settings.useLocal || settings.region.isEmpty() || settings.services.isEmpty()) {
        scheduler.cancel(DAILY_ARTWORK_JOB)
        scheduler.cancel(IMMEDIATE_ARTWORK_JOB)
        return
    }
    val component = ComponentName(context, ArtworkRefreshJob::class.java)
    fun job(id: Int) = JobInfo.Builder(id, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

    if (scheduler.getPendingJob(DAILY_ARTWORK_JOB) == null) {
        scheduler.schedule(job(DAILY_ARTWORK_JOB).setPeriodic(CATALOG_INTERVAL).setPersisted(true).build())
    }
    if (settingsChanged || scheduler.getPendingJob(IMMEDIATE_ARTWORK_JOB) == null) {
        scheduler.schedule(
            job(IMMEDIATE_ARTWORK_JOB).setBackoffCriteria(TimeUnit.MINUTES.toMillis(15), JobInfo.BACKOFF_POLICY_EXPONENTIAL).build(),
        )
    }
}

class ArtworkRefreshJob : JobService() {
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val network = params.network ?: return false
        val client = catalogHttpClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(Dns { network.getAllByName(it).toList() })
            .build()
        running[params.jobId] = CoroutineScope(Dispatchers.Main).launch {
            val success = refreshLock.withLock {
                val settings = LauncherPrefs(this@ArtworkRefreshJob).artworkSettings
                if (!settings.enabled || settings.useLocal || settings.region.isEmpty() || settings.services.isEmpty()) {
                    return@withLock true
                }
                val catalog = ArtworkCatalog(this@ArtworkRefreshJob, client)
                val cached = catalog.cached()
                val age = cached?.let { System.currentTimeMillis() - it.checkedAt }
                val fresh = params.jobId == IMMEDIATE_ARTWORK_JOB && cached?.matches(settings) == true &&
                    cached.hasLabels && age != null && age in 0 until CATALOG_INTERVAL
                if (fresh) return@withLock true
                catalog.refresh(settings).also { if (it) catalogChanges.value++ }
            }
            running.remove(params.jobId)
            jobFinished(params, !success && params.jobId == IMMEDIATE_ARTWORK_JOB)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        running.values.forEach { it.cancel() }
        running.clear()
        super.onDestroy()
    }
}
