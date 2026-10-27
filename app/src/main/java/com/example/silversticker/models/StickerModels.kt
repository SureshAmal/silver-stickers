package com.example.silversticker.models

import android.content.Context
import android.net.Uri
import com.example.silversticker.utils.StickerStorage
import java.io.File

data class Sticker(
    val imageFileName: String, // Filename in stickers directory
    val emojis: List<String> = emptyList(),
    val isAnimated: Boolean = false,
    val accessibilityText: String? = null
) {
    /**
     * URI for WhatsApp (External)
     */
    fun getExternalUri(context: Context): Uri {
        return Uri.parse("content://${context.packageName}.sticker_provider/$imageFileName")
    }

    /**
     * File for App UI (Internal)
     */
    fun getInternalFile(context: Context): File {
        return File(StickerStorage.getStickersDir(context), imageFileName)
    }
}

data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFileName: String, // Filename in stickers directory
    val stickers: List<Sticker> = emptyList()
) {
    /**
     * URI for WhatsApp (External)
     */
    fun getTrayExternalUri(context: Context): Uri {
        return Uri.parse("content://${context.packageName}.sticker_provider/$trayImageFileName")
    }

    /**
     * File for App UI (Internal)
     */
    fun getTrayInternalFile(context: Context): File {
        return File(StickerStorage.getStickersDir(context), trayImageFileName)
    }
}
