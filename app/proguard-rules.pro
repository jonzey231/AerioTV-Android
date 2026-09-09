# Add project specific ProGuard rules here.

# FFmpeg audio decoder extension (vendored AAR). DefaultRenderersFactory loads
# FfmpegAudioRenderer reflectively, so keep it (and the JNI bridge) even if R8
# shrinking is ever enabled, otherwise AC-3/E-AC-3/DTS broadcast audio silently
# loses its software-decode fallback.
-keep class androidx.media3.decoder.ffmpeg.** { *; }


# ---- R8 (enabled 2026-09-09 for Play's app-optimization threshold) ----
# Readable crash traces through the mapping file.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,EnclosingMethod,InnerClasses
-renamesourcefileattribute SourceFile

# kotlinx.serialization: generated serializers and Companion.serializer()
# are looked up by name.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.aeriotv.android.**$$serializer { *; }
-keepclassmembers class com.aeriotv.android.** { *** Companion; }
-keepclasseswithmembers class com.aeriotv.android.** { kotlinx.serialization.KSerializer serializer(...); }
-keep @kotlinx.serialization.Serializable class com.aeriotv.android.** { *; }

# Anything the app marks with @Keep (reflection, manifest-referenced).
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }

# Ktor / SLF4J: optional logging backends are absent on Android.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
