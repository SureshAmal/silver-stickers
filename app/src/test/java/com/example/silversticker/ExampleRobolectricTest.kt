package com.example.silversticker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.silversticker.models.Sticker
import com.example.silversticker.models.StickerPack
import com.example.silversticker.utils.WhatsAppUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read app_name string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SilverSticker", appName)
  }

  @Test
  fun `validate WhatsApp pack validation logic`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pack = StickerPack(
      identifier = "test_pack",
      name = "Test Pack",
      publisher = "Tester",
      trayImageFileName = "tray.webp",
      stickers = listOf(
        Sticker("s1.webp", listOf("😊")),
        Sticker("s2.webp", listOf("🎉")),
        Sticker("s3.webp", listOf("🚀"))
      )
    )
    val report = WhatsAppUtil.validatePackForWhatsApp(context, pack)
    assertNotNull(report)
    assertEquals(5, report.checks.size)
  }

  @Test
  fun `importPack parses wastickers archive with title and author txt`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    
    val wastickersFile = java.io.File(context.cacheDir, "test_stickers.wastickers")
    java.util.zip.ZipOutputStream(java.io.FileOutputStream(wastickersFile)).use { zos ->
      // title.txt
      zos.putNextEntry(java.util.zip.ZipEntry("title.txt"))
      zos.write("My Wastickers Pack".toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // author.txt
      zos.putNextEntry(java.util.zip.ZipEntry("author.txt"))
      zos.write("Suresh Amal".toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // tray.png
      val trayBmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
      val trayBytes = java.io.ByteArrayOutputStream().also {
        trayBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
      }.toByteArray()
      zos.putNextEntry(java.util.zip.ZipEntry("tray.png"))
      zos.write(trayBytes)
      zos.closeEntry()

      // 3 sticker images
      for (i in 1..3) {
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also {
          bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
        }.toByteArray()
        zos.putNextEntry(java.util.zip.ZipEntry("$i.png"))
        zos.write(bytes)
        zos.closeEntry()
      }
    }

    val uri = android.net.Uri.fromFile(wastickersFile)
    val imported = com.example.silversticker.utils.ZipUtils.importPack(context, uri)

    assertNotNull(imported)
    assertEquals("My Wastickers Pack", imported!!.name)
    assertEquals("Suresh Amal", imported.publisher)
    assertEquals(3, imported.stickers.size)
    // Verify fallback assigns distinct emojis rather than identical "😊" for every sticker
    val distinctEmojis = imported.stickers.map { it.emojis }.distinct()
    org.junit.Assert.assertTrue("Stickers should receive distinct emojis", distinctEmojis.size > 1)
  }

  private fun createSampleWebpBytes(): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    bos.write("RIFF".toByteArray(Charsets.US_ASCII))
    bos.write(byteArrayOf(16, 0, 0, 0))
    bos.write("WEBP".toByteArray(Charsets.US_ASCII))
    bos.write("VP8 ".toByteArray(Charsets.US_ASCII))
    bos.write(byteArrayOf(4, 0, 0, 0))
    bos.write(byteArrayOf(0, 0, 0, 0))
    return bos.toByteArray()
  }

  @Test
  fun `WebpExifHelper accurately injects and extracts WhatsApp sticker metadata`() {
    val webpBytes = createSampleWebpBytes()

    val originalMetadata = com.example.silversticker.utils.WebpMetadata(
      packId = "cool_pack_123",
      packName = "Cool Memes",
      publisher = "Amal",
      emojis = listOf("🔥", "⚡", "😎")
    )

    val injectedBytes = com.example.silversticker.utils.WebpExifHelper.injectMetadata(webpBytes, originalMetadata)
    val extracted = com.example.silversticker.utils.WebpExifHelper.extractMetadata(injectedBytes)

    assertNotNull(extracted)
    assertEquals("Cool Memes", extracted!!.packName)
    assertEquals("Amal", extracted.publisher)
    assertEquals(listOf("🔥", "⚡", "😎"), extracted.emojis)
  }

  @Test
  fun `importPack extracts distinct per-sticker emojis from embedded WebP EXIF metadata`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val wastickersFile = java.io.File(context.cacheDir, "exif_stickers.wastickers")

    val expectedEmojis = listOf(
      listOf("😂", "🤣"),
      listOf("❤️", "🥰"),
      listOf("🔥", "⚡")
    )

    java.util.zip.ZipOutputStream(java.io.FileOutputStream(wastickersFile)).use { zos ->
      // title.txt
      zos.putNextEntry(java.util.zip.ZipEntry("title.txt"))
      zos.write("EXIF Pack".toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // tray.webp
      val trayBytes = createSampleWebpBytes()
      zos.putNextEntry(java.util.zip.ZipEntry("tray.webp"))
      zos.write(trayBytes)
      zos.closeEntry()

      // 3 WebP stickers with distinct embedded EXIF emojis
      for (i in 1..3) {
        val rawWebp = createSampleWebpBytes()
        val meta = com.example.silversticker.utils.WebpMetadata(
          packId = "exif_pack",
          packName = "EXIF Pack",
          publisher = "EXIF Author",
          emojis = expectedEmojis[i - 1]
        )
        val withExif = com.example.silversticker.utils.WebpExifHelper.injectMetadata(rawWebp, meta)

        zos.putNextEntry(java.util.zip.ZipEntry("sticker_$i.webp"))
        zos.write(withExif)
        zos.closeEntry()
      }
    }

    val uri = android.net.Uri.fromFile(wastickersFile)
    val imported = com.example.silversticker.utils.ZipUtils.importPack(context, uri)

    assertNotNull(imported)
    assertEquals(3, imported!!.stickers.size)
    assertEquals(listOf("😂", "🤣"), imported.stickers[0].emojis)
    assertEquals(listOf("❤️", "🥰"), imported.stickers[1].emojis)
    assertEquals(listOf("🔥", "⚡"), imported.stickers[2].emojis)
  }

  @Test
  fun `importPack parses emojis txt mapping file in wastickers archive`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val wastickersFile = java.io.File(context.cacheDir, "txt_stickers.wastickers")

    java.util.zip.ZipOutputStream(java.io.FileOutputStream(wastickersFile)).use { zos ->
      // title.txt & author.txt
      zos.putNextEntry(java.util.zip.ZipEntry("title.txt"))
      zos.write("Text Mapping Pack".toByteArray(Charsets.UTF_8))
      zos.closeEntry()
      zos.putNextEntry(java.util.zip.ZipEntry("author.txt"))
      zos.write("Author Name".toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // emojis.txt mapping
      val emojisContent = "1.png: 🎉, 🥳\n2.png: 🚀\n3.png: 🐶, 🐾\n"
      zos.putNextEntry(java.util.zip.ZipEntry("emojis.txt"))
      zos.write(emojisContent.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // tray.png
      val trayBmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
      val trayBytes = java.io.ByteArrayOutputStream().also {
        trayBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
      }.toByteArray()
      zos.putNextEntry(java.util.zip.ZipEntry("tray.png"))
      zos.write(trayBytes)
      zos.closeEntry()

      // 3 PNG sticker images
      for (i in 1..3) {
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also {
          bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
        }.toByteArray()
        zos.putNextEntry(java.util.zip.ZipEntry("$i.png"))
        zos.write(bytes)
        zos.closeEntry()
      }
    }

    val uri = android.net.Uri.fromFile(wastickersFile)
    val imported = com.example.silversticker.utils.ZipUtils.importPack(context, uri)

    assertNotNull(imported)
    assertEquals(3, imported!!.stickers.size)
    assertEquals(listOf("🎉", "🥳"), imported.stickers[0].emojis)
    assertEquals(listOf("🚀"), imported.stickers[1].emojis)
    assertEquals(listOf("🐶", "🐾"), imported.stickers[2].emojis)
  }

  @Test
  fun `WebpExifHelper injects and extracts WhatsApp sticker accessibility_text`() {
    val webpBytes = createSampleWebpBytes()

    val originalMetadata = com.example.silversticker.utils.WebpMetadata(
      packId = "a11y_pack",
      packName = "Accessible Pack",
      publisher = "Accessibility Team",
      emojis = listOf("☕", "😄"),
      accessibilityText = "A laughing cup of hot coffee"
    )

    val injectedBytes = com.example.silversticker.utils.WebpExifHelper.injectMetadata(webpBytes, originalMetadata)
    val extracted = com.example.silversticker.utils.WebpExifHelper.extractMetadata(injectedBytes)

    assertNotNull(extracted)
    assertEquals("A laughing cup of hot coffee", extracted!!.accessibilityText)
    assertEquals(listOf("☕", "😄"), extracted.emojis)
  }

  @Test
  fun `importPack parses accessibility_text from WhatsApp contents json`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val wastickersFile = java.io.File(context.cacheDir, "contents_stickers.wastickers")

    val contentsJson = """
      {
        "android_play_store_link": "",
        "ios_app_store_link": "",
        "publisher": "A11y Creator",
        "identifier": "a11y_pack_01",
        "name": "Inclusive Stickers",
        "stickers": [
          {
            "image_file": "1.webp",
            "emojis": ["❤️", "✨"],
            "accessibility_text": "Sparkling red heart"
          },
          {
            "image_file": "2.webp",
            "emojis": ["🐱"],
            "accessibility_text": "Curious kitten looking up"
          },
          {
            "image_file": "3.webp",
            "emojis": ["🚀"],
            "accessibility_text": "Rocket blasting off into space"
          }
        ]
      }
    """.trimIndent()

    java.util.zip.ZipOutputStream(java.io.FileOutputStream(wastickersFile)).use { zos ->
      zos.putNextEntry(java.util.zip.ZipEntry("contents.json"))
      zos.write(contentsJson.toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      // tray.webp
      val trayBytes = createSampleWebpBytes()
      zos.putNextEntry(java.util.zip.ZipEntry("tray.webp"))
      zos.write(trayBytes)
      zos.closeEntry()

      // 3 stickers
      for (i in 1..3) {
        val bytes = createSampleWebpBytes()
        zos.putNextEntry(java.util.zip.ZipEntry("$i.webp"))
        zos.write(bytes)
        zos.closeEntry()
      }
    }

    val uri = android.net.Uri.fromFile(wastickersFile)
    val imported = com.example.silversticker.utils.ZipUtils.importPack(context, uri)

    assertNotNull(imported)
    assertEquals("Inclusive Stickers", imported!!.name)
    assertEquals("A11y Creator", imported.publisher)
    assertEquals(3, imported.stickers.size)
    assertEquals("Sparkling red heart", imported.stickers[0].accessibilityText)
    assertEquals(listOf("❤️", "✨"), imported.stickers[0].emojis)
    assertEquals("Curious kitten looking up", imported.stickers[1].accessibilityText)
    assertEquals("Rocket blasting off into space", imported.stickers[2].accessibilityText)
  }

  @Test
  fun `importPacks and multi-pack import process multiple wastickers simultaneously`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    fun createDummyWastickers(name: String, fileName: String): java.io.File {
      val file = java.io.File(context.cacheDir, fileName)
      java.util.zip.ZipOutputStream(java.io.FileOutputStream(file)).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("title.txt"))
        zos.write(name.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("author.txt"))
        zos.write("Multi Author".toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        val trayBytes = createSampleWebpBytes()
        zos.putNextEntry(java.util.zip.ZipEntry("tray.webp"))
        zos.write(trayBytes)
        zos.closeEntry()

        for (i in 1..3) {
          val bytes = createSampleWebpBytes()
          zos.putNextEntry(java.util.zip.ZipEntry("$i.webp"))
          zos.write(bytes)
          zos.closeEntry()
        }
      }
      return file
    }

    val file1 = createDummyWastickers("Pack Alpha", "alpha.wastickers")
    val file2 = createDummyWastickers("Pack Beta", "beta.wastickers")

    val uri1 = android.net.Uri.fromFile(file1)
    val uri2 = android.net.Uri.fromFile(file2)

    val packs1 = com.example.silversticker.utils.ZipUtils.importPacks(context, uri1)
    val packs2 = com.example.silversticker.utils.ZipUtils.importPacks(context, uri2)

    assertEquals(1, packs1.size)
    assertEquals("Pack Alpha", packs1[0].name)
    assertEquals(1, packs2.size)
    assertEquals("Pack Beta", packs2[0].name)

    // Verify both are stored in StickerStorage
    val storedPack1 = com.example.silversticker.utils.StickerStorage.getPack(packs1[0].identifier)
    val storedPack2 = com.example.silversticker.utils.StickerStorage.getPack(packs2[0].identifier)
    assertNotNull(storedPack1)
    assertNotNull(storedPack2)
    assertEquals("Pack Alpha", storedPack1?.name)
    assertEquals("Pack Beta", storedPack2?.name)
  }

  @Test
  fun `StickerContentProvider returns WhatsApp compliant MIME types and cursors`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val provider = com.example.silversticker.utils.StickerContentProvider()
    val authority = "${context.packageName}.sticker_provider"
    val providerInfo = android.content.pm.ProviderInfo().apply {
      this.authority = authority
      this.packageName = context.packageName
    }
    provider.attachInfo(context, providerInfo)

    // Add a test pack
    val pack = StickerPack(
      identifier = "provider_test_pack",
      name = "Provider Test",
      publisher = "Provider Dev",
      trayImageFileName = "tray_test.webp",
      stickers = listOf(
        Sticker("s1.webp", listOf("🐱", "🐾")),
        Sticker("s2.webp", listOf("🚀")),
        Sticker("s3.webp", listOf("🔥", "⚡"))
      )
    )
    com.example.silversticker.utils.StickerStorage.addPack(context, pack)

    // 1. Verify MIME types
    val metaMime = provider.getType(android.net.Uri.parse("content://$authority/metadata"))
    val metaIdMime = provider.getType(android.net.Uri.parse("content://$authority/metadata/provider_test_pack"))
    val stickersMime = provider.getType(android.net.Uri.parse("content://$authority/stickers/provider_test_pack"))

    assertEquals("vnd.android.cursor.dir/vnd.$authority.metadata", metaMime)
    assertEquals("vnd.android.cursor.item/vnd.$authority.metadata", metaIdMime)
    assertEquals("vnd.android.cursor.dir/vnd.$authority.stickers", stickersMime)

    // 2. Verify metadata cursor
    val metaCursor = provider.query(android.net.Uri.parse("content://$authority/metadata"), null, null, null, null)
    assertNotNull(metaCursor)
    org.junit.Assert.assertTrue(metaCursor!!.count > 0)
    metaCursor.close()

    // 3. Verify stickers cursor
    val stickersCursor = provider.query(android.net.Uri.parse("content://$authority/stickers/provider_test_pack"), null, null, null, null)
    assertNotNull(stickersCursor)
    assertEquals(3, stickersCursor!!.count)
    stickersCursor.moveToFirst()
    val fileNameCol = stickersCursor.getColumnIndex("sticker_file_name")
    val emojiCol = stickersCursor.getColumnIndex("sticker_emoji")
    org.junit.Assert.assertTrue(fileNameCol >= 0)
    org.junit.Assert.assertTrue(emojiCol >= 0)
    assertEquals("s1.webp", stickersCursor.getString(fileNameCol))
    assertEquals("🐱,🐾", stickersCursor.getString(emojiCol))
    stickersCursor.close()
  }
}



