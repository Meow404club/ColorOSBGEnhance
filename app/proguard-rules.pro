# Xposed entry and hook infrastructure
-keep class com.frostwork.bgenhance.MainHook { *; }
-keep class com.frostwork.bgenhance.hooks.** { *; }
-keep class com.frostwork.bgenhance.HookConfig { *; }
-keep class com.frostwork.bgenhance.ModuleSettings { *; }
-keep class de.robv.android.xposed.** { *; }

# Manifest components
-keep class com.frostwork.bgenhance.ui.SettingsActivity { *; }
-keep class com.frostwork.bgenhance.ui.FeatureSettingsActivity { *; }
-keep class com.frostwork.bgenhance.ui.DiagnosticsActivity { *; }

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
