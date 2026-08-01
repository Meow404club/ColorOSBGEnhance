package com.frostwork.bgenhance.residency.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Resources that should bypass HANS proxying or restriction. */
public final class ResourcePolicy {
    private final boolean keepNetwork;
    private final Set<ResourceType> keptResources;

    public ResourcePolicy(boolean keepNetwork, Set<ResourceType> keptResources) {
        this.keepNetwork = keepNetwork;
        EnumSet<ResourceType> copy = keptResources == null || keptResources.isEmpty()
                ? EnumSet.noneOf(ResourceType.class) : EnumSet.copyOf(keptResources);
        this.keptResources = Collections.unmodifiableSet(copy);
    }

    public static ResourcePolicy followSystem() {
        return new ResourcePolicy(false, Collections.emptySet());
    }

    public boolean keepNetwork() { return keepNetwork; }
    public boolean keeps(ResourceType resource) {
        return keptResources.contains(Objects.requireNonNull(resource, "resource"));
    }
    public Set<ResourceType> keptResources() { return keptResources; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ResourcePolicy)) return false;
        ResourcePolicy that = (ResourcePolicy) other;
        return keepNetwork == that.keepNetwork && keptResources.equals(that.keptResources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keepNetwork, keptResources);
    }
}
