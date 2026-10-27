package com.example.silversticker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Extracts individual frames from a GIF using Android's Movie class,
 * and parses GIF extension blocks for per-frame delay times.
 */
object GifDecoder {

    private const val TAG = "GifDecoder"

    data class GifFrame(val bitmap: Bitmap, val delayMs: Int)

    /**
     * Decode a GIF URI into a list of frames, each scaled to [targetSize] x [targetSize].
     * Returns null if the image is not an animated GIF.
     */
    fun decode(context: Context, uri: Uri, targetSize: Int): List<GifFrame>? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        // Check GIF magic bytes
        if (bytes.size < 6) return null
        val magic = String(bytes, 0, 6)
        if (!magic.startsWith("GIF8")) return null

        @Suppress("DEPRECATION")
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (movie.duration() <= 0) return null

        val delays = parseFrameDelays(bytes)
        if (delays.isEmpty()) return null

        Log.d(TAG, "GIF: ${movie.width()}x${movie.height()}, ${delays.size} frames, ${movie.duration()}ms total")

        val margin = if (targetSize == 512) 16 else 4
        val innerSize = targetSize - margin * 2
        val scale = innerSize.toFloat() / maxOf(movie.width(), movie.height())
        val scaledW = (movie.width() * scale).toInt()
        val scaledH = (movie.height() * scale).toInt()
        val dx = (targetSize - scaledW) / 2f
        val dy = (targetSize - scaledH) / 2f

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val frames = mutableListOf<GifFrame>()
        var time = 0

        for (delay in delays) {
            movie.setTime(time)
            val bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            movie.draw(canvas, 0f, 0f, paint)
            canvas.restore()

            frames.add(GifFrame(bmp, maxOf(delay, 20))) // min 20ms per frame
            time += delay
        }

        Log.d(TAG, "Decoded ${frames.size} frames from GIF")
        return frames
    }

    /**
     * Parse Graphic Control Extension blocks in the GIF byte stream
     * to extract per-frame delay times (in milliseconds).
     */
    private fun parseFrameDelays(data: ByteArray): List<Int> {
        val delays = mutableListOf<Int>()
        var i = 6 // skip header "GIF89a"

        // Skip Logical Screen Descriptor (7 bytes)
        if (i + 7 > data.size) return delays
        val packed = data[i + 4].toInt() and 0xFF
        val hasGCT = (packed and 0x80) != 0
        val gctSize = if (hasGCT) 3 * (1 shl ((packed and 0x07) + 1)) else 0
        i += 7 + gctSize

        while (i < data.size) {
            when (data[i].toInt() and 0xFF) {
                0x21 -> { // Extension
                    if (i + 1 >= data.size) break
                    val label = data[i + 1].toInt() and 0xFF
                    if (label == 0xF9 && i + 6 < data.size) {
                        // Graphic Control Extension: delay at bytes 3-4 (little-endian, in 1/100s)
                        val lo = data[i + 4].toInt() and 0xFF
                        val hi = data[i + 5].toInt() and 0xFF
                        val delayCs = lo or (hi shl 8)
                        delays.add(delayCs * 10) // convert centiseconds → milliseconds
                        i += 8 // 2 (intro+label) + 1 (block size=4) + 4 (data) + 1 (terminator)
                    } else {
                        // Skip other extensions by traversing sub-blocks
                        i += 2
                        while (i < data.size) {
                            val blockSize = data[i].toInt() and 0xFF
                            i++
                            if (blockSize == 0) break
                            i += blockSize
                        }
                    }
                }
                0x2C -> { // Image Descriptor
                    if (i + 9 >= data.size) break
                    val imgPacked = data[i + 9].toInt() and 0xFF
                    val hasLCT = (imgPacked and 0x80) != 0
                    val lctSize = if (hasLCT) 3 * (1 shl ((imgPacked and 0x07) + 1)) else 0
                    i += 10 + lctSize
                    // Skip LZW minimum code size
                    if (i >= data.size) break
                    i++
                    // Skip image data sub-blocks
                    while (i < data.size) {
                        val blockSize = data[i].toInt() and 0xFF
                        i++
                        if (blockSize == 0) break
                        i += blockSize
                    }
                }
                0x3B -> break // Trailer
                else -> i++
            }
        }
        return delays
    }
}
