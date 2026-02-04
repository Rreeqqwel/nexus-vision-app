# Add project specific ProGuard rules here.

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }

# Keep Generative AI classes
-keep class com.google.ai.client.generativeai.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# CameraX
-keep class androidx.camera.** { *; }
