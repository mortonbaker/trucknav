# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- TruckNav keep rules ---------------------------------------------------
# uniffi (Ferrostar core) talks to Rust through JNA: everything reflective.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-keep class com.stadiamaps.ferrostar.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
# kotlinx.serialization (Photon / ABS JSON) + MapLibre style DSL
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.morton.trucknav.**$$serializer { *; }
-keepclassmembers class com.morton.trucknav.** { *** Companion; }
-keepclasseswithmembers class com.morton.trucknav.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
# Paho MQTT loads persistence/logging classes by name
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**
# our services / receivers are referenced by name from the manifest
-keep class com.morton.trucknav.books.** { *; }
-keep class com.morton.trucknav.overlay.** { *; }
-dontwarn okhttp3.**
-dontwarn org.slf4j.**
