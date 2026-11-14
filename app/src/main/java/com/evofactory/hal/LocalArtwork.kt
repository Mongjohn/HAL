package com.evofactory.hal

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val MAX_LOCAL_ARTWORK_BYTES = 8 * 1024 * 1024
private const val LOCAL_ARTWORK_DIRECTORY = "slideshow-artwork"
private val artworkFileName = Regex("[0-9a-f-]{36}_[a-zA-Z0-9_-]{1,80}[.](jpg|png|webp)")
private val artworkLock = Any()
private val localChanges = MutableStateFlow(0L)
internal val localArtworkChanges = localChanges.asStateFlow()

internal class LocalArtwork(context: Context) {
    private val directory = File(context.filesDir, LOCAL_ARTWORK_DIRECTORY)

    private fun files(): List<File> {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return emptyList()
        return directory.listFiles().orEmpty()
            .filter { artworkFileName.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .take(ARTWORK_LIMIT)
    }

    fun slides(): List<String> = synchronized(artworkLock) { files().map { it.name } }

    fun listing(): JSONObject = synchronized(artworkLock) {
        JSONObject().put("limit", ARTWORK_LIMIT).put("files", JSONArray(files().map {
            JSONObject().put("id", it.name).put("name", it.name.substringAfter('_'))
        }))
    }

    fun upload(bytes: ByteArray, originalName: String) {
        require(bytes.size in 1..MAX_LOCAL_ARTWORK_BYTES) { "Choose an image up to 8 MB." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val extension = when (bounds.outMimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("Choose a JPEG, PNG, or WebP image.")
        }
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) {
            "The image must be at most 8192 pixels wide and tall."
        }
        val sample = BitmapFactory.Options().apply { inSampleSize = 16 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample)
            ?: throw IllegalArgumentException("The image could not be decoded.")
        decoded.recycle()
        val label = originalName.substringBeforeLast('.').take(80)
            .replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_').ifEmpty { "image" }
        synchronized(artworkLock) {
            require(files().size < ARTWORK_LIMIT) { "The slideshow already has $ARTWORK_LIMIT images." }
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(directory.toPath())
            val id = "${UUID.randomUUID()}_$label.$extension"
            val temporary = File.createTempFile(".upload-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                Files.move(temporary.toPath(), File(directory, id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            localChanges.value++
        }
    }

    fun delete(id: String): Boolean = synchronized(artworkLock) {
        val file = files().firstOrNull { it.name == id } ?: return@synchronized false
        Files.delete(file.toPath())
        localChanges.value++
        true
    }

    fun read(id: String): ByteArray? = synchronized(artworkLock) {
        val file = files().firstOrNull { it.name == id } ?: return@synchronized null
        file.inputStream().use { readLimited(it, MAX_LOCAL_ARTWORK_BYTES) }
    }
}
