package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reads an OplusHansPackage using pre-resolved, in-memory accessors only. */
public final class HansObjectAdapter {
    private static final ConcurrentMap<Class<?>, AccessorSet> ACCESSORS =
            new ConcurrentHashMap<>();

    private HansObjectAdapter() {}

    /** Resolves all accessors before callbacks can enter a HANS package lock. */
    public static boolean prepare(Class<?> packageType) {
        return packageType != null && prepareAccessors(packageType).isReady();
    }

    public static Resolution resolve(Object hansPackage) {
        if (hansPackage == null) return Resolution.failed(Failure.NULL_PACKAGE);

        AccessorSet set = preparedAccessors(hansPackage.getClass());
        if (set == null) return Resolution.failed(Failure.ACCESSOR_NOT_PREPARED);
        if (!set.isReady()) return Resolution.failed(set.failure);
        try {
            Object uidValue = set.accessors.uid.read(hansPackage);
            if (!(uidValue instanceof Integer) || ((Integer) uidValue) < 0) {
                return Resolution.failed(Failure.INVALID_UID);
            }

            Object packageValue = set.accessors.packageName.read(hansPackage);
            if (!(packageValue instanceof String)
                    || !isNonBlank((String) packageValue)) {
                return Resolution.failed(Failure.INVALID_PACKAGE_NAME);
            }

            Object frozenValue = set.accessors.frozen.read(hansPackage);
            if (!(frozenValue instanceof Boolean)) {
                return Resolution.failed(Failure.INVALID_FROZEN_STATE);
            }

            Object superFreezeValue = set.accessors.superFreezeMode.read(hansPackage);
            if (!(superFreezeValue instanceof Boolean)) {
                return Resolution.failed(Failure.INVALID_SUPER_FREEZE_MODE);
            }

            if (set.accessors.lock.read(hansPackage) == null) {
                return Resolution.failed(Failure.INVALID_LOCK);
            }

            return Resolution.resolved(new Target(
                    (Integer) uidValue,
                    (String) packageValue,
                    (Boolean) frozenValue,
                    (Boolean) superFreezeValue));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return Resolution.failed(Failure.REFLECTION_FAILED);
        }
    }

    private static AccessorSet prepareAccessors(Class<?> type) {
        AccessorSet cached = ACCESSORS.get(type);
        if (cached != null) return cached;
        for (Class<?> current = type.getSuperclass();
                current != null;
                current = current.getSuperclass()) {
            AccessorSet inherited = ACCESSORS.get(current);
            if (inherited != null && inherited.isReady()) {
                AccessorSet raced = ACCESSORS.putIfAbsent(type, inherited);
                return raced != null ? raced : inherited;
            }
        }
        AccessorSet loaded = load(type);
        AccessorSet raced = ACCESSORS.putIfAbsent(type, loaded);
        return raced != null ? raced : loaded;
    }

