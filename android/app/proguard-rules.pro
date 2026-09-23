# Moshi reflective adapters need the generated JsonClass metadata kept.
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class *
