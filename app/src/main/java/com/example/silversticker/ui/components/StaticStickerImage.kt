package com.example.silversticker.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val thumbnailCache = LruCache<String, ImageBitmap>(64)

@Composable
fun StaticStickerImage(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    targetSizePx: Int = 192
) {
    val path = file?.absolutePath
    val lastModified = file?.lastModified()
    val cacheKey = "$path:$lastModified:$targetSizePx"
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = cacheKey) {
        synchronized(thumbnailCache) {
            thumbnailCache.get(cacheKey)
        }?.let {
            value = it
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            if (file == null || !file.exists()) return@withContext null
            decodeSampledBitmap(file, targetSizePx)?.asImageBitmap()?.also {
                synchronized(thumbnailCache) {
                    thumbnailCache.put(cacheKey, it)
                }
            }
        }
    }

    image?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}

private fun decodeSampledBitmap(file: File, targetSizePx: Int) = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)

    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    val sampleSize = Integer.highestOneBit((maxDimension / targetSizePx).coerceAtLeast(1))

    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
    )
} catch (_: Exception) {
    null
}
