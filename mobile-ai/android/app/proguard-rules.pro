# Add project specific ProGuard rules here.

# llama.rn native bindings — prevent stripping JNI methods
-keep class com.rnllama.** { *; }
-keepclassmembers class com.rnllama.** { *; }

# React Native
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }

# Keep native method names for JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
