package com.frostwork.bgenhance.hooks.hans;

import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.residency.decision.FreezeContext;
import com.frostwork.bgenhance.residency.decision.FreezeDecision;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionContext;
import com.frostwork.bgenhance.residency.decision.FreezeExemptionDecision;
import com.frostwork.bgenhance.residency.decision.ResidencyDecisionEngine;
import com.frostwork.bgenhance.residency.decision.ResourceContext;
import com.frostwork.bgenhance.residency.decision.ResourceDecision;
import com.frostwork.bgenhance.residency.model.FreezeSource;
import com.frostwork.bgenhance.residency.model.ResourceType;
import com.frostwork.bgenhance.residency.runtime.ResidencyIdentityIndex;
import com.frostwork.bgenhance.residency.runtime.ResidencyRuntimeState;
import com.frostwork.bgenhance.residency.runtime.ResidencyTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Plans asynchronous HANS recovery only when effective physical policy changes. */
public final class HansActiveRecoveryPlanner {
    static final int PHYSICAL_BEHAVIOR_COUNT = 17;
    private static final int MAX_TRACKED_UIDS = 512;
    static final int MAX_FORCED_RECOVERIES = 512;

    private final Capabilities capabilities;
    private final LinkedHashMap<Integer, DesiredState> baseline = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, DesiredState> forcedRecoveries =
            new LinkedHashMap<>();

    public HansActiveRecoveryPlanner() {
        this(Capabilities.all());
    }

    public HansActiveRecoveryPlanner(Capabilities capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public boolean requireRecovery(
            int uid,
            Set<String> expectedPackages,
            long runtimeGeneration,
            long signature) {
        DesiredState desired = new DesiredState(
                uid, expectedPackages, runtimeGeneration, signature);
        if (!forcedRecoveries.containsKey(uid)
                && forcedRecoveries.size() >= MAX_FORCED_RECOVERIES) {
            return false;
        }
        baseline.put(uid, desired);
        forcedRecoveries.put(uid, desired);
        trimBaseline();
        return true;
    }

    public Update update(ResidencyRuntimeState state, int recoveryBudget) {
        Objects.requireNonNull(state, "state");
        if (recoveryBudget < 0) throw new IllegalArgumentException("recoveryBudget < 0");
        if (state.status() == ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING) {
            return Update.waiting(state.generation());
        }

        Map<Integer, DesiredState> desired;
        Update.Status updateStatus;
        try {
            if (state.status() == ResidencyRuntimeState.Status.READY) {
                desired = desiredReadyStates(state);
                updateStatus = Update.Status.STABLE;
            } else {
                desired = restoreSystemStates(state.generation());
                updateStatus = Update.Status.RECOVERY_TO_SYSTEM;
            }
        } catch (Throwable ignored) {
            desired = restoreSystemStates(state.generation());
            updateStatus = Update.Status.RECOVERY_TO_SYSTEM;
        }

        TreeSet<Integer> allUids = new TreeSet<>(baseline.keySet());
        allUids.addAll(desired.keySet());
        allUids.addAll(forcedRecoveries.keySet());
        List<Transition> transitions = new ArrayList<>();
        Map<Integer, DesiredState> resolved = new LinkedHashMap<>();
        for (Integer uid : allUids) {
            DesiredState previous = baseline.get(uid);
            DesiredState next = desired.get(uid);
            DesiredState forced = forcedRecoveries.get(uid);
            if (next == null && previous != null) {
                next = previous.followSystem(state.generation());
            } else if (next == null && forced != null) {
                next = forced.followSystem(state.generation());
            }
            if (next == null) continue;
            resolved.put(uid, next);
            if (requiresRecovery(previous, next)
                    || (forced != null && forced.samePhysicalState(next))) {
                transitions.add(new Transition(next));
            } else if (forced != null) {
                forcedRecoveries.remove(uid);
            }
        }
        transitions.sort(Comparator
                .comparing((Transition transition) -> transition.next.signature() != 0L)
                .thenComparingInt(transition -> transition.next.uid()));

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        for (Transition transition : transitions) {
            if (selected.size() >= recoveryBudget) break;
            selected.add(transition.next.uid());
        }

        List<RecoveryIntent> intents = new ArrayList<>();
        for (Map.Entry<Integer, DesiredState> entry : resolved.entrySet()) {
            int uid = entry.getKey();
            DesiredState next = entry.getValue();
            DesiredState previous = baseline.get(uid);
            DesiredState forced = forcedRecoveries.get(uid);
            boolean needsRecovery = requiresRecovery(previous, next)
                    || (forced != null && forced.samePhysicalState(next));
            if (needsRecovery && !selected.contains(uid)) continue;
            baseline.put(uid, next);
            if (needsRecovery) {
                forcedRecoveries.remove(uid);
                intents.add(new RecoveryIntent(next));
            }
        }
        trimBaseline();
        boolean moreWork = transitions.size() > selected.size();
        return new Update(updateStatus, state.generation(), intents, moreWork);
    }

    public Revalidation revalidate(
            ResidencyRuntimeState state,
            int uid,
            Set<String> fallbackPackages) {
        Objects.requireNonNull(state, "state");
        Set<String> packages = immutablePackages(fallbackPackages);
        if (uid < 0 || packages.isEmpty()) return Revalidation.unavailable();
        if (state.status() == ResidencyRuntimeState.Status.IDENTITY_REBUILD_PENDING) {
            return Revalidation.waiting();
        }
        if (state.status() != ResidencyRuntimeState.Status.READY) {
            return Revalidation.stable(new DesiredState(
                    uid, packages, state.generation(), 0L));
        }
        try {
            validateReadyState(state);
            Set<String> currentPackages = state.identityIndex().uidPackages().get(uid);
            if (currentPackages == null || currentPackages.isEmpty()) {
                return Revalidation.stable(new DesiredState(
                        uid, packages, state.generation(), 0L));
            }
            return Revalidation.stable(desiredState(state, uid, currentPackages));
        } catch (Throwable ignored) {
            return Revalidation.unavailable();
        }
    }

    private Map<Integer, DesiredState> desiredReadyStates(ResidencyRuntimeState state) {
        validateReadyState(state);
        Map<Integer, DesiredState> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry
                : state.identityIndex().uidPackages().entrySet()) {
            int uid = entry.getKey();
            if (uid < 0 || entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalStateException("invalid UID identity");
            }
            result.put(uid, desiredState(state, uid, entry.getValue()));
        }
        return result;
    }

