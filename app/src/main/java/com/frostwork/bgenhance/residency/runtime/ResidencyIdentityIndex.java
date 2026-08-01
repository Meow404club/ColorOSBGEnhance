package com.frostwork.bgenhance.residency.runtime;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.FreezeSource;
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
    private final Map<Integer, Set<String>> uidPackages;

    private ResidencyIdentityIndex(
            long policyRevision,
            Map<String, AppResidencyPolicy> policiesByPackage,
            Map<String, Integer> packageAppIds,
            Map<Integer, Set<String>> appIdPackages,
            Map<Integer, Set<String>> uidPackages) {
        this.policyRevision = policyRevision;
        this.policiesByPackage = policiesByPackage;
        this.packageAppIds = packageAppIds;
        this.appIdPackages = appIdPackages;
        this.uidPackages = uidPackages;
    }

    /** Sentinel used while package identities are unavailable or being rebuilt. */
    public static ResidencyIdentityIndex invalidated() {
        return new ResidencyIdentityIndex(
                INVALIDATED_POLICY_REVISION,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap());
    }

    public static ResidencyIdentityIndex build(
            RuntimeConfigSnapshot snapshot,
            Map<String, Integer> packageUids) {
        Objects.requireNonNull(packageUids, "packageUids");
        Map<Integer, Set<String>> verifiedUidPackages = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : packageUids.entrySet()) {
            String packageName = Objects.requireNonNull(entry.getKey(), "packageName");
            Integer uid = Objects.requireNonNull(entry.getValue(), "uid");
            verifiedUidPackages
                    .computeIfAbsent(uid, ignored -> new TreeSet<>())
                    .add(packageName);
        }
        return build(snapshot, packageUids, verifiedUidPackages);
    }

    static ResidencyIdentityIndex build(
            RuntimeConfigSnapshot snapshot,
            Map<String, Integer> packageUids,
            Map<Integer, Set<String>> verifiedUidPackages) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(packageUids, "packageUids");
        Objects.requireNonNull(verifiedUidPackages, "verifiedUidPackages");

        Map<String, Integer> packageAppIdCopy = new TreeMap<>();
        Map<Integer, Set<String>> mutableAppIdPackages = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : packageUids.entrySet()) {
            String packageName = Objects.requireNonNull(entry.getKey(), "packageName");
            Integer uid = Objects.requireNonNull(entry.getValue(), "uid");
            addPackageIdentity(
                    packageAppIdCopy, mutableAppIdPackages, packageName, uid);
        }

        Map<Integer, Set<String>> mutableUidPackages = new TreeMap<>();
        for (Map.Entry<Integer, Set<String>> entry : verifiedUidPackages.entrySet()) {
            Integer uid = Objects.requireNonNull(entry.getKey(), "uid");
            if (uid < 0) throw new IllegalArgumentException("uid < 0");
            Set<String> packages = Objects.requireNonNull(entry.getValue(), "uid packages");
            if (packages.isEmpty()) {
                throw new IllegalArgumentException("empty package set for uid " + uid);
            }
            Set<String> uidPackageCopy = new TreeSet<>();
            for (String packageName : packages) {
                addPackageIdentity(
                        packageAppIdCopy, mutableAppIdPackages, packageName, uid);
                uidPackageCopy.add(packageName);
            }
            mutableUidPackages.put(uid, uidPackageCopy);
        }

        Map<Integer, Set<String>> immutableAppIdPackages =
                immutablePackageSets(mutableAppIdPackages);
        Map<Integer, Set<String>> immutableUidPackages =
                immutablePackageSets(mutableUidPackages);

        return new ResidencyIdentityIndex(
                snapshot.policyRevision(),
                snapshot.policiesByPackage(),
                Collections.unmodifiableMap(new LinkedHashMap<>(packageAppIdCopy)),
                immutableAppIdPackages,
                immutableUidPackages);
    }

    private static void addPackageIdentity(
            Map<String, Integer> packageAppIds,
            Map<Integer, Set<String>> appIdPackages,
            String packageName,
            int uid) {
        Objects.requireNonNull(packageName, "packageName");
        if (packageName.isEmpty()) throw new IllegalArgumentException("empty packageName");
        if (uid < 0) throw new IllegalArgumentException("uid < 0 for " + packageName);
        int appId = appId(uid);
        Integer previous = packageAppIds.putIfAbsent(packageName, appId);
        if (previous != null && previous != appId) {
            throw new IllegalStateException("conflicting appId for " + packageName);
        }
        appIdPackages
                .computeIfAbsent(appId, ignored -> new TreeSet<>())
                .add(packageName);
    }

    private static Map<Integer, Set<String>> immutablePackageSets(
            Map<Integer, Set<String>> source) {
        Map<Integer, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : source.entrySet()) {
            result.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
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

        return resolveUidCandidates(
                target, packagesForUid(target.uid()), UidPolicyScope.FULL);
    }

    /** Resolves policy for a UID-wide vendor action without trusting one package hint. */
    public ResidencyIdentityResolution resolveUidScoped(ResidencyTarget target) {
        return resolveUidScoped(target, UidPolicyScope.FULL);
    }

    /** Resolves one behavior projection across every known package sharing the target UID. */
    public ResidencyIdentityResolution resolveUidScoped(
            ResidencyTarget target, UidPolicyScope policyScope) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(policyScope, "policyScope");
        if (isInvalidated()) return ResidencyIdentityResolution.notEvaluated(target);

        String packageName = target.packageName();
        if (!target.hasUid()) {
            return resolution(
                    IdentityResolutionStatus.UNKNOWN,
                    target,
                    null,
                    packageName == null
                            ? Collections.emptySet()
                            : Collections.singleton(packageName),
                    null);
        }

        int targetAppId = appId(target.uid());
        Set<String> uidCandidates = packagesForUid(target.uid());
        Integer indexedAppId = packageName == null ? null : packageAppIds.get(packageName);
        if (indexedAppId != null && targetAppId != indexedAppId) {
            return resolution(
                    IdentityResolutionStatus.UID_MISMATCH,
                    target,
                    null,
                    Collections.singleton(packageName),
                    null);
        }
        if (isSystemUid(targetAppId)) {
            return resolution(
                    IdentityResolutionStatus.SYSTEM_UID,
                    target,
                    null,
                    packageName == null
                            ? packagesForUid(target.uid())
                            : Collections.singleton(packageName),
                    null);
        }
        if (packageName != null) {
            if (!policiesByPackage.containsKey(packageName)) {
                return resolution(
                        IdentityResolutionStatus.UNMANAGED,
                        target,
                        null,
                        Collections.singleton(packageName),
                        null);
            }
            if (indexedAppId == null) {
                return resolution(
                        IdentityResolutionStatus.UNKNOWN,
                        target,
                        null,
                        Collections.singleton(packageName),
                        null);
            }
            if (!uidCandidates.isEmpty() && !uidCandidates.contains(packageName)) {
                return resolution(
                        IdentityResolutionStatus.UID_MISMATCH,
                        target,
                        null,
                        Collections.singleton(packageName),
                        null);
            }
        }
        return resolveUidCandidates(
                target, uidCandidates, policyScope);
    }

    private ResidencyIdentityResolution resolveUidCandidates(
            ResidencyTarget target,
            Set<String> uidCandidates,
            UidPolicyScope policyScope) {
        if (uidCandidates.isEmpty()) {
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
        if (managedPolicies.size() != uidCandidates.size()) {
            return resolution(
                    IdentityResolutionStatus.SHARED_UID_CONFLICT,
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
            if (!sameEffectivePolicy(
                    representative, managedPolicies.get(i), policyScope)) {
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

    private Set<String> packagesForUid(int uid) {
        Set<String> packages = uidPackages.get(uid);
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
            AppResidencyPolicy second,
            UidPolicyScope policyScope) {
        boolean firstSystemManaged = first.mode() == ResidencyMode.SYSTEM;
        boolean secondSystemManaged = second.mode() == ResidencyMode.SYSTEM;
        if (first.enabled() != second.enabled()
                || firstSystemManaged != secondSystemManaged) {
            return false;
        }
        if (!first.enabled() || firstSystemManaged) return true;
        switch (policyScope) {
            case NORMAL_FREEZE:
                return sameFreezeAction(
                        first.freezePolicy(), second.freezePolicy(), FreezeSource.NORMAL);
            case FAST_FREEZE:
                return sameFreezeAction(
                        first.freezePolicy(), second.freezePolicy(), FreezeSource.FAST);
            case SUPER_FREEZE:
                return sameFreezeAction(
                        first.freezePolicy(), second.freezePolicy(), FreezeSource.SUPER);
            case PRELOAD_FREEZE:
                return sameFreezeAction(
                        first.freezePolicy(), second.freezePolicy(), FreezeSource.PRELOAD);
            case R_TO_M_TIMING:
                return sameFreezeTiming(
                        first.freezePolicy(), second.freezePolicy(), true);
            case M_TO_F_TIMING:
                return sameFreezeTiming(
                        first.freezePolicy(), second.freezePolicy(), false);
            case CORE_EXEMPTION:
                return first.freezePolicy().fullExemption()
                        == second.freezePolicy().fullExemption();
            case LCD_ON_EXEMPTION:
                return isLcdOnExempt(first.freezePolicy())
                        == isLcdOnExempt(second.freezePolicy());
            case FULL:
            default:
                return first.killPolicy().equals(second.killPolicy())
                        && first.freezePolicy().equals(second.freezePolicy())
                        && first.wakePolicy().equals(second.wakePolicy())
                        && first.resourcePolicy().equals(second.resourcePolicy())
                        && first.refreezePolicy().equals(second.refreezePolicy());
        }
    }

    private static boolean sameFreezeAction(
            FreezePolicy first, FreezePolicy second, FreezeSource source) {
        if (first.fullExemption() != second.fullExemption()) return false;
        if (first.fullExemption()) return true;
        if (first.allows(source) != second.allows(source)) return false;
        if (!first.allows(source)) return true;
        if (first.customTiming() != second.customTiming()) return false;
        if (!first.customTiming()) return true;
        return first.rToMDelayMs() == second.rToMDelayMs()
                && first.mToFDelayMs() == second.mToFDelayMs();
    }

    private static boolean sameFreezeTiming(
            FreezePolicy first, FreezePolicy second, boolean rToM) {
        boolean firstApplies = !first.fullExemption() && first.customTiming();
        boolean secondApplies = !second.fullExemption() && second.customTiming();
        if (firstApplies != secondApplies) return false;
        if (!firstApplies) return true;
        return rToM
                ? first.rToMDelayMs() == second.rToMDelayMs()
                : first.mToFDelayMs() == second.mToFDelayMs();
    }

    private static boolean isLcdOnExempt(FreezePolicy policy) {
        return policy.fullExemption() || policy.lcdOnExemption();
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
    public Map<Integer, Set<String>> uidPackages() { return uidPackages; }
}
