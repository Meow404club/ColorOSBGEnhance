package com.frostwork.bgenhance.residency.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable global settings carried by the unified runtime configuration. */
public final class GlobalFeatureConfig {
    private final boolean masterEnabled;
    private final boolean dryRun;
    private final boolean verboseDiagnostics;
    private final String activePreset;
    private final Map<String, Boolean> legacyFeatures;

    public GlobalFeatureConfig(
            boolean masterEnabled,
            boolean dryRun,
            boolean verboseDiagnostics,
            String activePreset,
            Map<String, Boolean> legacyFeatures) {
        this.masterEnabled = masterEnabled;
        this.dryRun = dryRun;
        this.verboseDiagnostics = verboseDiagnostics;
        this.activePreset = Objects.requireNonNull(activePreset, "activePreset");

        Map<String, Boolean> copy = new TreeMap<>();
        for (Map.Entry<String, Boolean> entry
                : Objects.requireNonNull(legacyFeatures, "legacyFeatures").entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "legacyFeatures contains null key"),
                    Objects.requireNonNull(
                            entry.getValue(),
                            "legacyFeatures contains null value for " + entry.getKey()));
        }
        this.legacyFeatures = Collections.unmodifiableMap(copy);
    }

    public static GlobalFeatureConfig defaults() {
        return new GlobalFeatureConfig(true, false, false, "balanced", Collections.emptyMap());
    }

    public boolean masterEnabled() { return masterEnabled; }
    public boolean dryRun() { return dryRun; }
    public boolean verboseDiagnostics() { return verboseDiagnostics; }
    public String activePreset() { return activePreset; }
    public Map<String, Boolean> legacyFeatures() { return legacyFeatures; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GlobalFeatureConfig)) return false;
        GlobalFeatureConfig that = (GlobalFeatureConfig) other;
        return masterEnabled == that.masterEnabled
                && dryRun == that.dryRun
                && verboseDiagnostics == that.verboseDiagnostics
                && activePreset.equals(that.activePreset)
                && legacyFeatures.equals(that.legacyFeatures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                masterEnabled, dryRun, verboseDiagnostics, activePreset, legacyFeatures);
    }
}
