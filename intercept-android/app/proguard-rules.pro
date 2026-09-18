# R8 rules for the release build.
#
# The application is small; the shrinker is here to remove what nothing calls —
# chiefly the extended icon set and LiveKit's unused Java. The risk in turning it
# on is the opposite failure: removing something that is only reached by
# reflection. kotlinx.serialization is exactly that, and this app's entire data
# layer goes through it, so it is spelled out below.

# Crash reports keep their frames instead of becoming "at a.b.c(SourceFile)".
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotations are read at runtime by kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# --- kotlinx.serialization -------------------------------------------------
# Every @Serializable class carries a generated $$serializer plus a Companion
# that holds it. Nothing calls them directly, so R8 is entitled to delete them —
# and then every decode throws at runtime.
-keep,includedescriptorclasses class com.intercept.**$$serializer { *; }
-keepclassmembers class com.intercept.** {
    *** Companion;
}
-keepclasseswithmembers class com.intercept.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# --- Retrofit / OkHttp -----------------------------------------------------
# Both ship their own rules; these only silence the optional-dependency noise
# that would otherwise fail the build with warnings-as-errors.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- LiveKit / WebRTC ------------------------------------------------------
# JNI callbacks arrive by class name, so the entry points the native layer
# reaches must survive. Its own consumer rules cover the rest.
-keep class io.livekit.android.room.** { *; }
-keepclasseswithmembers class * {
    @io.livekit.android.annotations.* <methods>;
}
-dontwarn io.livekit.**
-dontwarn org.webrtc.**
