package com.frostwork.bgenhance.hooks.hans;

import android.util.SparseArray;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the complete cgroup-v2 FAST candidate gate before OEM bookkeeping. */
public final class HansFastFreezeRuntime {
    static final String SCENE_CLASS =
            "com.android.server.hans.scene.HansSceneManager";
    static final String CGROUP_CLASS =
            "com.android.server.hans.freeze.HansCGroup";
    static final String PACKAGE_CLASS =
            "com.android.server.hans.OplusHansPackage";

    private HansFastFreezeRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        ClassLookup scene = load(SCENE_CLASS, loader);
        ClassLookup cgroup = load(CGROUP_CLASS, loader);
        ClassLookup packageType = load(PACKAGE_CLASS, loader);
        EnumMap<Capability, Entry> entries = new EnumMap<>(Capability.class);

        Status packageStatus = classStatus(packageType);
        if (packageStatus == Status.AVAILABLE
                && !HansObjectAdapter.prepare(packageType.type)) {
            packageStatus = Status.ACCESSOR_UNAVAILABLE;
        }
        entries.put(Capability.PACKAGE_ACCESS, new Entry(packageStatus, null));
        entries.put(Capability.ENTER_FAST_FREEZER, resolve(
                Capability.ENTER_FAST_FREEZER,
                scene,
                "enterFastFreezer",
                String.class,
                int.class,
                String.class,
                int.class,
                String.class));
        entries.put(Capability.ENTER_FAST_FREEZER_BRIDGE, resolveBridge(scene));
        entries.put(Capability.FILTER_FAST_FREEZER_CANDIDATES, resolve(
                Capability.FILTER_FAST_FREEZER_CANDIDATES,
                scene,
                "fastFreezeAppList",
                ArrayList.class,
                SparseArray.class));
        entries.put(Capability.FAST_FREEZE_V2_TERMINAL, resolve(
                Capability.FAST_FREEZE_V2_TERMINAL,
                cgroup,
                "FastFreezeEnter",
                void.class,
                int.class));
        return new CapabilityMatrix(entries);
    }

    public static boolean matches(Capability capability, Method method) {
        if (capability == null || method == null) return false;
        String owner = method.getDeclaringClass().getName();
        Class<?>[] parameters = method.getParameterTypes();
        switch (capability) {
            case ENTER_FAST_FREEZER:
                return SCENE_CLASS.equals(owner)
                        && Modifier.isPrivate(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("enterFastFreezer")
                        && method.getReturnType() == String.class
                        && types(parameters,
                                int.class, String.class, int.class, String.class);
            case ENTER_FAST_FREEZER_BRIDGE:
                return SCENE_CLASS.equals(owner)
                        && Modifier.isStatic(method.getModifiers())
                        && method.getName().endsWith("enterFastFreezer")
                        && method.getReturnType() == String.class
                        && types(parameters,
                                method.getDeclaringClass(),
                                int.class, String.class, int.class, String.class);
            case FILTER_FAST_FREEZER_CANDIDATES:
                return SCENE_CLASS.equals(owner)
                        && Modifier.isPrivate(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("fastFreezeAppList")
                        && method.getReturnType() == ArrayList.class
                        && types(parameters, SparseArray.class);
            case FAST_FREEZE_V2_TERMINAL:
                return CGROUP_CLASS.equals(owner)
                        && Modifier.isPublic(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("FastFreezeEnter")
                        && method.getReturnType() == void.class
                        && types(parameters, int.class);
            case PACKAGE_ACCESS:
            default:
                return false;
        }
    }

    private static Entry resolveBridge(ClassLookup owner) {
        Status ownerStatus = classStatus(owner);
        if (ownerStatus != Status.AVAILABLE) return new Entry(ownerStatus, null);
        try {
            Method resolved = null;
            for (Method method : owner.type.getDeclaredMethods()) {
                if (!matches(Capability.ENTER_FAST_FREEZER_BRIDGE, method)) continue;
                if (resolved != null) return new Entry(Status.INVALID_SIGNATURE, null);
                resolved = method;
            }
            return resolved == null
                    ? new Entry(Status.MISSING_METHOD, null)
                    : new Entry(Status.AVAILABLE, resolved);
        } catch (LinkageError | RuntimeException error) {
            return new Entry(Status.LOOKUP_FAILED, null);
        }
    }

    private static Entry resolve(
            Capability capability,
            ClassLookup owner,
            String name,
            Class<?> returnType,
            Class<?>... parameters) {
        Status ownerStatus = classStatus(owner);
        if (ownerStatus != Status.AVAILABLE) return new Entry(ownerStatus, null);
        try {
            Method method = owner.type.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != returnType || !matches(capability, method)) {
                return new Entry(Status.INVALID_SIGNATURE, null);
            }
            return new Entry(Status.AVAILABLE, method);
        } catch (NoSuchMethodException error) {
            return new Entry(Status.MISSING_METHOD, null);
        } catch (LinkageError | RuntimeException error) {
            return new Entry(Status.LOOKUP_FAILED, null);
        }
    }

    private static ClassLookup load(String className, ClassLoader loader) {
        try {
            return ClassLookup.available(Class.forName(className, false, loader));
        } catch (ClassNotFoundException error) {
            return ClassLookup.failed(Status.MISSING_CLASS);
        } catch (LinkageError | RuntimeException error) {
            return ClassLookup.failed(Status.LOOKUP_FAILED);
        }
    }

    private static Status classStatus(ClassLookup lookup) {
        return lookup.type == null ? lookup.failure : Status.AVAILABLE;
    }

    private static boolean types(Class<?>[] actual, Class<?>... expected) {
        if (actual.length != expected.length) return false;
        for (int index = 0; index < actual.length; index++) {
            if (actual[index] != expected[index]) return false;
        }
        return true;
    }

    public enum Capability {
        PACKAGE_ACCESS,
        ENTER_FAST_FREEZER,
        ENTER_FAST_FREEZER_BRIDGE,
        FILTER_FAST_FREEZER_CANDIDATES,
        FAST_FREEZE_V2_TERMINAL
    }

    public enum Status {
        AVAILABLE,
        MISSING_CLASS,
        MISSING_METHOD,
        INVALID_SIGNATURE,
        ACCESSOR_UNAVAILABLE,
        LOOKUP_FAILED
    }

    public static final class CapabilityMatrix {
        private final Map<Capability, Entry> entries;

        private CapabilityMatrix(EnumMap<Capability, Entry> entries) {
            this.entries = Collections.unmodifiableMap(new EnumMap<>(entries));
        }

        public Status status(Capability capability) {
            Entry entry = entries.get(Objects.requireNonNull(capability, "capability"));
            return entry == null ? Status.LOOKUP_FAILED : entry.status;
        }

        public Method methodOrNull(Capability capability) {
            Entry entry = entries.get(Objects.requireNonNull(capability, "capability"));
            return entry == null ? null : entry.method;
        }

        public boolean allAvailable() {
            for (Capability capability : Capability.values()) {
                if (status(capability) != Status.AVAILABLE) return false;
            }
            return true;
        }

        public String summary() {
            StringBuilder value = new StringBuilder();
            for (Capability capability : Capability.values()) {
                if (value.length() > 0) value.append(',');
                value.append(capability.name().toLowerCase(Locale.ROOT))
                        .append('=')
                        .append(status(capability).name());
            }
            return value.toString();
        }
    }

    private static final class Entry {
        private final Status status;
        private final Method method;

        private Entry(Status status, Method method) {
            this.status = Objects.requireNonNull(status, "status");
            this.method = method;
        }
    }

    private static final class ClassLookup {
        private final Class<?> type;
        private final Status failure;

        private ClassLookup(Class<?> type, Status failure) {
            this.type = type;
            this.failure = failure;
        }

        private static ClassLookup available(Class<?> type) {
            return new ClassLookup(Objects.requireNonNull(type, "type"), null);
        }

        private static ClassLookup failed(Status failure) {
            return new ClassLookup(null, Objects.requireNonNull(failure, "failure"));
        }
    }
}
