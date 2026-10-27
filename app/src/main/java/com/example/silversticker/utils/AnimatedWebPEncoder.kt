package com.example.silversticker.utils

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin animated WebP muxer.
 * Encodes individual Bitmap frames via Android's built-in Bitmap.compress,
 * then wraps them into a RIFF/ANIM/ANMF animated WebP container.
 */
object AnimatedWebPEncoder {

    private const val TAG = "AnimatedWebPEncoder"

    data class Frame(val bitmap: Bitmap, val durationMs: Int)

    fun encode(frames: List<Frame>, outputFile: File, quality: Int = 80, loopCount: Int = 0): Boolean {
        if (frames.isEmpty()) return false
        val w = frames[0].bitmap.width
        val h = frames[0].bitmap.height

        return try {
            val encodedFrames = frames.map { frame ->
                val webp = compressFrame(frame.bitmap, quality)
                val bitstream = extractBitstream(webp) ?: return false
                bitstream to frame.durationMs
            }

            val buf = ByteArrayOutputStream()
            buf.write("RIFF".toByteArray()); buf.write(ByteArray(4)); buf.write("WEBP".toByteArray())
            writeVP8X(buf, w, h)
            writeANIM(buf, loopCount)
            for ((data, dur) in encodedFrames) writeANMF(buf, w, h, dur, data)

            val bytes = buf.toByteArray()
            val size = bytes.size - 8
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array()
                .copyInto(bytes, 4)

            FileOutputStream(outputFile).use { it.write(bytes) }
            Log.d(TAG, "Encoded ${frames.size} frames → ${outputFile.length()} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed: ${e.message}", e)
            false
        }
    }

    private fun compressFrame(bitmap: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bos)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, bos)
        }
        return bos.toByteArray()
    }

    /** Strip RIFF/WEBP header + VP8X, keep only ALPH/VP8/VP8L chunks. */
    private fun extractBitstream(webp: ByteArray): ByteArray? {
        if (webp.size < 12) return null
        if (String(webp, 0, 4) != "RIFF" || String(webp, 8, 4) != "WEBP") return null

        val result = ByteArrayOutputStream()
        var off = 12
        while (off + 8 <= webp.size) {
            val cc = String(webp, off, 4)
            val sz = ByteBuffer.wrap(webp, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val total = 8 + sz + (sz % 2)
            if (cc == "VP8 " || cc == "VP8L" || cc == "ALPH") {
                val end = (off + total).coerceAtMost(webp.size)
                result.write(webp, off, end - off)
            }
            off += total
        }
        return result.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun writeVP8X(out: ByteArrayOutputStream, width: Int, height: Int) {
        out.write("VP8X".toByteArray())
        writeLE32(out, 10)
        out.write(0x12)           // flags: Alpha + Animation
        out.write(ByteArray(3))   // reserved
        write24(out, width - 1)
        write24(out, height - 1)
    }

    private fun writeANIM(out: ByteArrayOutputStream, loopCount: Int) {
        out.write("ANIM".toByteArray())
        writeLE32(out, 6)
        out.write(ByteArray(4))   // bg color (transparent)
        out.write(loopCount and 0xFF)
        out.write((loopCount shr 8) and 0xFF)
    }

    private fun writeANMF(out: ByteArrayOutputStream, w: Int, h: Int, durMs: Int, data: ByteArray) {
        out.write("ANMF".toByteArray())
        writeLE32(out, 16 + data.size)
        write24(out, 0)           // x offset
        write24(out, 0)           // y offset
        write24(out, w - 1)
        write24(out, h - 1)
        write24(out, durMs)
        out.write(0x02)           // alpha-blend, do not dispose
        out.write(data)
        if (data.size % 2 != 0) out.write(0) // padding
    }

    private fun writeLE32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun write24(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF)
    }
}
