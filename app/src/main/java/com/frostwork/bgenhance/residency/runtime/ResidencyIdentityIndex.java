package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Package and UID identity index built for one immutable policy revision. */
public final class ResidencyIdentityIndex {
    private static final long INVALIDATED_POLICY_REVISION = -1L;
    private static final int PER_USER_RANGE = 100_000;
    private static final int FIRST_APPLICATION_UID = 10_000;

    private final long policyRevision;
    private final Map<String, AppResidencyPolicy> policiesByPackage;
    private final Map<String, Integer> packageAppIds;
    private final Map<Integer, Set<String>> appIdPackages;

    private ResidencyIdentityIndex(
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByPackage,
            Map<String, Integer> packageAppIds,
            Map<Integer, Set<String>> appIdPackages) {
        this.policyRevision = policyRevision;
        this.policiesByPackage = policiesByPackage;
        this.packageAppIds = packageAppIds;
        this.appIdPackages = appIdPackages;
    }

    /** Sentinel used while package identities are unavailable or being rebuilt. */
    public static ResidencyIdentityIndex invalidated() {
        return new ResidencyIdentityIndex(
                INVALIDATED_POLICY_REVISION,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap());
    }

    public static ResidencyIdentityIndex build(
            RuntimeConfigSnapshot snapshot,
            Map<String, Integer> packageUids) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(packageUids, "packageUids");

