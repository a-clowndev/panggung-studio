-keep class io.panggung.obs.NativeBridge { *; }
-keep class io.panggung.obs.privilege.** { *; }
-keep class rikka.shizuku.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn android.webkit.**
-dontwarn rikka.shizuku.**
