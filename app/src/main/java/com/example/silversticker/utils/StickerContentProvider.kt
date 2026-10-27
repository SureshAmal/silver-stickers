package com.example.silversticker.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

class StickerContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "StickerProvider"
        
        private const val METADATA = 1
        private const val METADATA_ID = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4
        private const val STICKERS_ASSET_DIRECT = 5

        // WhatsApp Standard Column Names - Exact match required
        private const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        private const val STICKER_PACK_NAME = "sticker_pack_name"
        private const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        private const val STICKER_PACK_ICON = "sticker_pack_icon"
        private const val ANDROID_PLAY_STORE_LINK = "android_play_store_link"
        private const val IOS_APP_STORE_LINK = "ios_app_store_link"
        private const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        private const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        private const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        private const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        private const val IMAGE_DATA_VERSION = "image_data_version"
        private const val AVOID_CACHE = "avoid_cache"
        private const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        private const val STICKER_FILE_NAME = "sticker_file_name"
        private const val STICKER_EMOJI = "sticker_emoji"
        private const val STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"
    }

    private var matcher: UriMatcher = UriMatcher(UriMatcher.NO_MATCH)
    private var authority: String = ""

    private fun getAuthority(): String {
        if (authority.isBlank()) {
            authority = "${context?.packageName}.sticker_provider"
        }
        return authority
    }

    private fun ensureMatcher(): UriMatcher {
        val auth = getAuthority()
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(auth, "metadata", METADATA)
            addURI(auth, "metadata/*", METADATA_ID)
            addURI(auth, "stickers/*", STICKERS)
            addURI(auth, "stickers_asset/*/*", STICKERS_ASSET)
            addURI(auth, "stickers_asset/*", STICKERS_ASSET)
            addURI(auth, "*", STICKERS_ASSET_DIRECT)
        }
        return matcher
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            authority = "${ctx.packageName}.sticker_provider"
            ensureMatcher()
            StickerStorage.init(ctx)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        context?.let { StickerStorage.init(it) }
        val m = ensureMatcher()
        val match = m.match(uri)
        Log.d(TAG, "Query received. URI: $uri, Match: $match")
        
        return when (match) {
            METADATA -> getAllPacksCursor(projection)
            METADATA_ID -> getPackCursor(uri.lastPathSegment?.trim() ?: "", projection)
            STICKERS -> getStickersCursor(uri.lastPathSegment?.trim() ?: "", projection)
            else -> {
                Log.w(TAG, "No match for query URI: $uri")
                MatrixCursor(arrayOf("_id"))
            }
        }
    }

    private fun getAllPacksCursor(projection: Array<out String>?): Cursor {
        val allColumns = arrayOf(
            STICKER_PACK_IDENTIFIER, STICKER_PACK_NAME, STICKER_PACK_PUBLISHER, STICKER_PACK_ICON,
            ANDROID_PLAY_STORE_LINK, IOS_APP_STORE_LINK, PUBLISHER_EMAIL, PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE, IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_STICKER_PACK
        )
        val columns = if (projection != null && projection.isNotEmpty()) projection else allColumns
        val cursor = MatrixCursor(columns)
        
        val packs = StickerStorage.getAllPacks(context)
        Log.d(TAG, "getAllPacksCursor: returning ${packs.size} packs")
        packs.forEach { pack ->
            val isAnimated = if (pack.stickers.any { it.isAnimated }) 1 else 0
            val row = arrayOfNulls<Any>(columns.size)
            for (i in columns.indices) {
                when (columns[i]) {
                    STICKER_PACK_IDENTIFIER -> row[i] = pack.identifier
                    STICKER_PACK_NAME -> row[i] = pack.name
                    STICKER_PACK_PUBLISHER -> row[i] = pack.publisher
                    STICKER_PACK_ICON -> row[i] = pack.trayImageFileName
                    ANDROID_PLAY_STORE_LINK -> row[i] = ""
                    IOS_APP_STORE_LINK -> row[i] = ""
                    PUBLISHER_EMAIL -> row[i] = ""
                    PUBLISHER_WEBSITE -> row[i] = ""
                    PRIVACY_POLICY_WEBSITE -> row[i] = ""
                    LICENSE_AGREEMENT_WEBSITE -> row[i] = ""
                    IMAGE_DATA_VERSION -> row[i] = "1"
                    AVOID_CACHE -> row[i] = 0
                    ANIMATED_STICKER_PACK -> row[i] = isAnimated
                    else -> row[i] = null
                }
            }
            cursor.addRow(row)
        }
        context?.let { cursor.setNotificationUri(it.contentResolver, Uri.parse("content://${getAuthority()}/metadata")) }
        return cursor
    }

    private fun getPackCursor(identifier: String, projection: Array<out String>?): Cursor {
        val allColumns = arrayOf(
            STICKER_PACK_IDENTIFIER, STICKER_PACK_NAME, STICKER_PACK_PUBLISHER, STICKER_PACK_ICON,
            ANDROID_PLAY_STORE_LINK, IOS_APP_STORE_LINK, PUBLISHER_EMAIL, PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE, IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_STICKER_PACK
        )
        val columns = if (projection != null && projection.isNotEmpty()) projection else allColumns
        val cursor = MatrixCursor(columns)
        
        val pack = StickerStorage.getPack(identifier, context)
        Log.d(TAG, "getPackCursor: requested '$identifier', found: ${pack != null}")
        pack?.let { p ->
            val isAnimated = if (p.stickers.any { it.isAnimated }) 1 else 0
            val row = arrayOfNulls<Any>(columns.size)
            for (i in columns.indices) {
                when (columns[i]) {
                    STICKER_PACK_IDENTIFIER -> row[i] = p.identifier
                    STICKER_PACK_NAME -> row[i] = p.name
                    STICKER_PACK_PUBLISHER -> row[i] = p.publisher
                    STICKER_PACK_ICON -> row[i] = p.trayImageFileName
                    ANDROID_PLAY_STORE_LINK -> row[i] = ""
                    IOS_APP_STORE_LINK -> row[i] = ""
                    PUBLISHER_EMAIL -> row[i] = ""
                    PUBLISHER_WEBSITE -> row[i] = ""
                    PRIVACY_POLICY_WEBSITE -> row[i] = ""
                    LICENSE_AGREEMENT_WEBSITE -> row[i] = ""
                    IMAGE_DATA_VERSION -> row[i] = "1"
                    AVOID_CACHE -> row[i] = 0
                    ANIMATED_STICKER_PACK -> row[i] = isAnimated
                    else -> row[i] = null
                }
            }
            cursor.addRow(row)
        }
        context?.let { cursor.setNotificationUri(it.contentResolver, Uri.parse("content://${getAuthority()}/metadata/$identifier")) }
        return cursor
    }

    private fun getStickersCursor(identifier: String, projection: Array<out String>?): Cursor {
        val defaultColumns = arrayOf(STICKER_FILE_NAME, STICKER_EMOJI, STICKER_ACCESSIBILITY_TEXT)
        val columns = if (projection != null && projection.isNotEmpty()) projection else defaultColumns
        val cursor = MatrixCursor(columns)
        val pack = StickerStorage.getPack(identifier, context)
        
        Log.d(TAG, "Getting stickers for pack: '$identifier'. Found: ${pack?.stickers?.size ?: 0}")
        
        pack?.stickers?.forEachIndexed { index, sticker ->
            val emojis = if (sticker.emojis.isNotEmpty()) sticker.emojis.take(3).joinToString(",") else "😊"
            val accessibilityText = sticker.accessibilityText?.takeIf { it.isNotBlank() }
                ?: if (sticker.emojis.isNotEmpty()) "Sticker expressing ${sticker.emojis.joinToString(" ")}"
                else "Sticker ${index + 1}"
            
            val row = arrayOfNulls<Any>(columns.size)
            for (i in columns.indices) {
                when (columns[i]) {
                    STICKER_FILE_NAME -> row[i] = sticker.imageFileName
                    STICKER_EMOJI -> row[i] = emojis
                    STICKER_ACCESSIBILITY_TEXT -> row[i] = accessibilityText
                    else -> row[i] = null
                }
            }
            cursor.addRow(row)
        }
        context?.let { cursor.setNotificationUri(it.contentResolver, Uri.parse("content://${getAuthority()}/stickers/$identifier")) }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val auth = getAuthority()
        val m = ensureMatcher()
        val match = m.match(uri)
        return when (match) {
            METADATA -> "vnd.android.cursor.dir/vnd.$auth.metadata"
            METADATA_ID -> "vnd.android.cursor.item/vnd.$auth.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$auth.stickers"
            STICKERS_ASSET, STICKERS_ASSET_DIRECT -> {
                val fileName = uri.lastPathSegment ?: ""
                if (fileName.endsWith(".png", ignoreCase = true)) "image/png" else "image/webp"
            }
            else -> {
                val fileName = uri.lastPathSegment ?: ""
                if (fileName.endsWith(".png", ignoreCase = true)) "image/png"
                else if (fileName.endsWith(".webp", ignoreCase = true)) "image/webp"
                else null
            }
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return android.content.res.AssetFileDescriptor(pfd, 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: throw FileNotFoundException("Context is null")
        val rawFileName = uri.lastPathSegment ?: throw FileNotFoundException("No filename in URI: $uri")
        val fileName = Uri.decode(rawFileName)
        val stickersDir = StickerStorage.getStickersDir(ctx)
        
        var file = File(stickersDir, fileName)
        if (!file.exists()) {
            file = File(stickersDir, rawFileName)
        }
        if (!file.exists()) {
            file = File(ctx.filesDir, fileName)
        }
        
        Log.d(TAG, "openFile: Requested: $fileName (raw: $rawFileName), Path: ${file.absolutePath}, Exists: ${file.exists()}, Size: ${file.length()}")
        
        if (!file.exists() || file.length() == 0L) {
            throw FileNotFoundException("Sticker file not found or empty: ${file.absolutePath}")
        }
        
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
