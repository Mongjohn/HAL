package com.evofactory.hal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.tv.material3.Text
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val TAG = "WatchNext"
private const val MAX_CARDS_PER_APP = 30
private const val MAX_POSTER_BYTES = 8 * 1024 * 1024
private const val POSTER_WIDTH = 480

/** An app's program in Android TV's Watch Next feed. */
data class WatchNextCard(
    val title: String,
    val packageName: String,
    val episode: String?,
    val posterUri: String?,
    val intentUri: String,
    val browsable: Boolean,
    val engagedMillis: Long,
    val programType: Int? = null,
    val availability: Int? = null,
) {
    val key: String get() = intentUri
    // Match the key used by the old Watch Next row, including devices that reported seconds.
    val hideKey: String get() = "$packageName|$title|${episode.orEmpty()}|$engagedMillis"
}

fun watchNextByApp(rows: List<WatchNextCard>, hidden: List<String>): Map<String, List<WatchNextCard>> {
    val hiddenKeys = hidden.toSet()
    return rows.filter { it.browsable && it.intentUri.isNotBlank() && it.hideKey !in hiddenKeys }
        .groupBy { it.packageName }
        .mapValues { (_, cards) -> cards.sortedByDescending { it.engagedMillis }.distinctBy { it.key }.take(MAX_CARDS_PER_APP) }
}

fun Context.canReadWatchNext() = checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

/**
 * The Watch Next table is read with the TV listings permission. Sort and filter locally because
 * Android reserves SQL selections for callers with privileged TV provider access.
 */
fun loadWatchNext(context: Context): List<WatchNextCard> {
    return try {
        readWatchNext(context)
    } catch (e: RuntimeException) {
        AppLog.w(TAG, "Couldn't read Watch Next", e)
        emptyList()
    }
}

private fun readWatchNext(context: Context): List<WatchNextCard> = buildList {
    context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
        fun text(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        fun number(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        while (cursor.moveToNext()) {
            val title = text(TvContract.WatchNextPrograms.COLUMN_TITLE)?.takeIf { it.isNotBlank() } ?: continue
            val intent = text(TvContract.WatchNextPrograms.COLUMN_INTENT_URI)?.takeIf { it.isNotBlank() } ?: continue
            val app = text(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME) ?: continue
            val season = text(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
            val episode = text(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
            val engaged = number(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) ?: 0
            add(WatchNextCard(
                title = title,
                packageName = app,
                episode = if (season != null && episode != null) "S$season E$episode" else null,
                posterUri = text(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                    ?: text(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                intentUri = intent,
                browsable = number(TvContract.WatchNextPrograms.COLUMN_BROWSABLE) != 0L,
                engagedMillis = if (engaged in 1 until 100_000_000_000L) engaged * 1000 else engaged,
                programType = number(TvContract.WatchNextPrograms.COLUMN_TYPE)?.toInt(),
                availability = number(TvContract.WatchNextPrograms.COLUMN_AVAILABILITY)?.toInt(),
            ))
        }
    }
}

/** Resumes in the app that published it; if that fails, at least opens the app. */
internal fun readLimited(stream: InputStream, maxBytes: Int = MAX_POSTER_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > maxBytes) return null
    }
}

/**
 * Shows the dock in [first] and expands below it for [second] while [revealed].
 * The alerts in [above] slide into the dock's space while [aboveRevealed].
 */
