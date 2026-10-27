package com.example.silversticker.utils

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Metadata embedded inside WhatsApp WebP stickers (stored in the EXIF chunk).
 */
data class WebpMetadata(
    val packId: String? = null,
    val packName: String? = null,
    val publisher: String? = null,
    val emojis: List<String> = emptyList(),
    val accessibilityText: String? = null,
    val androidPlayStoreLink: String? = null,
    val iosAppStoreLink: String? = null
)

/**
 * Utility for reading and writing official WhatsApp WebP EXIF metadata.
 * WhatsApp and .wastickers packs store per-sticker metadata (emojis, pack name, publisher)
 * inside a TIFF-like structure in the "EXIF" RIFF chunk of WebP images.
 */
object WebpExifHelper {
    private const val TAG = "WebpExifHelper"

    /**
     * Extracts WhatsApp sticker metadata (emojis, pack name, publisher) from a WebP file.
     */
    fun extractMetadata(file: File): WebpMetadata? {
        if (!file.exists() || file.length() < 30) return null
        return try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            extractMetadata(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read WebP file ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Extracts WhatsApp sticker metadata from raw WebP bytes.
     */
    fun extractMetadata(bytes: ByteArray): WebpMetadata? {
        if (bytes.size < 12) return null

        // 1. Try extracting from standard EXIF RIFF chunk
        val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val isWebp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

        if (isRiff && isWebp) {
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
                        ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                        ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                        ((bytes[offset + 7].toInt() and 0xFF) shl 24)

                val payloadStart = offset + 8
                if (chunkSize < 0 || payloadStart + chunkSize > bytes.size) break

                if (fourCC == "EXIF") {
                    val exifBytes = bytes.copyOfRange(payloadStart, payloadStart + chunkSize)
                    val meta = parseMetadataFromBytes(exifBytes)
                    if (meta != null && (meta.emojis.isNotEmpty() || meta.packName != null)) {
                        return meta
                    }
                }

                val pad = chunkSize and 1
                offset = payloadStart + chunkSize + pad
            }
        }

        // 2. Fallback: Search the entire byte array for embedded JSON containing WhatsApp sticker tags
        return parseMetadataFromBytes(bytes)
    }

    /**
     * Injects WhatsApp EXIF metadata into an existing WebP file.
     */
    fun injectMetadata(file: File, metadata: WebpMetadata): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            val originalBytes = FileInputStream(file).use { it.readBytes() }
            val newBytes = injectMetadata(originalBytes, metadata)
            FileOutputStream(file).use { it.write(newBytes) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject WebP metadata: ${e.message}", e)
            false
        }
    }