        Map<String, Integer> packageAppIdCopy = new TreeMap<>();
        Map<Integer, Set<String>> mutableAppIdPackages = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : packageUids.entrySet()) {
            String packageName = Objects.requireNonNull(entry.getKey(), "packageName");
            Integer uid = Objects.requireNonNull(entry.getValue(), "uid");
            if (packageName.isEmpty()) throw new IllegalArgumentException("empty packageName");
            if (uid < 0) throw new IllegalArgumentException("uid < 0 for " + packageName);
            int appId = appId(uid);
            packageAppIdCopy.put(packageName, appId);
            mutableAppIdPackages
                    .computeIfAbsent(appId, ignored -> new TreeSet<>())
                    .add(packageName);
        }

        Map<Integer, Set<String>> immutableAppIdPackages = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : mutableAppIdPackages.entrySet()) {
            immutableAppIdPackages.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }

        return new ResidencyIdentityIndex(
                snapshot.policyRevision(),
                snapshot.policiesByPackage(),
                Collections.unmodifiableMap(new LinkedHashMap<>(packageAppIdCopy)),
                Collections.unmodifiableMap(immutableAppIdPackages));
    }

    public ResidencyIdentityResolution resolve(ResidencyTarget target) {
        Objects.requireNonNull(target, "target");
        String packageName = target.packageName();
        Integer indexedAppId = packageName == null ? null : packageAppIds.get(packageName);
        int targetAppId = target.hasUid() ? appId(target.uid()) : ResidencyTarget.UNKNOWN_UID;

        if (target.hasUid() && indexedAppId != null && targetAppId != indexedAppId) {
            return resolution(
                    IdentityResolutionStatus.UID_MISMATCH,
                    target,
                    null,
                    Collections.singleton(packageName),
                    null);
        }

        int effectiveAppId = target.hasUid()
                ? targetAppId
                : indexedAppId == null ? ResidencyTarget.UNKNOWN_UID : indexedAppId;
        if (effectiveAppId != ResidencyTarget.UNKNOWN_UID && isSystemUid(effectiveAppId)) {
            return resolution(
                    IdentityResolutionStatus.SYSTEM_UID,
                    target,
                    null,
                    packageName == null
                            ? packagesForAppId(effectiveAppId)
                            : Collections.singleton(packageName),
                    null);
        }

        if (packageName != null) {
            AppResidencyPolicy policy = policiesByPackage.get(packageName);
            if (policy == null) {
                return resolution(
                        IdentityResolutionStatus.UNMANAGED,
                        target,
                        null,
                        Collections.singleton(packageName),
                        null);
            }
            return resolution(
                    IdentityResolutionStatus.EXACT_PACKAGE,
                    target,
                    packageName,
                    Collections.singleton(packageName),
                    policy);
        }

        if (!target.hasUid()) {
            return resolution(
                    IdentityResolutionStatus.UNKNOWN,
                    target,
                    null,
                    Collections.emptySet(),
                    null);
        }

        Set<String> uidCandidates = appIdPackages.get(targetAppId);
        if (uidCandidates == null || uidCandidates.isEmpty()) {
            return resolution(
                    IdentityResolutionStatus.UNKNOWN,
                    target,
                    null,
                    Collections.emptySet(),
                    null);
        }

        List<AppResidencyPolicy> managedPolicies = new ArrayList<>();
        for (String candidate : uidCandidates) {
            AppResidencyPolicy policy = policiesByPackage.get(candidate);
            if (policy != null) managedPolicies.add(policy);
        }
        if (managedPolicies.isEmpty()) {
            return resolution(
                    IdentityResolutionStatus.UNMANAGED,
                    target,
                    null,
                    uidCandidates,
                    null);
        }
        if (managedPolicies.size() == 1) {
            AppResidencyPolicy policy = managedPolicies.get(0);
            return resolution(
                    IdentityResolutionStatus.UID_SINGLE_RULE,
                    target,
                    policy.packageName(),
                    uidCandidates,
                    policy);
        }

        AppResidencyPolicy representative = managedPolicies.get(0);
        for (int i = 1; i < managedPolicies.size(); i++) {
            if (!sameEffectivePolicy(representative, managedPolicies.get(i))) {
                return resolution(
                        IdentityResolutionStatus.SHARED_UID_CONFLICT,
                        target,
                        null,
                        uidCandidates,
                        null);
            }
        }
        return resolution(
                IdentityResolutionStatus.SHARED_UID_SAME_POLICY,
                target,
                representative.packageName(),
                uidCandidates,
                representative);
    }

    private Set<String> packagesForAppId(int appId) {
        Set<String> packages = appIdPackages.get(appId);
        return packages == null ? Collections.emptySet() : packages;
    }

    private static ResidencyIdentityResolution resolution(
            IdentityResolutionStatus status,
            ResidencyTarget target,
            String matchedPackageName,
            Set<String> candidates,
            AppResidencyPolicy policy) {
        return new ResidencyIdentityResolution(
                status, target, matchedPackageName, candidates, policy);
    }

    private static boolean sameEffectivePolicy(
            AppResidencyPolicy first,
            AppResidencyPolicy second) {
        boolean firstSystemManaged = first.mode() == ResidencyMode.SYSTEM;
        boolean secondSystemManaged = second.mode() == ResidencyMode.SYSTEM;
        if (first.enabled() != second.enabled()
                || firstSystemManaged != secondSystemManaged) {
            return false;
        }
        if (!first.enabled() || firstSystemManaged) return true;
        return first.killPolicy().equals(second.killPolicy())
                && first.freezePolicy().equals(second.freezePolicy())
                && first.wakePolicy().equals(second.wakePolicy())
                && first.resourcePolicy().equals(second.resourcePolicy())
                && first.refreezePolicy().equals(second.refreezePolicy());
    }

    public static boolean isSystemUid(int uid) {
        if (uid < 0) return false;
        return appId(uid) < FIRST_APPLICATION_UID;
    }

    public static int appId(int uid) {
        if (uid < 0) throw new IllegalArgumentException("uid < 0");
        return uid % PER_USER_RANGE;
    }

    public long policyRevision() { return policyRevision; }
    public boolean isInvalidated() { return policyRevision == INVALIDATED_POLICY_REVISION; }
    public Map<String, Integer> packageAppIds() { return packageAppIds; }
    public Map<Integer, Set<String>> appIdPackages() { return appIdPackages; }
}
