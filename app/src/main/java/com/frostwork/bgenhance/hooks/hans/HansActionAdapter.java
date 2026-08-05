package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Resolves an Action's HANS package through an installation-time field accessor. */
public final class HansActionAdapter {
    private static final String PACKAGE_FIELD = "mHansPackage";
    private static final ConcurrentMap<Class<?>, AccessorSet> ACCESSORS =
            new ConcurrentHashMap<>();

    private HansActionAdapter() {}

    /** Prepares the exact protected final field before any Action callback runs. */
    public static boolean prepare(Class<?> actionType, Class<?> packageType) {
        if (actionType == null || packageType == null) return false;
        return prepareAccessors(actionType, packageType).isReady();
    }

    public static Resolution resolve(Object action) {
        if (action == null) return Resolution.failed(Failure.NULL_ACTION);

        AccessorSet set = preparedAccessors(action.getClass());
        if (set == null) return Resolution.failed(Failure.ACCESSOR_NOT_PREPARED);
        if (!set.isReady()) return Resolution.failed(set.failure);
        try {
            Object hansPackage = set.packageField.get(action);
            if (hansPackage == null) {
                return Resolution.failed(Failure.NULL_PACKAGE);
            }
            if (!set.packageType.isInstance(hansPackage)) {
                return Resolution.failed(Failure.INVALID_PACKAGE_TYPE);
            }

            HansObjectAdapter.Resolution packageResolution =
                    HansObjectAdapter.resolve(hansPackage);
            if (!packageResolution.isResolved()) {
                return Resolution.packageFailed(packageResolution.failureOrNull());
            }
            return Resolution.resolved(
                    packageResolution.targetOrNull(), hansPackage);
        } catch (IllegalAccessException | RuntimeException error) {
            return Resolution.failed(Failure.REFLECTION_FAILED);
        }
    }

    private static AccessorSet prepareAccessors(
            Class<?> actionType, Class<?> packageType) {
        AccessorSet cached = ACCESSORS.get(actionType);
        if (cached != null) {
            return cached.packageType == packageType
                    ? cached
                    : AccessorSet.failed(Failure.ACCESSOR_INVALID, packageType);
        }
        AccessorSet loaded = load(actionType, packageType);
        AccessorSet raced = ACCESSORS.putIfAbsent(actionType, loaded);
        AccessorSet selected = raced != null ? raced : loaded;
        return selected.packageType == packageType
                ? selected
                : AccessorSet.failed(Failure.ACCESSOR_INVALID, packageType);
    }

    private static AccessorSet preparedAccessors(Class<?> type) {
        AccessorSet firstFailure = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            AccessorSet prepared = ACCESSORS.get(current);
            if (prepared == null) continue;
            if (prepared.isReady()) return prepared;
            if (firstFailure == null) firstFailure = prepared;
        }
        return firstFailure;
    }

    private static AccessorSet load(Class<?> actionType, Class<?> packageType) {
        try {
            Field field = actionType.getDeclaredField(PACKAGE_FIELD);
            int modifiers = field.getModifiers();
            if (field.getType() != packageType
                    || Modifier.isStatic(modifiers)
                    || !Modifier.isProtected(modifiers)
                    || !Modifier.isFinal(modifiers)) {
                return AccessorSet.failed(Failure.ACCESSOR_INVALID, packageType);
            }
            if (!HansObjectAdapter.prepare(packageType)) {
                return AccessorSet.failed(
                        Failure.PACKAGE_ACCESSOR_UNAVAILABLE, packageType);
            }
            field.setAccessible(true);
            return AccessorSet.ready(field, packageType);
        } catch (NoSuchFieldException error) {
            return AccessorSet.failed(Failure.ACCESSOR_MISSING, packageType);
        } catch (RuntimeException error) {
            return AccessorSet.failed(Failure.REFLECTION_FAILED, packageType);
        }
    }

    public enum Failure {
        NULL_ACTION,
        ACCESSOR_NOT_PREPARED,
        ACCESSOR_MISSING,
        ACCESSOR_INVALID,
        PACKAGE_ACCESSOR_UNAVAILABLE,
        NULL_PACKAGE,
        INVALID_PACKAGE_TYPE,
        PACKAGE_RESOLUTION_FAILED,
        REFLECTION_FAILED
    }

    public static final class Resolution {
        private final HansObjectAdapter.Target target;
        private final Object packageObject;
        private final Failure failure;
        private final HansObjectAdapter.Failure packageFailure;

        private Resolution(
                HansObjectAdapter.Target target,
                Object packageObject,
                Failure failure,
                HansObjectAdapter.Failure packageFailure) {
            this.target = target;
            this.packageObject = packageObject;
            this.failure = failure;
            this.packageFailure = packageFailure;
        }

        private static Resolution resolved(
                HansObjectAdapter.Target target, Object packageObject) {
            return new Resolution(
                    Objects.requireNonNull(target, "target"),
                    Objects.requireNonNull(packageObject, "packageObject"),
                    null,
                    null);
        }

        private static Resolution failed(Failure failure) {
            return new Resolution(
                    null, null, Objects.requireNonNull(failure, "failure"), null);
        }

        private static Resolution packageFailed(
                HansObjectAdapter.Failure packageFailure) {
            return new Resolution(
                    null,
                    null,
                    Failure.PACKAGE_RESOLUTION_FAILED,
                    Objects.requireNonNull(packageFailure, "packageFailure"));
        }

        public boolean isResolved() { return target != null; }
        public HansObjectAdapter.Target targetOrNull() { return target; }
        public Object packageObjectOrNull() { return packageObject; }
        public Failure failureOrNull() { return failure; }
        public HansObjectAdapter.Failure packageFailureOrNull() {
            return packageFailure;
        }
    }

    private static final class AccessorSet {
        private final Field packageField;
        private final Class<?> packageType;
        private final Failure failure;

        private AccessorSet(
                Field packageField, Class<?> packageType, Failure failure) {
            this.packageField = packageField;
            this.packageType = Objects.requireNonNull(packageType, "packageType");
            this.failure = failure;
        }

        private static AccessorSet ready(Field packageField, Class<?> packageType) {
            return new AccessorSet(
                    Objects.requireNonNull(packageField, "packageField"),
                    packageType,
                    null);
        }

        private static AccessorSet failed(Failure failure, Class<?> packageType) {
            return new AccessorSet(
                    null, packageType, Objects.requireNonNull(failure, "failure"));
        }

        private boolean isReady() { return packageField != null; }
    }
}
