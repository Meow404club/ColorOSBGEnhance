package com.frostwork.bgenhance;

public interface FeatureHook {
    String id();
    String prefKey();
    String install(ClassLoader classLoader);
}
