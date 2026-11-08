package net.spritzlive.securemessenger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

private const val MAX_DIMENSION = 1280
private const val JPEG_QUALITY = 70

/**
 * Turns a picked or captured photo's Uri into a compressed JPEG ready to send —
 * resized to [MAX_DIMENSION] on its longest edge at [JPEG_QUALITY], comfortably
 * clearing the relay's raised size cap. CPU-bound; callers dispatch off-main
 * (see NetworkRepository's own "every method does blocking work" contract, which
 * this mirrors).
 */
object ImagePipeline {
    fun loadResizedJpeg(context: Context, uri: Uri): ByteArray {
        val bitmap = decodeDownsampled(context, uri) ?: error("couldn't decode image at $uri")
        val scaled = scaleToMax(bitmap, MAX_DIMENSION)
        if (scaled !== bitmap) bitmap.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        return out.toByteArray()
    }

    /** Decodes at a coarse inSampleSize first — avoids allocating a full-resolution
     * bitmap (easily 10+ MP from a modern camera) just to immediately scale it down. */
    private fun decodeDownsampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DIMENSION * 2 || bounds.outHeight / sampleSize > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun scaleToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
