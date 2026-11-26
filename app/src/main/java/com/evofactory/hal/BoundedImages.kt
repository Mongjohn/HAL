package com.evofactory.hal

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Reject malformed/absurd source bounds before allocating pixels; sample to the display bucket. */
internal fun decodeBoundedImage(bytes: ByteArray, target: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = mediaArtworkSampleSize(bounds.outWidth, bounds.outHeight, target)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

internal data class MediaImageKey(val origin: String, val account: String, val url: String, val size: Int, val content: List<String?> = emptyList())
internal val mediaImageCache = SharedRequestCache<MediaImageKey, ImageBitmap>(16L * 1024 * 1024) {
    it.width.toLong() * it.height * 4
}
internal fun clearMediaImages() = mediaImageCache.invalidate { true }
