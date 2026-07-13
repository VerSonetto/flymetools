-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

-keep class com.karen.flymetool.** { *; }
-keep class de.robv.android.xposed.** { *; }

# miuix
-keep class top.yukonga.miuix.** { *; }
-keepclassmembers class top.yukonga.miuix.** { *; }

# navigationevent (miuix 依赖)
-keep class androidx.navigationevent.** { *; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# squircle
-keep class top.yukonga.miuix.kmp.squircle.** { *; }

-keepclassmembers class * {
    @de.robv.android.xposed.* <methods>;
}
