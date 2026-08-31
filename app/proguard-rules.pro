-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

-keep class com.karen.flymetool.** { *; }
-keep class io.github.libxposed.service.** { *; }

# libxposed API 101：保留入口类，并适配被混淆后的 java_init.list
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}