    /**
     * Injects WhatsApp EXIF metadata into WebP bytes and returns the modified byte array.
     */
    fun injectMetadata(bytes: ByteArray, metadata: WebpMetadata): ByteArray {
        if (bytes.size < 12) return bytes
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val webp = String(bytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || webp != "WEBP") return bytes

        try {
            // Build JSON metadata payload
            val root = JsonObject().apply {
                metadata.packId?.let { addProperty("sticker-pack-id", it) }
                metadata.packName?.let { addProperty("sticker-pack-name", it) }
                metadata.publisher?.let { addProperty("sticker-pack-publisher", it) }
                metadata.androidPlayStoreLink?.let { addProperty("android-app-store-link", it) }
                metadata.iosAppStoreLink?.let { addProperty("ios-app-store-link", it) }
                metadata.accessibilityText?.let { addProperty("accessibility_text", it) }

                val emojis = metadata.emojis.take(3).ifEmpty { listOf("😊") }
                val emojiArray = JsonArray()
                emojis.forEach { emojiArray.add(it) }
                add("emojis", emojiArray)
            }
            val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)

            // Construct TIFF Header and IFD0 per WhatsApp EXIF spec (26 bytes header + JSON)
            val exifPayload = ByteArrayOutputStream()
            // TIFF header: Intel little-endian ("II"), magic 42, IFD0 offset = 8
            exifPayload.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))
            exifPayload.write(byteArrayOf(0x08, 0x00, 0x00, 0x00))

            // IFD0: 1 tag entry
            exifPayload.write(byteArrayOf(0x01, 0x00))
            // Custom WhatsApp Tag: 0x5741 ("WA" in little-endian: 0x41, 0x57)
            exifPayload.write(byteArrayOf(0x41, 0x57))
            // Type 7 = UNDEFINED
            exifPayload.write(byteArrayOf(0x07, 0x00))
            // Length of JSON bytes
            val jsonLen = jsonBytes.size
            exifPayload.write(byteArrayOf(
                (jsonLen and 0xFF).toByte(),
                ((jsonLen shr 8) and 0xFF).toByte(),
                ((jsonLen shr 16) and 0xFF).toByte(),
                ((jsonLen shr 24) and 0xFF).toByte()
            ))
            // Offset to data = 26 bytes (0x1A)
            exifPayload.write(byteArrayOf(0x1A, 0x00, 0x00, 0x00))
            // Next IFD = 0
            exifPayload.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            // Write JSON string
            exifPayload.write(jsonBytes)

            val rawExifData = exifPayload.toByteArray()

            // Collect existing chunks (strip existing EXIF chunk)
            class Chunk(val fourCC: String, val data: ByteArray)
            val chunks = mutableListOf<Chunk>()
            var offset = 12
            var hasVp8x = false
            var vp8xChunk: Chunk? = null
            var hasAlpha = false
            var isAnimated = false
            var canvasWidth = 512
            var canvasHeight = 512

            while (offset + 8 <= bytes.size) {
                val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
                        ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                        ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                        ((bytes[offset + 7].toInt() and 0xFF) shl 24)

                val payloadStart = offset + 8
                if (chunkSize < 0 || payloadStart + chunkSize > bytes.size) break

                val chunkData = bytes.copyOfRange(payloadStart, payloadStart + chunkSize)
                if (fourCC == "VP8X") {
                    hasVp8x = true
                    if (chunkData.size >= 10) {
                        val flags = chunkData[0].toInt() and 0xFF
                        if ((flags and 0x02) != 0) isAnimated = true
                        if ((flags and 0x10) != 0) hasAlpha = true
                        val w = (chunkData[4].toInt() and 0xFF) or
                                ((chunkData[5].toInt() and 0xFF) shl 8) or
                                ((chunkData[6].toInt() and 0xFF) shl 16)
                        val h = (chunkData[7].toInt() and 0xFF) or
                                ((chunkData[8].toInt() and 0xFF) shl 8) or
                                ((chunkData[9].toInt() and 0xFF) shl 16)
                        canvasWidth = w + 1
                        canvasHeight = h + 1
                        chunkData[0] = (flags or 0x08).toByte() // EXIF flag
                    }
                    vp8xChunk = Chunk(fourCC, chunkData)
                } else if (fourCC == "ALPH") {
                    hasAlpha = true
                    chunks.add(Chunk(fourCC, chunkData))
                } else if (fourCC == "ANIM" || fourCC == "ANMF") {
                    isAnimated = true
                    chunks.add(Chunk(fourCC, chunkData))
                } else if (fourCC == "VP8L") {
                    hasAlpha = true
                    if (chunkData.size >= 5 && chunkData[0] == 0x2F.toByte()) {
                        val b1 = chunkData[1].toInt() and 0xFF
                        val b2 = chunkData[2].toInt() and 0xFF
                        val b3 = chunkData[3].toInt() and 0xFF
                        val b4 = chunkData[4].toInt() and 0xFF
                        canvasWidth = (b1 or ((b2 and 0x3F) shl 8)) + 1
                        canvasHeight = (((b2 and 0xC0) shr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10)) + 1
                    }
                    chunks.add(Chunk(fourCC, chunkData))
                } else if (fourCC == "VP8 ") {
                    if (chunkData.size >= 10) {
                        canvasWidth = ((chunkData[6].toInt() and 0xFF) or ((chunkData[7].toInt() and 0xFF) shl 8)) and 0x3FFF
                        canvasHeight = ((chunkData[8].toInt() and 0xFF) or ((chunkData[9].toInt() and 0xFF) shl 8)) and 0x3FFF
                    }
                    chunks.add(Chunk(fourCC, chunkData))
                } else if (fourCC != "EXIF") {
                    chunks.add(Chunk(fourCC, chunkData))
                }

                val pad = chunkSize and 1
                offset = payloadStart + chunkSize + pad
            }

            // Build new WebP output stream
            val output = ByteArrayOutputStream()
            // RIFF header placeholder (12 bytes)
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.write(ByteArray(4)) // Size placeholder
            output.write("WEBP".toByteArray(Charsets.US_ASCII))

            // Write VP8X chunk (required when EXIF is present)
            if (hasVp8x && vp8xChunk != null) {
                writeChunk(output, "VP8X", vp8xChunk.data)
            } else {
                // Synthetic VP8X header (10 bytes: flags, reserved=3 bytes, canvas w, h)
                val vp8xData = ByteArray(10)
                var flags = 0x08 // EXIF flag
                if (hasAlpha) flags = flags or 0x10 // ALPHA flag (mandatory if ALPH chunk exists)
                if (isAnimated) flags = flags or 0x02 // ANIMATION flag
                vp8xData[0] = flags.toByte()

                val wMinus1 = maxOf(0, canvasWidth - 1)
                val hMinus1 = maxOf(0, canvasHeight - 1)
                vp8xData[4] = (wMinus1 and 0xFF).toByte()
                vp8xData[5] = ((wMinus1 shr 8) and 0xFF).toByte()
                vp8xData[6] = ((wMinus1 shr 16) and 0xFF).toByte()
                vp8xData[7] = (hMinus1 and 0xFF).toByte()
                vp8xData[8] = ((hMinus1 shr 8) and 0xFF).toByte()
                vp8xData[9] = ((hMinus1 shr 16) and 0xFF).toByte()
                writeChunk(output, "VP8X", vp8xData)
            }

            // Write original content chunks (image bitstream, etc.)
            for (c in chunks) {
                writeChunk(output, c.fourCC, c.data)
            }

            // Write the new EXIF chunk
            writeChunk(output, "EXIF", rawExifData)

            val finalBytes = output.toByteArray()
            // Update RIFF total size: (file size - 8)
            val totalSizeMinus8 = finalBytes.size - 8
            finalBytes[4] = (totalSizeMinus8 and 0xFF).toByte()
            finalBytes[5] = ((totalSizeMinus8 shr 8) and 0xFF).toByte()
            finalBytes[6] = ((totalSizeMinus8 shr 16) and 0xFF).toByte()
            finalBytes[7] = ((totalSizeMinus8 shr 24) and 0xFF).toByte()

            return finalBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting metadata into WebP bytes: ${e.message}", e)
            return bytes
        }
    }

    private fun writeChunk(output: ByteArrayOutputStream, fourCC: String, data: ByteArray) {
        output.write(fourCC.toByteArray(Charsets.US_ASCII))
        val size = data.size
        output.write(size and 0xFF)
        output.write((size shr 8) and 0xFF)
        output.write((size shr 16) and 0xFF)
        output.write((size shr 24) and 0xFF)
        output.write(data)
        if (size and 1 != 0) {
            output.write(0) // RIFF odd-byte padding
        }
    }

    private fun parseMetadataFromBytes(bytes: ByteArray): WebpMetadata? {
        val markers = listOf(
            "\"emojis\"".toByteArray(Charsets.UTF_8),
            "\"sticker-pack-id\"".toByteArray(Charsets.UTF_8),
            "\"sticker-pack-name\"".toByteArray(Charsets.UTF_8),
            "\"categories\"".toByteArray(Charsets.UTF_8)
        )

        for (marker in markers) {
            var markerPos = indexOf(bytes, marker, 0)
            while (markerPos != -1) {
                // Search backwards for the opening '{' of the JSON object
                var start = -1
                for (i in markerPos downTo maxOf(0, markerPos - 512)) {
                    if (bytes[i] == '{'.code.toByte()) {
                        start = i
                        break
                    }
                }

                if (start != -1) {
                    // Match closing '}' respecting strings and escapes
                    var depth = 0
                    var inString = false
                    var escape = false
                    var end = -1

                    for (i in start until minOf(bytes.size, start + 8192)) {
                        val b = bytes[i].toInt().toChar()
                        if (escape) {
                            escape = false
                            continue
                        }
                        if (b == '\\') {
                            escape = true
                            continue
                        }
                        if (b == '"') {
                            inString = !inString
                            continue
                        }
                        if (!inString) {
                            if (b == '{') depth++
                            else if (b == '}') {
                                depth--
                                if (depth == 0) {
                                    end = i + 1
                                    break
                                }
                            }
                        }
                    }

                    if (end != -1) {
                        try {
                            val jsonStr = String(bytes, start, end - start, Charsets.UTF_8).trim()
                            val meta = parseWebpJson(jsonStr)
                            if (meta != null && (meta.emojis.isNotEmpty() || meta.packName != null || meta.packId != null)) {
                                return meta
                            }
                        } catch (_: Exception) {}
                    }
                }

                markerPos = indexOf(bytes, marker, markerPos + 1)
            }
        }
        return null
    }

    private fun parseWebpJson(jsonStr: String): WebpMetadata? {
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val packId = root.get("sticker-pack-id")?.asString
                ?: root.get("identifier")?.asString
                ?: root.get("id")?.asString

            val packName = root.get("sticker-pack-name")?.asString
                ?: root.get("pack-name")?.asString
                ?: root.get("name")?.asString
                ?: root.get("title")?.asString

            val publisher = root.get("sticker-pack-publisher")?.asString
                ?: root.get("sticker-author-name")?.asString
                ?: root.get("publisher")?.asString
                ?: root.get("author")?.asString

            val playStoreLink = root.get("android-app-store-link")?.asString
            val appStoreLink = root.get("ios-app-store-link")?.asString
            val accessibilityText = root.get("accessibility_text")?.asString
                ?: root.get("accessibility-text")?.asString
                ?: root.get("accessibilityText")?.asString

            val emojis = mutableListOf<String>()

            if (root.has("emojis")) {
                val elem = root.get("emojis")
                if (elem.isJsonArray) {
                    elem.asJsonArray.forEach { e ->
                        if (e.isJsonPrimitive) {
                            val text = e.asString
                            val split = ImageUtils.splitIntoEmojis(text)
                            if (split.isNotEmpty()) emojis.addAll(split)
                            else if (text.isNotBlank()) emojis.add(text.trim())
                        }
                    }
                } else if (elem.isJsonPrimitive) {
                    val text = elem.asString
                    val split = ImageUtils.splitIntoEmojis(text)
                    if (split.isNotEmpty()) emojis.addAll(split)
                    else if (text.isNotBlank()) emojis.add(text.trim())
                }
            }

            if (emojis.isEmpty() && root.has("categories")) {
                val elem = root.get("categories")
                if (elem.isJsonArray) {
                    elem.asJsonArray.forEach { e ->
                        if (e.isJsonPrimitive) {
                            val text = e.asString
                            val split = ImageUtils.splitIntoEmojis(text)
                            if (split.isNotEmpty()) emojis.addAll(split)
                            else if (text.isNotBlank()) emojis.add(text.trim())
                        }
                    }
                }
            }

            val cleanedEmojis = emojis.filter { it.isNotBlank() }.distinct().take(3)

            WebpMetadata(
                packId = packId?.takeIf { it.isNotBlank() },
                packName = packName?.takeIf { it.isNotBlank() },
                publisher = publisher?.takeIf { it.isNotBlank() },
                emojis = cleanedEmojis,
                accessibilityText = accessibilityText?.takeIf { it.isNotBlank() },
                androidPlayStoreLink = playStoreLink,
                iosAppStoreLink = appStoreLink
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, startIndex: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size || startIndex >= haystack.size) return -1
        val start = maxOf(0, startIndex)
        val max = haystack.size - needle.size
        for (i in start..max) {
            var found = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
