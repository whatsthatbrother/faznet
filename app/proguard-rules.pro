# Faznet: library consumer rules cover Media3, Coil, Room and Hilt. Crash logs are decoded with the mapping file kept
# for each release (archive/<version>/mapping.txt).
-keepattributes SourceFile,LineNumberTable
# FFmpeg renderer is loaded by reflection from DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
# libVLC is driven from native code through JNI: keep its classes and members as they are
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**
