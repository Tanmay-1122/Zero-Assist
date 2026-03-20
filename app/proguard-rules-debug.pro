# Debug ProGuard rules – resource shrinking only.
# Code is NOT obfuscated or stripped so stack traces remain readable.

# Keep all classes and their members exactly as-is (no shrinking, no renaming).
-keep class ** { *; }
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod

# Keep Kotlin metadata so reflection works correctly.
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# Prevent stripping coroutines internals needed for debug stack traces.
-keep class kotlinx.coroutines.** { *; }

# Keep Compose internals for correct runtime behaviour.
-keep class androidx.compose.** { *; }

# Keep Room entities and DAOs.
-keep @androidx.room.Entity class ** { *; }
-keep @androidx.room.Dao class ** { *; }
-keep @androidx.room.Database class ** { *; }

# Keep serializable classes used by kotlinx.serialization.
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
