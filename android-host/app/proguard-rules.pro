# AndroidDEX Proguard / R8 Configuration Rules
# REL-01, REL-02

# ---------------------------------------------------------------------------
# JNI Keep Rules
# ---------------------------------------------------------------------------
# Keep all classes with native methods and keep their method names and signatures intact
-keepclasseswithmembernames class * {
    native <methods>;
}

# Explicitly keep JNI bridge classes and their companion objects
-keep class com.example.androidhost.quic.QuicServer { *; }
-keep class com.example.androidhost.quic.QuicServer$* { *; }
-keep class com.example.androidhost.security.SecurityBridge { *; }
-keep class com.example.androidhost.security.SecurityBridge$* { *; }

# ---------------------------------------------------------------------------
# Protobuf Lite Keep Rules
# ---------------------------------------------------------------------------
# Keep generated Protobuf classes and their fields/methods
-keep class com.androiddex.protocol.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

# ---------------------------------------------------------------------------
# Kotlin & Coroutines Keep Rules
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------------
# Jetpack Compose & Android Lifecycle Keep Rules
# ---------------------------------------------------------------------------
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# Keep services and activities accessed by the Android system
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