    private static void validateReadyState(ResidencyRuntimeState state) {
        ResidencyIdentityIndex index = state.identityIndex();
        if (!state.ready()
                || !state.snapshot().available()
                || index.isInvalidated()
                || state.snapshot().policyRevision() != index.policyRevision()) {
            throw new IllegalStateException("inconsistent ready runtime");
        }
    }

    private DesiredState desiredState(
            ResidencyRuntimeState state, int uid, Set<String> expectedPackages) {
        ResidencyDecisionEngine engine = state.decisionEngine();
        ResidencyTarget target = ResidencyTarget.forUid(uid);
        long signature = 0L;
        for (FreezeSource source : FreezeSource.values()) {
            if (!capabilities.supportsFreeze(source)) continue;
            FreezeDecision decision = engine.decideFreeze(new FreezeContext(
                    target,
                    source,
                    FeatureIds.HANS,
                    HookTargetIds.HANS_FREEZE_DISPATCH));
            if (decision.effectiveAction() == FreezeDecision.Action.BLOCK_FREEZE) {
                signature |= bit(source.ordinal());
            }
        }

        int offset = FreezeSource.values().length;
        if (capabilities.coreExemption()) {
            FreezeExemptionDecision core = engine.decideFreezeExemption(
                    new FreezeExemptionContext(
                            target,
                            FreezeExemptionContext.Kind.CORE,
                            FeatureIds.HANS,
                            HookTargetIds.HANS_CORE_EXEMPTION));
            if (core.effectiveAction() == FreezeExemptionDecision.Action.GRANT_EXEMPTION) {
                signature |= bit(offset);
            }
        }
        offset++;
        if (capabilities.lcdOnExemption()) {
            FreezeExemptionDecision lcd = engine.decideFreezeExemption(
                    new FreezeExemptionContext(
                            target,
                            FreezeExemptionContext.Kind.LCD_ON,
                            FeatureIds.HANS,
                            HookTargetIds.HANS_LCD_ON_EXEMPTION));
            if (lcd.effectiveAction() == FreezeExemptionDecision.Action.GRANT_EXEMPTION) {
                signature |= bit(offset);
            }
        }
        offset++;

        if (capabilities.network()) {
            ResourceDecision network = engine.decideResource(ResourceContext.network(
                    target,
                    FeatureIds.HANS_RESOURCE,
                    HookTargetIds.HANS_RESOURCE_DISPATCH));
            if (network.effectiveAction() == ResourceDecision.Action.KEEP_RESOURCE) {
                signature |= bit(offset);
            }
        }
        offset++;
        if (offset + ResourceType.values().length != PHYSICAL_BEHAVIOR_COUNT) {
            throw new AssertionError("physical behavior signature layout changed");
        }
        for (ResourceType resource : ResourceType.values()) {
            if (!capabilities.supportsResource(resource)) continue;
            ResourceDecision decision = engine.decideResource(ResourceContext.resource(
                    target,
                    resource,
                    FeatureIds.HANS_RESOURCE,
                    HookTargetIds.HANS_RESOURCE_DISPATCH));
            if (decision.effectiveAction() == ResourceDecision.Action.KEEP_RESOURCE) {
                signature |= bit(offset + resource.ordinal());
            }
        }
        return new DesiredState(
                uid, expectedPackages, state.generation(), signature);
    }

