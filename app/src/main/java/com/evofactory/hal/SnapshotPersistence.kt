package com.evofactory.hal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor

/** One bounded latest-wins queue. Compression, replacement and pruning run on its worker only. */
internal class SnapshotPersistence(
    private val executor: Executor,
    private val failed: (Exception) -> Unit,
) {
    private data class Write(val file: File, val time: Long, val encode: (File) -> Unit)
    private val pending = linkedMapOf<File, Write>()
    private val saved = mutableMapOf<File, Long>()
    private var allowed: Set<File>? = null
    private var pruning: File? = null
    private var running = false

    @Synchronized fun save(file: File, time: Long, encode: (File) -> Unit) {
        if (allowed?.contains(file) == false || time <= (saved[file] ?: Long.MIN_VALUE) ||
            time <= (pending[file]?.time ?: Long.MIN_VALUE)) return
        if (pending.size >= 200 && file !in pending) pending.remove(pending.keys.first())
        pending[file] = Write(file, time, encode)
        start()
    }

    @Synchronized fun prune(directory: File, keep: Set<File>) {
        allowed = keep
        pending.keys.retainAll(keep)
        saved.keys.retainAll(keep)
        pruning = directory
        start()
    }

    private fun start() {
        if (!running) { running = true; executor.execute(::drain) }
    }

    private fun drain() {
        while (true) {
            val work = synchronized(this) {
                val first = pending.entries.firstOrNull()
                if (first != null) pending.remove(first.key) else null
            }
            if (work != null) {
                var temporary: File? = null
                try {
                    temporary = File.createTempFile(".camera-", ".tmp", work.file.parentFile)
                    work.encode(temporary)
                    val eligible = synchronized(this) {
                        allowed?.contains(work.file) != false && work.time > (saved[work.file] ?: Long.MIN_VALUE)
                    }
                    if (eligible) {
                        Files.move(temporary.toPath(), work.file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        work.file.setLastModified(work.time)
                        synchronized(this) { saved[work.file] = work.time }
                    }
                } catch (e: IOException) { failed(e) }
                catch (e: SecurityException) { failed(e) }
                finally { try { temporary?.delete() } catch (e: SecurityException) { failed(e) } }
                continue
            }
            val cleanup = synchronized(this) { pruning?.let { it to allowed.orEmpty() }.also { pruning = null } }
            if (cleanup != null) {
                try {
                    cleanup.first.listFiles { f -> f.name.startsWith("camera_") && f.name.endsWith(".jpg") && f !in cleanup.second }
                        ?.forEach { Files.deleteIfExists(it.toPath()) }
                } catch (e: IOException) { failed(e) }
                catch (e: SecurityException) { failed(e) }
                continue
            }
            synchronized(this) {
                if (pending.isEmpty() && pruning == null) { running = false; return }
            }
        }
    }
}
