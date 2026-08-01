# Xposed entry and hook infrastructure
-keep class com.frostwork.bgenhance.MainHook { *; }
-keep class com.frostwork.bgenhance.hooks.** { *; }
-keep class com.frostwork.bgenhance.HookConfig { *; }
-keep class com.frostwork.bgenhance.ModuleSettings { *; }
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Manifest components
-keep class com.frostwork.bgenhance.ModuleApplication { *; }
-keep class com.frostwork.bgenhance.ui.SettingsActivity { *; }
-keep class com.frostwork.bgenhance.ui.FeatureSettingsActivity { *; }
-keep class com.frostwork.bgenhance.ui.DiagnosticsActivity { *; }

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