    private Map<Integer, DesiredState> restoreSystemStates(long generation) {
        Map<Integer, DesiredState> result = new LinkedHashMap<>();
        for (DesiredState previous : baseline.values()) {
            result.put(previous.uid(), previous.followSystem(generation));
        }
        return result;
    }

    private static boolean requiresRecovery(DesiredState previous, DesiredState next) {
        if (previous == null) return next.signature() != 0L;
        if (previous.samePhysicalState(next)) return false;
        return previous.signature() != 0L || next.signature() != 0L;
    }

    private void trimBaseline() {
        if (baseline.size() <= MAX_TRACKED_UIDS) return;
        List<Integer> removable = new ArrayList<>();
        for (Map.Entry<Integer, DesiredState> entry : baseline.entrySet()) {
            if (entry.getValue().signature() == 0L) removable.add(entry.getKey());
        }
        for (Integer uid : removable) {
            if (baseline.size() <= MAX_TRACKED_UIDS) break;
            baseline.remove(uid);
        }
        if (baseline.size() > MAX_TRACKED_UIDS) {
            throw new IllegalStateException("too many active UID policies");
        }
    }

    private static long bit(int index) {
        if (index < 0 || index >= Long.SIZE) throw new IllegalArgumentException("invalid bit");
        return 1L << index;
    }

    private static Set<String> immutablePackages(Set<String> source) {
        if (source == null || source.isEmpty()) return Collections.emptySet();
        TreeSet<String> packages = new TreeSet<>();
        for (String packageName : source) {
            if (packageName == null || packageName.isEmpty()) {
                throw new IllegalArgumentException("invalid package name");
            }
            packages.add(packageName);
        }
        return Collections.unmodifiableSet(packages);
    }

    public static final class DesiredState {
        private final int uid;
        private final Set<String> expectedPackages;
        private final long runtimeGeneration;
        private final long signature;

        private DesiredState(
                int uid,
                Set<String> expectedPackages,
                long runtimeGeneration,
                long signature) {
            if (uid < 0) throw new IllegalArgumentException("uid < 0");
            if (runtimeGeneration < 0L) {
                throw new IllegalArgumentException("runtimeGeneration < 0");
            }
            this.uid = uid;
            this.expectedPackages = immutablePackages(expectedPackages);
            if (this.expectedPackages.isEmpty()) {
                throw new IllegalArgumentException("expectedPackages is empty");
            }
            this.runtimeGeneration = runtimeGeneration;
            this.signature = signature;
        }

        private DesiredState followSystem(long generation) {
            return new DesiredState(uid, expectedPackages, generation, 0L);
        }

