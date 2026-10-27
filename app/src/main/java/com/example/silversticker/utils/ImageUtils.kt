package com.example.silversticker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    private const val TAG = "ImageUtils"
    private const val MAX_STICKER_SIZE = 100 * 1024      // 100KB for static
    private const val MAX_ANIMATED_SIZE = 500 * 1024      // 500KB for animated
    private const val MAX_TRAY_SIZE = 50 * 1024

    data class ConversionResult(val file: File, val isAnimated: Boolean)

    /**
     * Converts an image to WhatsApp-compliant WebP.
     * Detects GIFs and converts them to animated WebP; all others become static WebP.
     */
    fun convertToWebP(context: Context, uri: Uri, size: Int, fileName: String): ConversionResult? {
        // Check if it's an animated GIF
        if (size == 512 && isAnimatedGif(context, uri)) {
            val result = convertGifToAnimatedWebP(context, uri, size, fileName)
            if (result != null) return result
            Log.w(TAG, "Animated conversion failed, falling back to static")
        }

        // Static conversion (original logic)
        return convertToStaticWebP(context, uri, size, fileName)
    }

    /** Backward-compatible overload that returns just a File. */
    fun convertToWebPFile(context: Context, uri: Uri, size: Int, fileName: String): File? {
        return convertToWebP(context, uri, size, fileName)?.file
    }

    private fun isAnimatedGif(context: Context, uri: Uri): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().take(6).toByteArray()
            } ?: return false
            val magic = String(bytes, 0, minOf(6, bytes.size))
            if (!magic.startsWith("GIF8")) return false

            // Re-read full bytes to check for multiple frames
            val fullBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            var frameCount = 0
            var i = 0
            while (i < fullBytes.size) {
                if (fullBytes[i].toInt() and 0xFF == 0x2C) frameCount++ // Image Descriptor
                if (frameCount >= 2) return true
                i++
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GIF: ${e.message}")
            false
        }
    }

    private fun convertGifToAnimatedWebP(context: Context, uri: Uri, size: Int, fileName: String): ConversionResult? {
        return try {
            val frames = GifDecoder.decode(context, uri, size) ?: return null
            if (frames.size < 2) return null

            val outputDir = StickerStorage.getStickersDir(context)
            outputDir.mkdirs()
            val finalName = if (fileName.lowercase().endsWith(".webp")) fileName else "$fileName.webp"
            val outputFile = File(outputDir, finalName)

            // Try encoding at decreasing quality until under size limit
            var quality = 80
            var success = false
            while (quality > 10) {
                val encoderFrames = frames.map { AnimatedWebPEncoder.Frame(it.bitmap, it.delayMs) }
                if (AnimatedWebPEncoder.encode(encoderFrames, outputFile, quality)) {
                    if (outputFile.length() <= MAX_ANIMATED_SIZE) {
                        success = true
                        break
                    }
                }
                quality -= 10
            }

            // Recycle bitmaps
            frames.forEach { it.bitmap.recycle() }

            if (success) {
                Log.d(TAG, "Animated WebP: ${frames.size} frames, ${outputFile.length()} bytes, q=$quality")
                // Copy the original GIF to accompany the sticker for high-fidelity downloads/shares
                try {
                    val gifFile = File(outputDir, finalName.replace(".webp", ".gif"))
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        gifFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Original GIF saved as companion: ${gifFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save companion GIF: ${e.message}")
                }
                ConversionResult(outputFile, isAnimated = true)
            } else {
                outputFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in animated conversion: ${e.message}", e)
            null
        }
    }

    private fun convertToStaticWebP(context: Context, uri: Uri, size: Int, fileName: String): ConversionResult? {
        val maxSize = if (size == 96) MAX_TRAY_SIZE else MAX_STICKER_SIZE

        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null

            val outputBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)

            val margin = if (size == 512) 16 else 4
            val innerSize = size - (margin * 2)

            val scale = innerSize.toFloat() / Math.max(originalBitmap.width, originalBitmap.height)
            val scaledWidth = (originalBitmap.width * scale).toInt()
            val scaledHeight = (originalBitmap.height * scale).toInt()

            val left = (size - scaledWidth) / 2f
            val top = (size - scaledHeight) / 2f

            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            canvas.drawBitmap(scaledBitmap, left, top, paint)

            val outputDir = StickerStorage.getStickersDir(context)
            if (!outputDir.exists()) outputDir.mkdirs()

            val finalFileName = if (fileName.lowercase().endsWith(".webp")) fileName else "$fileName.webp"
            val outputFile = File(outputDir, finalFileName)

            var quality = 90
            val bos = ByteArrayOutputStream()

            do {
                bos.reset()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    outputBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bos)
                } else {
                    @Suppress("DEPRECATION")
                    outputBitmap.compress(Bitmap.CompressFormat.WEBP, quality, bos)
                }
                if (bos.size() <= maxSize) break
                quality -= 10
            } while (quality > 10)

            FileOutputStream(outputFile).use { it.write(bos.toByteArray()) }

            originalBitmap.recycle()
            scaledBitmap.recycle()
            outputBitmap.recycle()

            Log.d(TAG, "Static WebP: $fileName → ${outputFile.length()} bytes")
            ConversionResult(outputFile, isAnimated = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error in convertToStaticWebP: ${e.message}")
            null
        }
    }

    /**
     * Checks if a Unicode code point belongs to an emoji range or emoji modifier.
     */
    fun isEmojiCodePoint(codePoint: Int): Boolean {
        return (codePoint in 0x1F300..0x1FAFF) ||
               (codePoint in 0x2600..0x27BF) ||
               (codePoint in 0x1F1E0..0x1F1FF) ||
               (codePoint in 0x1F000..0x1F0FF) ||
               (codePoint in 0x1F600..0x1F64F) ||
               (codePoint in 0x1F680..0x1F6FF) ||
               (codePoint in 0x1F200..0x1F2FF) ||
               (codePoint in 0x2300..0x23FF) ||
               (codePoint in 0x2B00..0x2BFF) ||
               (codePoint in 0xE0000..0xE007F)
    }

    /**
     * Filters a string to only contain valid Unicode emojis and caps the length at 3 emojis.
     */
    fun filterEmojiInput(input: String): String {
        val sb = StringBuilder()
        var emojiCount = 0
        var i = 0
        while (i < input.length && emojiCount < 3) {
            val codePoint = input.codePointAt(i)
            val charCount = Character.charCount(codePoint)

            if (isEmojiCodePoint(codePoint)) {
                sb.append(input.substring(i, i + charCount))
                emojiCount++
            }
            i += charCount
        }
        return sb.toString()
    }

    /**
     * Splits a string of emojis into individual emoji grapheme clusters,
     * preserving compound emojis (e.g. ❤️, flags, skin tone modifiers, and ZWJ sequences)
     * while ignoring spaces, commas, and punctuation.
     */
    fun splitIntoEmojis(input: String): List<String> {
        val list = mutableListOf<String>()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(input)
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            val cluster = input.substring(start, end).trim()
            if (cluster.isNotEmpty()) {
                var containsEmoji = false
                var i = 0
                while (i < cluster.length) {
                    val cp = cluster.codePointAt(i)
                    if (isEmojiCodePoint(cp)) {
                        containsEmoji = true
                        break
                    }
                    i += Character.charCount(cp)
                }
                if (containsEmoji) {
                    list.add(cluster)
                }
            }
            start = end
            end = it.next()
        }
        return list
    }
}
