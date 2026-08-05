package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.Objects;

/** Network or HANS-proxied resource being evaluated for retention. */
public final class ResourceContext extends ResidencyContext {
    public enum Kind {
        NETWORK,
        HANS_RESOURCE
    }

    private final Kind kind;
    private final ResourceType resourceType;

    private ResourceContext(
            ResidencyTarget target,
            Kind kind,
            ResourceType resourceType,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resourceType = resourceType;
        if (kind == Kind.HANS_RESOURCE && resourceType == null) {
            throw new NullPointerException("resourceType");
        }
        if (kind == Kind.NETWORK && resourceType != null) {
            throw new IllegalArgumentException("network context cannot have resourceType");
        }
    }

    public static ResourceContext network(ResidencyTarget target) {
        return network(target, "", "");
    }

    public static ResourceContext network(
            ResidencyTarget target,
            String featureId,
            String hookTargetId) {
        return new ResourceContext(target, Kind.NETWORK, null, featureId, hookTargetId);
    }

    public static ResourceContext resource(ResidencyTarget target, ResourceType resourceType) {
        return resource(target, resourceType, "", "");
    }

    public static ResourceContext resource(
            ResidencyTarget target,
            ResourceType resourceType,
            String featureId,
            String hookTargetId) {
        return new ResourceContext(
                target, Kind.HANS_RESOURCE, resourceType, featureId, hookTargetId);
    }

    public Kind kind() { return kind; }
    public ResourceType resourceType() { return resourceType; }
}