        public int uid() { return uid; }
        public Set<String> expectedPackages() { return expectedPackages; }
        public long runtimeGeneration() { return runtimeGeneration; }
        public long signature() { return signature; }
        public boolean samePhysicalState(DesiredState that) {
            return that != null
                    && signature == that.signature
                    && expectedPackages.equals(that.expectedPackages);
        }
    }

    public static final class RecoveryIntent {
        private final DesiredState desiredState;

        private RecoveryIntent(DesiredState desiredState) {
            this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
        }

        public DesiredState desiredState() { return desiredState; }
    }

    public static final class Update {
        public enum Status { WAITING, STABLE, RECOVERY_TO_SYSTEM }

        private final Status status;
        private final long runtimeGeneration;
        private final List<RecoveryIntent> intents;
        private final boolean moreWork;

        private Update(
                Status status,
                long runtimeGeneration,
                List<RecoveryIntent> intents,
                boolean moreWork) {
            this.status = Objects.requireNonNull(status, "status");
            this.runtimeGeneration = runtimeGeneration;
            this.intents = Collections.unmodifiableList(new ArrayList<>(intents));
            this.moreWork = moreWork;
        }

        private static Update waiting(long generation) {
            return new Update(Status.WAITING, generation, Collections.emptyList(), false);
        }

        public Status status() { return status; }
        public long runtimeGeneration() { return runtimeGeneration; }
        public List<RecoveryIntent> intents() { return intents; }
        public boolean moreWork() { return moreWork; }
    }

    public static final class Revalidation {
        public enum Status { WAITING, STABLE, UNAVAILABLE }

        private final Status status;
        private final DesiredState desiredState;

        private Revalidation(Status status, DesiredState desiredState) {
            this.status = status;
            this.desiredState = desiredState;
        }

        private static Revalidation waiting() {
            return new Revalidation(Status.WAITING, null);
        }

        private static Revalidation unavailable() {
            return new Revalidation(Status.UNAVAILABLE, null);
        }

        private static Revalidation stable(DesiredState desiredState) {
            return new Revalidation(Status.STABLE, desiredState);
        }

        public Status status() { return status; }
        public DesiredState desiredStateOrNull() { return desiredState; }
    }

    public static final class Capabilities {
        private final Set<FreezeSource> freezeSources;
        private final boolean coreExemption;
        private final boolean lcdOnExemption;
        private final boolean network;
        private final Set<ResourceType> resources;

        public Capabilities(
                Set<FreezeSource> freezeSources,
                boolean coreExemption,
                boolean lcdOnExemption,
                boolean network,
                Set<ResourceType> resources) {
            this.freezeSources = immutableEnumSet(freezeSources, FreezeSource.class);
            this.coreExemption = coreExemption;
            this.lcdOnExemption = lcdOnExemption;
            this.network = network;
            this.resources = immutableEnumSet(resources, ResourceType.class);
        }

        public static Capabilities all() {
            return new Capabilities(
                    java.util.EnumSet.allOf(FreezeSource.class),
                    true,
                    true,
                    true,
                    java.util.EnumSet.allOf(ResourceType.class));
        }

        public static Capabilities none() {
            return new Capabilities(
                    Collections.emptySet(), false, false, false, Collections.emptySet());
        }

        public boolean supportsFreeze(FreezeSource source) {
            return freezeSources.contains(Objects.requireNonNull(source, "source"));
        }

        public boolean coreExemption() { return coreExemption; }
        public boolean lcdOnExemption() { return lcdOnExemption; }
        public boolean network() { return network; }
        public boolean supportsResource(ResourceType resource) {
            return resources.contains(Objects.requireNonNull(resource, "resource"));
        }

        private static <E extends Enum<E>> Set<E> immutableEnumSet(
                Set<E> source, Class<E> type) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(type, "type");
            if (source.isEmpty()) return Collections.emptySet();
            java.util.EnumSet<E> copy = java.util.EnumSet.noneOf(type);
            for (E value : source) copy.add(Objects.requireNonNull(value, "capability"));
            return Collections.unmodifiableSet(copy);
        }
    }

    private static final class Transition {
        private final DesiredState next;

        private Transition(DesiredState next) {
            this.next = next;
        }
    }
}
