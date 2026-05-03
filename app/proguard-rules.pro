-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

-keep class com.karen.flymetool.** { *; }
-keep class de.robv.android.xposed.** { *; }

-keepclassmembers class * {
    @de.robv.android.xposed.* <methods>;
}

-keepclassmembers class com.karen.flymetool.hook.** {
    public static void handleLoadPackage(...);
}
