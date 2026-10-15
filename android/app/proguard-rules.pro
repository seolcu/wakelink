# Moshi reflective adapters need the generated JsonClass metadata kept.
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class *

# The API DTOs are plain Kotlin data classes read by Moshi's *reflective*
# KotlinJsonAdapterFactory (no @JsonClass codegen), so R8 must not rename or
# strip their fields/constructors, and Kotlin metadata must survive for
# kotlin-reflect to see parameter names and nullability.
-keep class app.wakelink.android.network.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# WorkManager (initialized at startup via androidx.startup) builds its Room
# database by reflectively calling WorkDatabase_Impl's no-arg constructor.
# Under AGP 9's R8 that constructor was stripped, so the release build
# crashed before any app code ran ("NoSuchMethodException:
# WorkDatabase_Impl.<init>"). Keep generated Room database constructors.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# End-to-end payload, also (de)serialized by Moshi's reflective adapter.
-keep class app.wakelink.android.crypto.SealedWake { *; }