    private static AccessorSet preparedAccessors(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            AccessorSet prepared = ACCESSORS.get(current);
            if (prepared != null) return prepared;
        }
        return null;
    }

    private static AccessorSet load(Class<?> type) {
        try {
            return AccessorSet.ready(new Accessors(
                    findAccessor(type, "getUid", int.class, "uid", int.class),
                    findAccessor(type, "getPkgName", String.class,
                            "pkgName", String.class),
                    findAccessor(type, "isFrozen", boolean.class,
                            "frozen", boolean.class),
                    findAccessor(type, "getSuperFRZMode", boolean.class,
                            "isSuperFRZMode", boolean.class),
                    findAccessor(type, "getLock", Object.class,
                            "mLock", Object.class)));
        } catch (NoSuchMethodException | NoSuchFieldException error) {
            return AccessorSet.failed(Failure.ACCESSOR_MISSING);
        } catch (InvalidAccessorException error) {
            return AccessorSet.failed(Failure.ACCESSOR_INVALID);
        } catch (RuntimeException error) {
            return AccessorSet.failed(Failure.REFLECTION_FAILED);
        }
    }

    private static ValueAccessor findAccessor(
            Class<?> type,
            String methodName,
            Class<?> returnType,
            String fieldName,
            Class<?> fieldType)
            throws NoSuchMethodException, NoSuchFieldException, InvalidAccessorException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                if (method.getReturnType() != returnType
                        || !Modifier.isPublic(method.getModifiers())
                        || Modifier.isStatic(method.getModifiers())) {
                    throw new InvalidAccessorException();
                }
                method.setAccessible(true);
                return method::invoke;
            } catch (NoSuchMethodException ignored) {
                // Vendor package accessors may be inherited by a firmware-specific subtype.
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (field.getType() != fieldType || Modifier.isStatic(field.getModifiers())) {
                    throw new InvalidAccessorException();
                }
                field.setAccessible(true);
                return field::get;
            } catch (NoSuchFieldException ignored) {
                // A field fallback is selected once during installation, never per callback.
            }
        }
        throw new NoSuchMethodException(type.getName() + '#' + methodName + "()");
    }

    private static boolean isNonBlank(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return true;
        }
        return false;
    }

    public enum Failure {
        NULL_PACKAGE,
        ACCESSOR_NOT_PREPARED,
        ACCESSOR_MISSING,
        ACCESSOR_INVALID,
        REFLECTION_FAILED,
        INVALID_UID,
        INVALID_PACKAGE_NAME,
        INVALID_FROZEN_STATE,
        INVALID_SUPER_FREEZE_MODE,
        INVALID_LOCK
    }

    public static final class Target {
        private final int uid;
        private final String packageName;
        private final boolean frozen;
        private final boolean superFreezeMode;

        private Target(
                int uid, String packageName, boolean frozen, boolean superFreezeMode) {
            this.uid = uid;
            this.packageName = Objects.requireNonNull(packageName, "packageName");
            this.frozen = frozen;
            this.superFreezeMode = superFreezeMode;
        }

        public int uid() { return uid; }
        public String packageName() { return packageName; }
        public boolean frozen() { return frozen; }
        public boolean superFreezeMode() { return superFreezeMode; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Target)) return false;
            Target that = (Target) other;
            return uid == that.uid
                    && frozen == that.frozen
                    && superFreezeMode == that.superFreezeMode
                    && packageName.equals(that.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uid, packageName, frozen, superFreezeMode);
        }
    }

    public static final class Resolution {
        private final Target target;
        private final Failure failure;

        private Resolution(Target target, Failure failure) {
            this.target = target;
            this.failure = failure;
        }

        private static Resolution resolved(Target target) {
            return new Resolution(target, null);
        }

        private static Resolution failed(Failure failure) {
            return new Resolution(null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean isResolved() { return target != null; }
        public Target targetOrNull() { return target; }
        public Failure failureOrNull() { return failure; }
    }

    private static final class Accessors {
        private final ValueAccessor uid;
        private final ValueAccessor packageName;
        private final ValueAccessor frozen;
        private final ValueAccessor superFreezeMode;
        private final ValueAccessor lock;

        private Accessors(
                ValueAccessor uid,
                ValueAccessor packageName,
                ValueAccessor frozen,
                ValueAccessor superFreezeMode,
                ValueAccessor lock) {
            this.uid = uid;
            this.packageName = packageName;
            this.frozen = frozen;
            this.superFreezeMode = superFreezeMode;
            this.lock = lock;
        }
    }

    private interface ValueAccessor {
        Object read(Object target) throws ReflectiveOperationException;
    }

    private static final class AccessorSet {
        private final Accessors accessors;
        private final Failure failure;

        private AccessorSet(Accessors accessors, Failure failure) {
            this.accessors = accessors;
            this.failure = failure;
        }

        private static AccessorSet ready(Accessors accessors) {
            return new AccessorSet(accessors, null);
        }

        private static AccessorSet failed(Failure failure) {
            return new AccessorSet(null, failure);
        }

        private boolean isReady() { return accessors != null; }
    }

    private static final class InvalidAccessorException extends Exception {}
}
