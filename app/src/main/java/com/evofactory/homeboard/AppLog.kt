package com.evofactory.homeboard

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val LOG_HOURS_DEFAULT = 12
const val LOG_HOURS_MIN = 1
const val LOG_HOURS_MAX = 72

private const val LOG_FOLDER = "logs"
private const val MS_PER_HOUR = 3_600_000L

// A loop of errors must not fill the TV: the rest of that hour is dropped
private const val LOG_CHUNK_MAX_BYTES = 2L * 1024 * 1024
private const val CRASH_FLUSH_WAIT_S = 1L

// Camera addresses can hold a login, e.g. rtsp://user:password@camera/
private val URL_LOGIN = Regex("://[^/\\s:@]+:[^/\\s@]+@")
private val SECRET_PARAMETER = Regex("(token|password|api_key)=[^&\\s]+", RegexOption.IGNORE_CASE)

/**
 * The app's log, on top of logcat, which is small and can be wiped at any time: one file per hour,
 * YYYY-MM-DD_HH.log, deleted after [hoursKept]. In the app's external files folder, as a release
 * build's private files can't be read over adb.
 */
object AppLog {
    private val chunkName = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH")
    private val lineTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    // One at a time, away from the main thread
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "AppLog").apply { isDaemon = true } }

    @Volatile private var folder: File? = null
    @Volatile private var hoursKept = LOG_HOURS_DEFAULT

    // Used on the writer's thread only
    private var currentChunk = ""
    private var droppingChunk = ""

    /** Once, when the process starts. Crashes get logged from here on. */
    fun start(context: Context) {
        folder = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FOLDER)
        hoursKept = LauncherPrefs(context).logHoursKept
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            e("Crash", "Uncaught on thread ${thread.name}", e)
            // The process is about to die: let the line reach the file
            runCatching { writer.submit { }.get(CRASH_FLUSH_WAIT_S, TimeUnit.SECONDS) }
            previous?.uncaughtException(thread, e)
        }
        i("App", "Started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        write("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        write("W", tag, if (error != null) "$message: $error" else message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        write("E", tag, if (error != null) "$message\n${error.stackTraceToString()}" else message)
    }

    /** Fewer hours than before deletes what's now too old at once */
    fun keepHours(hours: Int) {
        hoursKept = hours
        writer.execute { folder?.let(::deleteOld) }
    }

    /** What the kept hours take up. Reads the disk. */
    fun sizeBytes(): Long = chunks(folder ?: return 0).sumOf { it.length() }

    private fun write(level: String, tag: String, message: String) {
        val now = LocalDateTime.now()
        val line = "${lineTime.format(now)} $level/$tag: ${scrub(message)}\n"
        writer.execute { append(chunkName.format(now), line) }
    }

    private fun scrub(message: String) =
        message.replace(URL_LOGIN, "://***@").replace(SECRET_PARAMETER, "$1=***")

    private fun append(name: String, line: String) {
        val dir = folder ?: return
        // Never let the log itself bring the app down, e.g. when the storage is unavailable
        try {
            if (name != currentChunk) {
                currentChunk = name
                dir.mkdirs()
                deleteOld(dir)
            }
            val file = File(dir, "$name.log")
            if (file.length() < LOG_CHUNK_MAX_BYTES) {
                file.appendText(line)
            } else if (name != droppingChunk) {
                droppingChunk = name
                file.appendText("Over ${LOG_CHUNK_MAX_BYTES / 1024 / 1024} MB: the rest of this hour isn't logged\n")
            }
        } catch (e: Exception) {
            Log.w("AppLog", "Can't write the log", e)
        }
    }

    private fun deleteOld(dir: File) {
        val oldest = System.currentTimeMillis() - hoursKept * MS_PER_HOUR
        chunks(dir).filter { it.lastModified() < oldest }.forEach { it.delete() }
    }

    private fun chunks(dir: File): List<File> = dir.listFiles { file -> file.name.endsWith(".log") }.orEmpty().toList()
}
