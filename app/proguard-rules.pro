# Faznet — library consumer rules cover Media3/Coil; keep our model names readable in crash logs
-keepattributes SourceFile,LineNumberTable
-keep class com.faznet.tv.data.** { *; }
# FFmpeg renderer is loaded by reflection from DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
