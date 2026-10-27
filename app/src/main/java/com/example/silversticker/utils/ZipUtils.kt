package com.example.silversticker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.example.silversticker.models.Sticker
import com.example.silversticker.models.StickerPack
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtils {
    private const val TAG = "ZipUtils"
    private const val PACK_JSON_ENTRY = "pack.json"
    private const val CONTENTS_JSON_ENTRY = "contents.json"
    private const val CONTENT_JSON_ENTRY = "content.json"
    private const val TITLE_TXT_ENTRY = "title.txt"
    private const val AUTHOR_TXT_ENTRY = "author.txt"

    private val gson = Gson()

    /**
     * Zip a sticker pack and return a shareable URI.
     * Supports exporting as .wastickers or standard .zip.
     * Includes both pack.json and title.txt/author.txt for universal compatibility.
     */
    fun exportPack(context: Context, pack: StickerPack, asWastickers: Boolean = false): Uri? {
        return try {
            val cacheDir = File(context.cacheDir, "shared_packs")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val ext = if (asWastickers) "wastickers" else "zip"
            val safeName = pack.name.replace("\\s+".toRegex(), "_").replace("[^a-zA-Z0-9_-]".toRegex(), "")
            val exportFile = File(cacheDir, "${safeName}_sticker_pack.$ext")
            if (exportFile.exists()) exportFile.delete()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(exportFile))).use { zos ->
                val stickersDir = StickerStorage.getStickersDir(context)

                // 1. Write the metadata pack.json entry (SilverSticker format)
                val metadataJson = gson.toJson(pack)
                writeTextEntry(zos, PACK_JSON_ENTRY, metadataJson)

                // 2. Write title.txt and author.txt (.wastickers standard format)
                writeTextEntry(zos, TITLE_TXT_ENTRY, pack.name)
                writeTextEntry(zos, AUTHOR_TXT_ENTRY, pack.publisher)

                // Also write emojis.txt for maximum compatibility across third-party .wastickers tools
                val emojisSummary = buildString {
                    pack.stickers.forEach { s ->
                        val emojiStr = s.emojis.joinToString(", ")
                        append("${s.imageFileName}: $emojiStr\n")
                    }
                }
                writeTextEntry(zos, "emojis.txt", emojisSummary)

                // Write official WhatsApp contents.json with accessibility_text for every sticker
                val contentsJsonObj = JsonObject().apply {
                    addProperty("android_play_store_link", "")
                    addProperty("ios_app_store_link", "")
                    addProperty("name", pack.name)
                    addProperty("publisher", pack.publisher)
                    addProperty("tray_image_file", pack.trayImageFileName)
                    val stickersArray = JsonArray()
                    pack.stickers.forEachIndexed { index, s ->
                        val sObj = JsonObject().apply {
                            addProperty("image_file", s.imageFileName)
                            val emArr = JsonArray()
                            s.emojis.forEach { emArr.add(it) }
                            add("emojis", emArr)
                            val a11y = s.accessibilityText?.takeIf { it.isNotBlank() }
                                ?: if (s.emojis.isNotEmpty()) "Sticker expressing ${s.emojis.joinToString(" ")}"
                                else "Sticker ${index + 1}"
                            addProperty("accessibility_text", a11y)
                        }
                        stickersArray.add(sObj)
                    }
                    add("stickers", stickersArray)
                }
                writeTextEntry(zos, CONTENTS_JSON_ENTRY, contentsJsonObj.toString())

                // 3. Write the tray image
                val trayFile = File(stickersDir, pack.trayImageFileName)
                if (trayFile.exists()) {
                    writeFileEntry(zos, trayFile, pack.trayImageFileName)
                }

                // 4. Write each sticker file (ensuring official WhatsApp EXIF metadata is injected)
                for (sticker in pack.stickers) {
                    val stickerFile = File(stickersDir, sticker.imageFileName)
                    if (stickerFile.exists()) {
                        if (sticker.imageFileName.endsWith(".webp", ignoreCase = true)) {
                            WebpExifHelper.injectMetadata(
                                stickerFile,
                                WebpMetadata(
                                    packId = pack.identifier,
                                    packName = pack.name,
                                    publisher = pack.publisher,
                                    emojis = sticker.emojis
                                )
                            )
                        }
                        writeFileEntry(zos, stickerFile, sticker.imageFileName)
                    }
                    // Also include companion GIF if present
                    val gifFile = File(stickersDir, sticker.imageFileName.replace(".webp", ".gif"))
                    if (gifFile.exists()) {
                        writeFileEntry(zos, gifFile, gifFile.name)
                    }
                }
            }

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export pack: ${e.message}", e)
            null
        }
    }

    private fun writeTextEntry(zos: ZipOutputStream, entryName: String, text: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeFileEntry(zos: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(1024 * 4)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                zos.write(buffer, 0, bytesRead)
            }
        }
        zos.closeEntry()
    }

    /**
     * Imports one or more sticker packs from a URI.
     * If the archive contains nested .wastickers files, each nested pack is extracted and imported.
     * Otherwise, imports the archive as a single pack.
     */
    fun importPacks(context: Context, zipUri: Uri): List<StickerPack> {
        val nestedFiles = mutableListOf<File>()
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(zipUri) ?: return emptyList()
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (!entry.isDirectory && (name.endsWith(".wastickers") || name.endsWith(".wasticker"))) {
                        val tempFile = File(context.cacheDir, "nested_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.wastickers")
                        FileOutputStream(tempFile).use { fos ->
                            val buffer = ByteArray(1024 * 8)
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                            }
                        }
                        nestedFiles.add(tempFile)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed scanning for nested wastickers in $zipUri: ${e.message}", e)
        }

        if (nestedFiles.isNotEmpty()) {
            val packs = mutableListOf<StickerPack>()
            for (nested in nestedFiles) {
                try {
                    val pack = importPack(context, Uri.fromFile(nested))
                    if (pack != null) {
                        packs.add(pack)
                    }
                } finally {
                    try { if (nested.exists()) nested.delete() } catch (_: Exception) {}
                }
            }
            if (packs.isNotEmpty()) {
                return packs
            }
        }

        val single = importPack(context, zipUri)
        return if (single != null) listOf(single) else emptyList()
    }

    /**
     * Imports a .wastickers or .zip sticker pack URI.
     * Supports:
     * 1. Standard .wastickers archives (with title.txt, author.txt, tray icon, webp/png stickers)
     * 2. Official WhatsApp Stickers contents.json format
     * 3. SilverSticker pack.json archives
     * 4. Raw archives with images (derives name from filename, generates tray icon)
     */
    fun importPack(context: Context, zipUri: Uri): StickerPack? {
        val stickersDir = StickerStorage.getStickersDir(context)
        if (!stickersDir.exists()) stickersDir.mkdirs()

        val tempFiles = mutableMapOf<String, File>()
        val textContents = mutableMapOf<String, String>()

        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(zipUri) ?: return null
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    // Ignore directories, MacOS artifacts, and hidden files
                    if (!entry.isDirectory && !entryName.startsWith("__MACOSX") && !entryName.contains("/.")) {
                        val simpleName = File(entryName).name
                        val lowerName = simpleName.lowercase(Locale.ROOT)

                        // Read metadata text entries directly into memory
                        val isMetadataEntry = lowerName == PACK_JSON_ENTRY ||
                                lowerName == CONTENTS_JSON_ENTRY ||
                                lowerName == CONTENT_JSON_ENTRY ||
                                lowerName == TITLE_TXT_ENTRY ||
                                lowerName == AUTHOR_TXT_ENTRY ||
                                lowerName == "emojis.txt" ||
                                lowerName == "emoji.txt" ||
                                lowerName == "emojis.json" ||
                                lowerName == "metadata.txt" ||
                                lowerName == "metadata.tsv" ||
                                lowerName == "metadata.json" ||
                                lowerName == "stickers.txt" ||
                                lowerName == "stickers.json" ||
                                lowerName == "tags.txt"

                        if (isMetadataEntry) {
                            val bytes = zis.readBytes()
                            val text = String(bytes, Charsets.UTF_8).trim()
                            textContents[lowerName] = text
                        } else {
                            // Extract asset files into temp directory
                            val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_$simpleName")
                            FileOutputStream(tempFile).use { fos ->
                                val buffer = ByteArray(1024 * 4)
                                var bytesRead: Int
                                while (zis.read(buffer).also { bytesRead = it } != -1) {
                                    fos.write(buffer, 0, bytesRead)
                                }
                            }
                            tempFiles[lowerName] = tempFile
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            Log.d(TAG, "Extracted ${tempFiles.size} asset files and ${textContents.size} metadata files from $zipUri")

            // 1. Resolve Pack Metadata (Name & Publisher)
            var packName: String? = null
            var publisherName: String? = null
            var explicitTrayName: String? = null
            val emojiMap = mutableMapOf<String, List<String>>() // lower filename -> emojis
            val accessibilityMap = mutableMapOf<String, String>() // lower filename -> accessibility_text
            var packJsonStickers: List<Sticker>? = null

            // Parse extra emoji mapping files if present (emojis.txt, metadata.txt, emojis.json)
            parseExtraEmojiMappings(textContents, emojiMap)

            // Check A: pack.json (SilverSticker format)
            textContents[PACK_JSON_ENTRY]?.let { json ->
                try {
                    val pack = gson.fromJson(json, StickerPack::class.java)
                    if (pack != null) {
                        packName = pack.name
                        publisherName = pack.publisher
                        explicitTrayName = pack.trayImageFileName.lowercase(Locale.ROOT)
                        packJsonStickers = pack.stickers
                        pack.stickers.forEach { s ->
                            val lower = s.imageFileName.lowercase(Locale.ROOT)
                            emojiMap[lower] = s.emojis
                            emojiMap[lower.substringBeforeLast(".")] = s.emojis
                            s.accessibilityText?.takeIf { it.isNotBlank() }?.let {
                                accessibilityMap[lower] = it
                                accessibilityMap[lower.substringBeforeLast(".")] = it
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing pack.json: ${e.message}")
                }
            }

            // Check B: contents.json or content.json (WhatsApp official spec)
            if (packName == null) {
                val jsonStr = textContents[CONTENTS_JSON_ENTRY] ?: textContents[CONTENT_JSON_ENTRY]
                if (jsonStr != null) {
                    try {
                        val rootObj = JsonParser.parseString(jsonStr).asJsonObject
                        val packObj = if (rootObj.has("sticker_packs")) {
                            rootObj.getAsJsonArray("sticker_packs").firstOrNull()?.asJsonObject
                        } else {
                            rootObj
                        }

                        if (packObj != null) {
                            if (packObj.has("name")) packName = packObj.get("name").asString
                            if (packObj.has("publisher")) publisherName = packObj.get("publisher").asString
                            if (packObj.has("tray_image_file")) explicitTrayName = packObj.get("tray_image_file").asString.lowercase(Locale.ROOT)

                            if (packObj.has("stickers")) {
                                packObj.getAsJsonArray("stickers").forEach { item ->
                                    val itemObj = item.asJsonObject
                                    val imgFile = itemObj.get("image_file")?.asString?.lowercase(Locale.ROOT)
                                    val emojis = mutableListOf<String>()
                                    if (itemObj.has("emojis")) {
                                        itemObj.getAsJsonArray("emojis").forEach { e -> emojis.add(e.asString) }
                                    }
                                    val a11y = itemObj.get("accessibility_text")?.asString
                                    if (imgFile != null) {
                                        val cleanName = File(imgFile).name.lowercase(Locale.ROOT)
                                        if (emojis.isNotEmpty()) {
                                            emojiMap[cleanName] = emojis
                                            emojiMap[cleanName.substringBeforeLast(".")] = emojis
                                        }
                                        if (!a11y.isNullOrBlank()) {
                                            accessibilityMap[cleanName] = a11y
                                            accessibilityMap[cleanName.substringBeforeLast(".")] = a11y
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing contents.json: ${e.message}")
                    }
                }
            }

            // Check C: title.txt and author.txt (.wastickers format)
            if (packName.isNullOrBlank()) {
                textContents[TITLE_TXT_ENTRY]?.takeIf { it.isNotBlank() }?.let { packName = it }
            }
            if (publisherName.isNullOrBlank()) {
                textContents[AUTHOR_TXT_ENTRY]?.takeIf { it.isNotBlank() }?.let { publisherName = it }
            }

            // 2. Identify Image Files
            val imageExtensions = listOf(".webp", ".png", ".jpg", ".jpeg", ".gif")
            val imageEntries = tempFiles.filter { (name, file) ->
                imageExtensions.any { ext -> name.endsWith(ext) } && file.length() > 0
            }

            if (imageEntries.isEmpty()) {
                Log.e(TAG, "No image files found in sticker archive")
                return null
            }

            // Check D: If packName or publisherName is still missing, scan WebP files for embedded EXIF metadata!
            if (packName.isNullOrBlank() || publisherName.isNullOrBlank()) {
                for ((name, file) in imageEntries) {
                    if (name.endsWith(".webp")) {
                        val meta = WebpExifHelper.extractMetadata(file)
                        if (meta != null) {
                            if (packName.isNullOrBlank() && !meta.packName.isNullOrBlank()) {
                                packName = meta.packName
                            }
                            if (publisherName.isNullOrBlank() && !meta.publisher.isNullOrBlank()) {
                                publisherName = meta.publisher
                            }
                        }
                        if (!packName.isNullOrBlank() && !publisherName.isNullOrBlank()) break
                    }
                }
            }

            // Fallback for Name: Extract from URI filename (e.g. "Cat_Memes.wastickers" -> "Cat Memes")
            if (packName.isNullOrBlank()) {
                val displayName = getDisplayNameFromUri(context, zipUri) ?: "Imported Pack"
                packName = displayName
                    .replace(".wastickers", "", ignoreCase = true)
                    .replace(".wasticker", "", ignoreCase = true)
                    .replace(".zip", "", ignoreCase = true)
                    .replace("_", " ")
                    .trim()
                    .ifBlank { "Imported Stickers" }
            }

            if (publisherName.isNullOrBlank()) {
                publisherName = "Sticker Maker"
            }

            // 3. Resolve Tray Icon
            // Look for explicit tray file, or files named tray/cover/icon/0, or fallback to first image
            var trayEntry: Map.Entry<String, File>? = null
            if (explicitTrayName != null) {
                trayEntry = imageEntries.entries.firstOrNull { it.key == explicitTrayName }
            }
            if (trayEntry == null) {
                trayEntry = imageEntries.entries.firstOrNull { (name, _) ->
                    name.startsWith("tray") || name.startsWith("cover") || name.startsWith("icon") ||
                    name.startsWith("0.") || name.contains("tray")
                }
            }
            if (trayEntry == null && imageEntries.size > 3) {
                // Take first image as tray icon if more than 3 stickers exist
                trayEntry = imageEntries.entries.firstOrNull()
            }

            val finalIdentifier = UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)
            val newTrayFileName = "tray_${finalIdentifier}.webp"
            val destTrayFile = File(stickersDir, newTrayFileName)

            if (trayEntry != null) {
                val traySourceFile = trayEntry.value
                val isTrayWebP = trayEntry.key.endsWith(".webp")
                // Check if already 96x96 WebP and under 50KB
                if (isTrayWebP && traySourceFile.length() <= 50 * 1024) {
                    val bounds = getBitmapBounds(traySourceFile)
                    if (bounds.first == 96 && bounds.second == 96) {
                        traySourceFile.copyTo(destTrayFile, overwrite = true)
                    } else {
                        ImageUtils.convertToWebPFile(context, Uri.fromFile(traySourceFile), 96, "tray_${finalIdentifier}")
                    }
                } else {
                    ImageUtils.convertToWebPFile(context, Uri.fromFile(traySourceFile), 96, "tray_${finalIdentifier}")
                }
            }

            // If tray conversion failed or no tray entry found, create a fallback tray icon
            if (!destTrayFile.exists()) {
                val fallbackSource = imageEntries.values.firstOrNull()
                if (fallbackSource != null) {
                    ImageUtils.convertToWebPFile(context, Uri.fromFile(fallbackSource), 96, "tray_${finalIdentifier}")
                }
            }

            // 4. Resolve Sticker Files (exclude tray icon)
            val stickerCandidates = imageEntries.filter { it.key != trayEntry?.key }
            val stickerList = if (stickerCandidates.isNotEmpty()) stickerCandidates else imageEntries

            val processedStickers = mutableListOf<Sticker>()
            val sortedEntries = stickerList.entries.sortedWith(NaturalOrderComparator)

            for ((index, entry) in sortedEntries.withIndex()) {
                if (processedStickers.size >= 30) break // WhatsApp max limit

                val lowerName = entry.key
                val tempFile = entry.value

                val baseName = "sticker_${finalIdentifier}_${processedStickers.size + 1}"
                val targetFileName = "$baseName.webp"
                val destFile = File(stickersDir, targetFileName)

                // Resolve emojis for this specific sticker:
                // 1) From mapping tables (contents.json, pack.json, emojis.txt, metadata.txt)
                // 2) From embedded WebP EXIF metadata (the official WhatsApp WebP sticker spec)
                // 3) Fallback to distinct contextual reaction emojis (so stickers never share the identical emoji)
                val cleanName = lowerName.substringBeforeLast(".")
                val numOnly = lowerName.filter { it.isDigit() }
                var resolvedEmojis = emojiMap[lowerName]
                    ?: emojiMap[cleanName]
                    ?: (if (numOnly.isNotEmpty()) emojiMap[numOnly] ?: emojiMap["$numOnly.webp"] else null)
                    ?: emojiMap["line_$index"]

                var resolvedAccessibility = accessibilityMap[lowerName]
                    ?: accessibilityMap[cleanName]
                    ?: (if (numOnly.isNotEmpty()) accessibilityMap[numOnly] ?: accessibilityMap["$numOnly.webp"] else null)

                if (lowerName.endsWith(".webp")) {
                    val exifMeta = WebpExifHelper.extractMetadata(tempFile)
                    if (exifMeta != null) {
                        if (resolvedEmojis.isNullOrEmpty() && exifMeta.emojis.isNotEmpty()) {
                            resolvedEmojis = exifMeta.emojis
                            Log.d(TAG, "Extracted ${resolvedEmojis.size} emojis from WebP EXIF for $lowerName: $resolvedEmojis")
                        }
                        if (resolvedAccessibility.isNullOrBlank() && !exifMeta.accessibilityText.isNullOrBlank()) {
                            resolvedAccessibility = exifMeta.accessibilityText
                        }
                    }
                }

                val finalEmojis = resolvedEmojis?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_EMOJI_PALETTE[index % DEFAULT_EMOJI_PALETTE.size]

                val finalAccessibility = resolvedAccessibility?.takeIf { it.isNotBlank() }
                    ?: if (finalEmojis.isNotEmpty()) "Sticker expressing ${finalEmojis.joinToString(" ")}"
                    else "Sticker ${index + 1}"

                // Check if file is already an animated WebP
                if (isAnimatedWebP(tempFile) && tempFile.length() <= 500 * 1024) {
                    tempFile.copyTo(destFile, overwrite = true)
                    processedStickers.add(Sticker(targetFileName, emojis = finalEmojis, isAnimated = true, accessibilityText = finalAccessibility))
                    continue
                }

                // Check if file is animated GIF
                if (lowerName.endsWith(".gif")) {
                    val result = ImageUtils.convertToWebP(context, Uri.fromFile(tempFile), 512, baseName)
                    if (result != null && result.file.exists()) {
                        processedStickers.add(Sticker(result.file.name, emojis = finalEmojis, isAnimated = result.isAnimated, accessibilityText = finalAccessibility))
                        continue
                    }
                }

                // Check if already 512x512 WebP under 100KB
                if (lowerName.endsWith(".webp") && tempFile.length() <= 100 * 1024) {
                    val bounds = getBitmapBounds(tempFile)
                    if (bounds.first == 512 && bounds.second == 512) {
                        tempFile.copyTo(destFile, overwrite = true)
                        processedStickers.add(Sticker(targetFileName, emojis = finalEmojis, isAnimated = false, accessibilityText = finalAccessibility))
                        continue
                    }
                }

                // Convert to WhatsApp-compliant 512x512 WebP
                val result = ImageUtils.convertToWebP(context, Uri.fromFile(tempFile), 512, baseName)
                if (result != null && result.file.exists()) {
                    processedStickers.add(Sticker(result.file.name, emojis = finalEmojis, isAnimated = result.isAnimated, accessibilityText = finalAccessibility))
                }
            }

            if (processedStickers.isEmpty()) {
                Log.e(TAG, "No stickers could be successfully decoded or converted")
                destTrayFile.delete()
                return null
            }

            val finalPack = StickerPack(
                identifier = finalIdentifier,
                name = packName ?: "Imported Stickers",
                publisher = publisherName ?: "Sticker Maker",
                trayImageFileName = destTrayFile.name,
                stickers = processedStickers
            )

            StickerStorage.addPack(context, finalPack)
            Log.d(TAG, "Successfully imported pack: '${finalPack.name}' with ${finalPack.stickers.size} stickers")
            return finalPack
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import pack: ${e.message}", e)
            return null
        } finally {
            // Cleanup all temporary extracted files
            tempFiles.values.forEach { file ->
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Checks if a WebP file contains animation chunks (ANIM/ANMF or VP8X animation flag).
     */
    fun isAnimatedWebP(file: File): Boolean {
        if (!file.exists() || file.length() < 30) return false
        return try {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(minOf(file.length().toInt(), 2048))
                val read = input.read(buffer)
                buffer.copyOf(maxOf(0, read))
            }
            if (bytes.size < 12) return false
            val riff = String(bytes, 0, 4, Charsets.US_ASCII)
            val webp = String(bytes, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || webp != "WEBP") return false

            var i = 12
            while (i + 4 <= bytes.size) {
                val chunk = String(bytes, i, 4, Charsets.US_ASCII)
                if (chunk == "ANIM" || chunk == "ANMF") return true
                if (chunk == "VP8X" && i + 8 <= bytes.size) {
                    val flags = bytes[i + 8].toInt() and 0xFF
                    if ((flags and 0x02) != 0) return true
                }
                i++
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun getBitmapBounds(file: File): Pair<Int, Int> {
        if (file.name.endsWith(".webp", ignoreCase = true)) {
            val webpBounds = getWebpBounds(file)
            if (webpBounds != null && webpBounds.first > 0 && webpBounds.second > 0) {
                return webpBounds
            }
        }
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            Pair(options.outWidth, options.outHeight)
        } catch (_: Exception) {
            Pair(0, 0)
        }
    }

    /**
     * Parses width and height directly from the WebP container header (VP8X, VP8L, VP8).
     */
    private fun getWebpBounds(file: File): Pair<Int, Int>? {
        return try {
            if (!file.exists() || file.length() < 30) return null
            val bytes = file.inputStream().use { input ->
                val buf = ByteArray(64)
                val read = input.read(buf)
                buf.copyOf(maxOf(0, read))
            }
            if (bytes.size < 16) return null
            val riff = String(bytes, 0, 4, Charsets.US_ASCII)
            val webp = String(bytes, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || webp != "WEBP") return null

            val fourCC = String(bytes, 12, 4, Charsets.US_ASCII)
            when (fourCC) {
                "VP8X" -> {
                    if (bytes.size >= 30) {
                        val w = (bytes[24].toInt() and 0xFF) or
                                ((bytes[25].toInt() and 0xFF) shl 8) or
                                ((bytes[26].toInt() and 0xFF) shl 16)
                        val h = (bytes[27].toInt() and 0xFF) or
                                ((bytes[28].toInt() and 0xFF) shl 8) or
                                ((bytes[29].toInt() and 0xFF) shl 16)
                        Pair(w + 1, h + 1)
                    } else null
                }
                "VP8L" -> {
                    if (bytes.size >= 25 && bytes[20] == 0x2F.toByte()) {
                        val b1 = bytes[21].toInt() and 0xFF
                        val b2 = bytes[22].toInt() and 0xFF
                        val b3 = bytes[23].toInt() and 0xFF
                        val b4 = bytes[24].toInt() and 0xFF
                        val w = (b1 or ((b2 and 0x3F) shl 8)) + 1
                        val h = (((b2 and 0xC0) shr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10)) + 1
                        Pair(w, h)
                    } else null
                }
                "VP8 " -> {
                    if (bytes.size >= 30) {
                        val w = ((bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)) and 0x3FFF
                        val h = ((bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)) and 0x3FFF
                        Pair(w, h)
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getDisplayNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.let { File(it).name }
    }

    /**
     * Natural comparator to sort filenames like "1.webp", "2.webp", "10.webp" in proper numeric order.
     */
    private val NaturalOrderComparator = Comparator<Map.Entry<String, File>> { e1, e2 ->
        val s1 = e1.key
        val s2 = e2.key
        val n1 = s1.filter { it.isDigit() }.toIntOrNull()
        val n2 = s2.filter { it.isDigit() }.toIntOrNull()
        if (n1 != null && n2 != null) {
            n1.compareTo(n2)
        } else {
            s1.compareTo(s2)
        }
    }

    /**
     * Parses alternative emoji metadata files found in various third-party .wastickers tools:
     * - emojis.txt / metadata.txt / metadata.tsv / stickers.txt / tags.txt
     * - emojis.json / metadata.json / stickers.json
     */
    private fun parseExtraEmojiMappings(
        textContents: Map<String, String>,
        emojiMap: MutableMap<String, List<String>>
    ) {
        // 1. Check JSON-based emoji files
        for (jsonName in listOf("emojis.json", "metadata.json", "stickers.json")) {
            textContents[jsonName]?.let { jsonStr ->
                try {
                    val element = JsonParser.parseString(jsonStr)
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        for ((key, value) in obj.entrySet()) {
                            val lowerKey = key.lowercase(Locale.ROOT)
                            val emojis = mutableListOf<String>()
                            if (value.isJsonArray) {
                                value.asJsonArray.forEach { e ->
                                    if (e.isJsonPrimitive) emojis.addAll(ImageUtils.splitIntoEmojis(e.asString))
                                }
                            } else if (value.isJsonPrimitive) {
                                emojis.addAll(ImageUtils.splitIntoEmojis(value.asString))
                            }
                            if (emojis.isNotEmpty()) {
                                emojiMap[lowerKey] = emojis
                                emojiMap[lowerKey.substringBeforeLast(".")] = emojis
                            }
                        }
                    } else if (element.isJsonArray) {
                        element.asJsonArray.forEach { item ->
                            if (item.isJsonObject) {
                                val itemObj = item.asJsonObject
                                val fileKey = itemObj.get("file")?.asString
                                    ?: itemObj.get("image_file")?.asString
                                    ?: itemObj.get("name")?.asString
                                val emojis = mutableListOf<String>()
                                if (itemObj.has("emojis")) {
                                    val eElem = itemObj.get("emojis")
                                    if (eElem.isJsonArray) {
                                        eElem.asJsonArray.forEach { e -> emojis.addAll(ImageUtils.splitIntoEmojis(e.asString)) }
                                    } else if (eElem.isJsonPrimitive) {
                                        emojis.addAll(ImageUtils.splitIntoEmojis(eElem.asString))
                                    }
                                }
                                if (fileKey != null && emojis.isNotEmpty()) {
                                    val lowerKey = File(fileKey).name.lowercase(Locale.ROOT)
                                    emojiMap[lowerKey] = emojis
                                    emojiMap[lowerKey.substringBeforeLast(".")] = emojis
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing $jsonName: ${e.message}")
                }
            }
        }

        // 2. Check line-based text files (emojis.txt, metadata.txt, metadata.tsv, etc.)
        for (txtName in listOf("emojis.txt", "emoji.txt", "metadata.txt", "metadata.tsv", "stickers.txt", "tags.txt")) {
            textContents[txtName]?.let { text ->
                var lineIndex = 0
                text.lines().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isNotEmpty() && !line.startsWith("#")) {
                        if (line.contains(":") || line.contains("=") || line.contains("\t") || (line.contains(",") && (line.contains(".webp") || line.contains(".png")))) {
                            val delimiter = when {
                                line.contains(":") -> ":"
                                line.contains("=") -> "="
                                line.contains("\t") -> "\t"
                                else -> ","
                            }
                            val key = line.substringBefore(delimiter).trim().lowercase(Locale.ROOT)
                            val rest = line.substringAfter(delimiter).trim()
                            val emojis = ImageUtils.splitIntoEmojis(rest).ifEmpty {
                                rest.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
                            }
                            if (emojis.isNotEmpty()) {
                                emojiMap[key] = emojis
                                emojiMap[key.substringBeforeLast(".")] = emojis
                            }
                        } else {
                            val emojis = ImageUtils.splitIntoEmojis(line)
                            if (emojis.isNotEmpty()) {
                                emojiMap["line_$lineIndex"] = emojis
                            }
                        }
                        lineIndex++
                    }
                }
            }
        }
    }

    /**
     * Curated palette of expressive, high-contrast emoji pairs used as fallbacks
     * when stickers lack embedded metadata, ensuring every sticker receives a distinct reaction.
     */
    private val DEFAULT_EMOJI_PALETTE = listOf(
        listOf("✨", "💫"),
        listOf("❤️", "🥰"),
        listOf("😂", "🤣"),
        listOf("🔥", "⚡"),
        listOf("👍", "👏"),
        listOf("🎉", "🥳"),
        listOf("😎", "🕶️"),
        listOf("😍", "💖"),
        listOf("🤩", "⭐"),
        listOf("🙌", "💯"),
        listOf("🐶", "🐾"),
        listOf("🐱", "😺"),
        listOf("🌸", "🌺"),
        listOf("🍕", "🍔"),
        listOf("☕", "🍵"),
        listOf("🚀", "🛸"),
        listOf("💡", "🧠"),
        listOf("💪", "🏆"),
        listOf("🌈", "☀️"),
        listOf("🎈", "🎁"),
        listOf("😴", "💤"),
        listOf("😭", "🥺"),
        listOf("😜", "🤪"),
        listOf("😇", "🕊️"),
        listOf("🤗", "🫂"),
        listOf("🎶", "🎵"),
        listOf("🎨", "🖌️"),
        listOf("🌟", "✨"),
        listOf("✌️", "🤟"),
        listOf("💃", "🕺")
    )
}

