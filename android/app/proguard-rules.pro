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
