# ProGuard & R8 Optimization Rules for SilverSticker

# Gson reflection & serialization rules
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Keep data models serialized to/from JSON with Gson
-keep class com.example.silversticker.models.** { *; }
-keepclassmembers class com.example.silversticker.models.** { *; }
-keep class com.example.silversticker.utils.WebpMetadata { *; }
-keepclassmembers class com.example.silversticker.utils.WebpMetadata { *; }

# Keep WhatsApp sticker ContentProvider
-keep class com.example.silversticker.utils.StickerContentProvider { *; }
