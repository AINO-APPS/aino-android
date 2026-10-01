# Release R8 rules. Libraries that ship consumer rules (Room, WorkManager,
# Firebase, Media3, Coil, CameraX, ML Kit, OkHttp, kotlinx.serialization) are
# not repeated here; only what the app itself needs is listed.

-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# WebRTC: native code calls back into Java by name (JNI / @CalledByNative).
-keep class org.webrtc.** { *; }
# Modern WebRTC binds JNI through jni_zero; JNI_OnLoad looks these classes up
# by name and aborts if R8 removed them. The AAR ships no consumer rules.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.JniZeroJni
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# kotlinx.serialization: app models are looked up through their generated
# serializers / companions (rules from the kotlinx.serialization README).
-keepclassmembers @kotlinx.serialization.Serializable class app.aino.mobile.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.aino.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
