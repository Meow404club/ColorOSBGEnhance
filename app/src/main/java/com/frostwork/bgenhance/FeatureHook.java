package com.frostwork.bgenhance;

public interface FeatureHook {
    String hookTargetId();
    String prefKey();
    String install(ClassLoader classLoader);
}
