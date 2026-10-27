package com.example.silversticker.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.silversticker.models.StickerPack
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object StickerStorage {
    private const val TAG = "StickerStorage"
    private const val STORAGE_FILE = "sticker_packs.json"
    private const val STICKERS_DIR = "stickers"
    private val gson = Gson()
    private val packs = mutableMapOf<String, StickerPack>()
    private var lastLoadedTimestamp: Long = 0L

    @Synchronized
    fun init(context: Context) {
        ensureLoaded(context)
        getStickersDir(context).mkdirs()
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        val file = File(context.filesDir, STORAGE_FILE)
        if (!file.exists()) {
            return
        }
        val fileModTime = file.lastModified()
        if (fileModTime > lastLoadedTimestamp || packs.isEmpty()) {
            try {
                val json = file.readText()
                Log.d(TAG, "Loading JSON from ${file.absolutePath}")
                val type = object : TypeToken<Map<String, StickerPack>>() {}.type
                val loaded: Map<String, StickerPack>? = gson.fromJson(json, type)
                if (loaded != null) {
                    packs.clear()
                    packs.putAll(loaded)
                    lastLoadedTimestamp = fileModTime
                    Log.d(TAG, "Successfully loaded ${packs.size} packs (timestamp: $lastLoadedTimestamp)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sticker packs", e)
            }
        }
    }

    fun getStickersDir(context: Context): File {
        return File(context.filesDir, STICKERS_DIR)
    }

    @Synchronized
    private fun save(context: Context) {
        try {
            val file = File(context.filesDir, STORAGE_FILE)
            val json = gson.toJson(packs)
            file.writeText(json)
            lastLoadedTimestamp = file.lastModified()
            Log.d(TAG, "Saved ${packs.size} packs to ${file.absolutePath}")
            
            // Notify WhatsApp and the system that sticker metadata has been updated
            val authority = "${context.packageName}.sticker_provider"
            context.contentResolver.notifyChange(Uri.parse("content://$authority/metadata"), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sticker packs", e)
        }
    }

    @Synchronized
    fun addPack(context: Context, pack: StickerPack) {
        ensureLoaded(context)
        packs[pack.identifier] = pack
        save(context)
    }

    @Synchronized
    fun removePack(context: Context, identifier: String) {
        ensureLoaded(context)
        val pack = packs[identifier] ?: packs.values.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
        if (pack != null) {
            val dir = getStickersDir(context)
            File(dir, pack.trayImageFileName).delete()
            pack.stickers.forEach { 
                File(dir, it.imageFileName).delete() 
                File(dir, it.imageFileName.replace(".webp", ".gif")).delete()
            }
            packs.remove(pack.identifier)
            save(context)
        }
    }

    @Synchronized
    fun getPack(identifier: String, context: Context? = null): StickerPack? {
        if (context != null) {
            ensureLoaded(context)
        }
        var pack = packs[identifier]
        if (pack == null) {
            pack = packs.values.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
        }
        return pack
    }

    @Synchronized
    fun getAllPacks(context: Context? = null): List<StickerPack> {
        if (context != null) {
            ensureLoaded(context)
        }
        return packs.values.toList().sortedByDescending { it.name }
    }
}
