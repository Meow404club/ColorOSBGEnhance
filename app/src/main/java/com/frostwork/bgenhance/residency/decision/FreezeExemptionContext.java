package com.frostwork.bgenhance.residency.decision;

import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import com.frostwork.bgenhance.residency.runtime.UidPolicyScope;
import java.util.Objects;

/** One HANS exemption query and its UID-wide policy scope. */
public final class FreezeExemptionContext extends ResidencyContext {
    public enum Kind {
        CORE(UidPolicyScope.CORE_EXEMPTION),
        LCD_ON(UidPolicyScope.LCD_ON_EXEMPTION);

        private final UidPolicyScope uidPolicyScope;

        Kind(UidPolicyScope uidPolicyScope) {
            this.uidPolicyScope = uidPolicyScope;
        }

        public UidPolicyScope uidPolicyScope() { return uidPolicyScope; }
    }

    private final Kind kind;

    public FreezeExemptionContext(ResidencyTarget target, Kind kind) {
        this(target, kind, "", "");
    }

    public FreezeExemptionContext(
            ResidencyTarget target,
            Kind kind,
            String featureId,
            String hookTargetId) {
        super(target, featureId, hookTargetId);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() { return kind; }
    public UidPolicyScope uidPolicyScope() { return kind.uidPolicyScope(); }
}
