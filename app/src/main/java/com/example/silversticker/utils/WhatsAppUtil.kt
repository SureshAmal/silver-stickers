package com.example.silversticker.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.silversticker.models.StickerPack
import java.io.File

object WhatsAppUtil {
    private const val TAG = "WhatsAppUtil"
    private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

    fun createAddStickerPackIntent(context: Context, pack: StickerPack, targetPackage: String): Intent? {
        val authority = "${context.packageName}.sticker_provider"
        
        val intent = Intent().apply {
            action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
            `package` = targetPackage
            putExtra(EXTRA_STICKER_PACK_ID, pack.identifier)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, authority)
            putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
            
            // Critical for Android 11+ (API 30+)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            val packageManager = context.packageManager
            if (packageManager.getPackageInfo(targetPackage, PackageManager.GET_ACTIVITIES) != null) {
                intent
            } else {
                Log.e(TAG, "Target package $targetPackage not found")
                null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "WhatsApp not installed: $targetPackage")
            null
        }
    }

    fun isWhatsAppInstalled(context: Context, targetPackage: String): Boolean {
        return try {
            val packageManager = context.packageManager
            packageManager.getPackageInfo(targetPackage, PackageManager.GET_ACTIVITIES) != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    data class ValidationCheck(
        val title: String,
        val passed: Boolean,
        val detail: String
    )

    data class ValidationReport(
        val isValid: Boolean,
        val checks: List<ValidationCheck>
    )

    fun validatePackForWhatsApp(context: Context, pack: StickerPack): ValidationReport {
        val checks = mutableListOf<ValidationCheck>()
        val stickersDir = StickerStorage.getStickersDir(context)

        // 1. Pack name and publisher
        val hasMetadata = pack.name.isNotBlank() && pack.publisher.isNotBlank()
        checks.add(
            ValidationCheck(
                title = "Pack Metadata",
                passed = hasMetadata,
                detail = if (hasMetadata) "\"${pack.name}\" by ${pack.publisher}" else "Name or publisher is blank"
            )
        )

        // 2. Sticker count (3 to 30)
        val count = pack.stickers.size
        val countValid = count in 3..30
        checks.add(
            ValidationCheck(
                title = "Sticker Count (3–30)",
                passed = countValid,
                detail = "$count sticker(s) in pack" + if (!countValid) " (Requires min 3, max 30)" else ""
            )
        )

        // 3. Tray Icon validation
        val trayFile = File(stickersDir, pack.trayImageFileName)
        val trayExists = trayFile.exists()
        val traySizeKb = if (trayExists) trayFile.length() / 1024L else 0L
        val trayUnderLimit = traySizeKb <= 50
        val trayPassed = trayExists && trayUnderLimit
        checks.add(
            ValidationCheck(
                title = "Tray Icon (96x96, <50KB)",
                passed = trayPassed,
                detail = if (!trayExists) "File missing: ${pack.trayImageFileName}"
                else "${traySizeKb}KB (${if (trayUnderLimit) "Within limit" else "Exceeds 50KB"})"
            )
        )

        // 4. Individual Sticker limits (Static <100KB, Animated <500KB)
        var allStickersValid = true
        var oversizeCount = 0
        var missingCount = 0
        var missingEmojiCount = 0

        for (sticker in pack.stickers) {
            val file = File(stickersDir, sticker.imageFileName)
            if (!file.exists()) {
                missingCount++
                allStickersValid = false
            } else {
                val sizeKb = file.length() / 1024L
                val maxAllowed = if (sticker.isAnimated) 500 else 100
                if (sizeKb > maxAllowed) {
                    oversizeCount++
                    allStickersValid = false
                }
            }
            if (sticker.emojis.isEmpty() || sticker.emojis.size > 3) {
                missingEmojiCount++
            }
        }

        checks.add(
            ValidationCheck(
                title = "Sticker File Sizes (WebP)",
                passed = allStickersValid,
                detail = if (missingCount > 0) "$missingCount file(s) missing on disk"
                else if (oversizeCount > 0) "$oversizeCount sticker(s) exceed WhatsApp file size limit"
                else "All ${pack.stickers.size} stickers within size limits"
            )
        )

        // 5. Emoji tagging
        checks.add(
            ValidationCheck(
                title = "Emoji Tags (1–3 per sticker)",
                passed = missingEmojiCount == 0,
                detail = if (missingEmojiCount == 0) "All stickers have 1-3 emojis assigned"
                else "$missingEmojiCount sticker(s) lack assigned emojis"
            )
        )

        val isOverallValid = checks.all { it.passed }
        return ValidationReport(isValid = isOverallValid, checks = checks)
    }
}
