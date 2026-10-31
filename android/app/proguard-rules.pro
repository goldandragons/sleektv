# Keep the app's own classes as written (JSON models, screens, the custom player view).
# Libraries (Compose, Media3, OkHttp, Coil) still get fully optimized, which is where the speed comes from.
-keep class com.sleektv.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class com.sleektv.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional TLS providers OkHttp looks for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
