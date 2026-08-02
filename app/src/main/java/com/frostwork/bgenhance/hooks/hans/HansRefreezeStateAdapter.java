package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reads HANS state-machine ownership using accessors resolved during hook installation. */
public final class HansRefreezeStateAdapter {
    private static final ConcurrentMap<Class<?>, AccessorSet> ACCESSORS =
            new ConcurrentHashMap<>();

    private HansRefreezeStateAdapter() {}

    public static boolean prepare(
            Class<?> stateMachineType,
            Class<?> middleStateType,
            Class<?> frozenStateType,
            Class<?> packageType) {
        if (stateMachineType == null
                || middleStateType == null
                || frozenStateType == null
                || packageType == null) {
            return false;
        }
        try {
            Accessors shared = load(
                    stateMachineType, middleStateType, frozenStateType, packageType);
            if (shared == null || !HansObjectAdapter.prepare(packageType)) return false;
            AccessorSet ready = AccessorSet.ready(shared);
            ACCESSORS.put(middleStateType, ready);
            ACCESSORS.put(frozenStateType, ready);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return false;
        }
    }

    public static Resolution resolve(Object state) {
        if (state == null) return Resolution.failed(Failure.NULL_STATE);
        AccessorSet set = ACCESSORS.get(state.getClass());
        if (set == null) return Resolution.failed(Failure.ACCESSOR_NOT_PREPARED);
        if (set.accessors == null) return Resolution.failed(Failure.ACCESSOR_INVALID);
        try {
            Object stateMachine = set.accessors.outer.read(state);
            if (stateMachine == null) return Resolution.failed(Failure.NULL_STATE_MACHINE);
            Object packageObject = set.accessors.hansPackage.get(stateMachine);
            HansObjectAdapter.Resolution packageResolution =
                    HansObjectAdapter.resolve(packageObject);
            if (!packageResolution.isResolved()) {
                return Resolution.failed(Failure.PACKAGE_UNRESOLVED);
            }
            Object reasonValue = set.accessors.unfreezeReason.get(stateMachine);
            if (reasonValue != null && !(reasonValue instanceof String)) {
                return Resolution.failed(Failure.INVALID_REASON);
            }
            return Resolution.resolved(
                    stateMachine,
                    packageResolution.targetOrNull(),
                    (String) reasonValue);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return Resolution.failed(Failure.REFLECTION_FAILED);
        }
    }

    private static Accessors load(
            Class<?> stateMachineType,
            Class<?> middleStateType,
            Class<?> frozenStateType,
            Class<?> packageType) throws ReflectiveOperationException {
        if (middleStateType.getEnclosingClass() != stateMachineType
                || frozenStateType.getEnclosingClass() != stateMachineType) {
            return null;
        }
        Field middleOuter = exactOuterField(middleStateType, stateMachineType);
        Field frozenOuter = exactOuterField(frozenStateType, stateMachineType);
        Field packageField = exactPrivateField(
                stateMachineType, "mHansPkg", packageType);
        Field reasonField = exactPrivateField(
                stateMachineType, "mUnfreezeReason", String.class);
        middleOuter.setAccessible(true);
        frozenOuter.setAccessible(true);
        packageField.setAccessible(true);
        reasonField.setAccessible(true);
        return new Accessors(
                middleStateType,
                frozenStateType,
                middleOuter,
                frozenOuter,
                packageField,
                reasonField);
    }

    private static Field exactOuterField(Class<?> innerType, Class<?> outerType)
            throws NoSuchFieldException {
        Field match = null;
        for (Field field : innerType.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != outerType
                    || Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)
                    || !field.isSynthetic()) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException(innerType.getName() + " has multiple outer fields");
            }
            match = field;
        }
        if (match == null) {
            throw new NoSuchFieldException(innerType.getName() + " has no exact outer field");
        }
        return match;
    }

    private static Field exactPrivateField(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        int modifiers = field.getModifiers();
        if (field.getType() != type
                || !Modifier.isPrivate(modifiers)
                || Modifier.isStatic(modifiers)) {
            throw new NoSuchFieldException(owner.getName() + '#' + name);
        }
        return field;
    }

    public enum Failure {
        NULL_STATE,
        ACCESSOR_NOT_PREPARED,
        ACCESSOR_INVALID,
        NULL_STATE_MACHINE,
        PACKAGE_UNRESOLVED,
        INVALID_REASON,
        REFLECTION_FAILED
    }

    public static final class Resolution {
        private final Object stateMachine;
        private final HansObjectAdapter.Target target;
        private final String reason;
        private final Failure failure;

        private Resolution(
                Object stateMachine,
                HansObjectAdapter.Target target,
                String reason,
                Failure failure) {
            this.stateMachine = stateMachine;
            this.target = target;
            this.reason = reason;
            this.failure = failure;
        }

        private static Resolution resolved(
                Object stateMachine, HansObjectAdapter.Target target, String reason) {
            return new Resolution(
                    Objects.requireNonNull(stateMachine, "stateMachine"),
                    Objects.requireNonNull(target, "target"),
                    reason,
                    null);
        }

        private static Resolution failed(Failure failure) {
            return new Resolution(
                    null, null, null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean isResolved() { return stateMachine != null; }
        public Object stateMachineOrNull() { return stateMachine; }
        public HansObjectAdapter.Target targetOrNull() { return target; }
        public String reasonOrNull() { return reason; }
        public Failure failureOrNull() { return failure; }
    }

    private static final class Accessors {
        private final Class<?> middleStateType;
        private final Class<?> frozenStateType;
        private final Field middleOuter;
        private final Field frozenOuter;
        private final Field hansPackage;
        private final Field unfreezeReason;
        private final ValueAccessor outer;

        private Accessors(
                Class<?> middleStateType,
                Class<?> frozenStateType,
                Field middleOuter,
                Field frozenOuter,
                Field hansPackage,
                Field unfreezeReason) {
            this.middleStateType = middleStateType;
            this.frozenStateType = frozenStateType;
            this.middleOuter = middleOuter;
            this.frozenOuter = frozenOuter;
            this.hansPackage = hansPackage;
            this.unfreezeReason = unfreezeReason;
            this.outer = value -> {
                Class<?> type = value.getClass();
                if (type == this.middleStateType) return this.middleOuter.get(value);
                if (type == this.frozenStateType) return this.frozenOuter.get(value);
                throw new IllegalArgumentException("unexpected HANS state type");
            };
        }
    }

    private static final class AccessorSet {
        private final Accessors accessors;

        private AccessorSet(Accessors accessors) {
            this.accessors = accessors;
        }

        private static AccessorSet ready(Accessors accessors) {
            return new AccessorSet(Objects.requireNonNull(accessors, "accessors"));
        }
    }

    private interface ValueAccessor {
        Object read(Object value) throws ReflectiveOperationException;
    }
}
