package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves exact HANS firmware capabilities without exposing vendor types to policy code. */
public final class HansRuntime {
    private static final String PACKAGE_CLASS =
            "com.android.server.hans.OplusHansPackage";
    private static final String CONFIG_CLASS =
            "com.android.server.hans.OplusHansDBConfig";
    private static final String MANAGER_CLASS =
            "com.android.server.am.OplusHansManager";
    private static final String CGROUP_CLASS =
            "com.android.server.hans.freeze.HansCGroup";

    private HansRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        EnumMap<Capability, Entry> entries = new EnumMap<>(Capability.class);
        ClassLookup packageType = load(PACKAGE_CLASS, loader);
        ClassLookup configType = load(CONFIG_CLASS, loader);
        ClassLookup managerType = load(MANAGER_CLASS, loader);
        ClassLookup cgroupType = load(CGROUP_CLASS, loader);

        Status packageStatus = classStatus(packageType);
        if (packageStatus == Status.AVAILABLE
                && !HansObjectAdapter.prepare(packageType.type)) {
            packageStatus = Status.ACCESSOR_UNAVAILABLE;
        }
        entries.put(Capability.PACKAGE_ACCESS, new Entry(packageStatus, null));

        entries.put(Capability.R_TO_M_TIMING, resolve(
                Capability.R_TO_M_TIMING,
                configType,
                "getRtoMCheckTime",
                long.class,
                int.class, String.class, int.class, long.class));
        entries.put(Capability.M_TO_F_TIMING, resolve(
                Capability.M_TO_F_TIMING,
                configType,
                "getMtoFCheckTime",
                long.class,
                int.class, String.class, int.class, long.class, String.class));
        entries.put(Capability.CORE_EXEMPTION, resolve(
                Capability.CORE_EXEMPTION,
                managerType,
                "isHansCoreApp",
                boolean.class,
                int.class, String.class));
        entries.put(Capability.LCD_ON_EXEMPTION, resolve(
                Capability.LCD_ON_EXEMPTION,
                managerType,
                "isLcdOnNonRestrictPkg",
                boolean.class,
                int.class, String.class));
        entries.put(Capability.KERNEL_WAKE_7, resolve(
                Capability.KERNEL_WAKE_7,
                managerType,
                "unfreezeForKernel",
                void.class,
                int.class, int.class, int.class, int.class,
                int.class, String.class, int.class));

        Entry freeze = resolveWithVendorPackage(
                Capability.FREEZE_GATE,
                cgroupType,
                packageType,
                "hansFreezeLocked",
                String.class);
        Entry unfreeze = resolveWithVendorPackage(
                Capability.UNFREEZE_GATE,
                cgroupType,
                packageType,
                "hansUnfreezeLocked",
                String.class,
                String.class);
        Entry fastFreezeV2 = resolve(
                Capability.FAST_FREEZE_V2_PATH,
                cgroupType,
                "FastFreezeEnter",
                void.class,
                int.class);
        if (fastFreezeV2.status == Status.AVAILABLE) {
            fastFreezeV2 = new Entry(Status.DETECTED_UNCOVERED, null);
        }
        if (packageStatus != Status.AVAILABLE) {
            if (freeze.status == Status.AVAILABLE) {
                freeze = new Entry(Status.DEPENDENCY_UNAVAILABLE, null);
            }
            if (unfreeze.status == Status.AVAILABLE) {
                unfreeze = new Entry(Status.DEPENDENCY_UNAVAILABLE, null);
            }
        }
        entries.put(Capability.FREEZE_GATE, freeze);
        entries.put(Capability.UNFREEZE_GATE, unfreeze);
        entries.put(Capability.FAST_FREEZE_V2_PATH, fastFreezeV2);
        return new CapabilityMatrix(entries);
    }

    public static boolean matches(Capability capability, Method method) {
        if (capability == null
                || method == null
                || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        String owner = method.getDeclaringClass().getName();
        Class<?>[] parameters = method.getParameterTypes();
        switch (capability) {
            case R_TO_M_TIMING:
                return CONFIG_CLASS.equals(owner)
                        && method.getName().equals("getRtoMCheckTime")
                        && method.getReturnType() == long.class
                        && types(parameters, int.class, String.class, int.class, long.class);
            case M_TO_F_TIMING:
                return CONFIG_CLASS.equals(owner)
                        && method.getName().equals("getMtoFCheckTime")
                        && method.getReturnType() == long.class
                        && types(parameters, int.class, String.class, int.class,
                        long.class, String.class);
            case CORE_EXEMPTION:
                return MANAGER_CLASS.equals(owner)
                        && method.getName().equals("isHansCoreApp")
                        && method.getReturnType() == boolean.class
                        && types(parameters, int.class, String.class);
            case LCD_ON_EXEMPTION:
                return MANAGER_CLASS.equals(owner)
                        && method.getName().equals("isLcdOnNonRestrictPkg")
                        && method.getReturnType() == boolean.class
                        && types(parameters, int.class, String.class);
            case KERNEL_WAKE_7:
                return MANAGER_CLASS.equals(owner)
                        && method.getName().equals("unfreezeForKernel")
                        && method.getReturnType() == void.class
                        && types(parameters,
                        int.class, int.class, int.class, int.class,
                        int.class, String.class, int.class);
            case FREEZE_GATE:
                return CGROUP_CLASS.equals(owner)
                        && method.getName().equals("hansFreezeLocked")
                        && method.getReturnType() == boolean.class
                        && typeNames(parameters, PACKAGE_CLASS, String.class.getName());
            case UNFREEZE_GATE:
                return CGROUP_CLASS.equals(owner)
                        && method.getName().equals("hansUnfreezeLocked")
                        && method.getReturnType() == boolean.class
                        && typeNames(parameters, PACKAGE_CLASS,
                        String.class.getName(), String.class.getName());
            case FAST_FREEZE_V2_PATH:
                return CGROUP_CLASS.equals(owner)
                        && method.getName().equals("FastFreezeEnter")
                        && method.getReturnType() == void.class
                        && types(parameters, int.class);
            case PACKAGE_ACCESS:
            default:
                return false;
        }
    }

    private static Entry resolveWithVendorPackage(
            Capability capability,
            ClassLookup owner,
            ClassLookup packageType,
            String name,
            Class<?>... trailingParameters) {
        Status ownerStatus = classStatus(owner);
        if (ownerStatus != Status.AVAILABLE) return new Entry(ownerStatus, null);
        Status packageStatus = classStatus(packageType);
        if (packageStatus != Status.AVAILABLE) return new Entry(packageStatus, null);

        Class<?>[] parameters = new Class<?>[trailingParameters.length + 1];
        parameters[0] = packageType.type;
        System.arraycopy(
                trailingParameters, 0, parameters, 1, trailingParameters.length);
        return resolve(capability, owner, name, boolean.class, parameters);
    }

    private static Entry resolve(
            Capability capability,
            ClassLookup owner,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        Status ownerStatus = classStatus(owner);
        if (ownerStatus != Status.AVAILABLE) return new Entry(ownerStatus, null);
        try {
            Method method = owner.type.getDeclaredMethod(name, parameterTypes);
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

    private static boolean typeNames(Class<?>[] actual, String... expected) {
        if (actual.length != expected.length) return false;
        for (int index = 0; index < actual.length; index++) {
            if (!actual[index].getName().equals(expected[index])) return false;
        }
        return true;
    }

    public enum Capability {
        PACKAGE_ACCESS,
        R_TO_M_TIMING,
        M_TO_F_TIMING,
        CORE_EXEMPTION,
        LCD_ON_EXEMPTION,
        KERNEL_WAKE_7,
        FREEZE_GATE,
        UNFREEZE_GATE,
        FAST_FREEZE_V2_PATH
    }

    public enum Status {
        AVAILABLE,
        MISSING_CLASS,
        MISSING_METHOD,
        INVALID_SIGNATURE,
        ACCESSOR_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE,
        DETECTED_UNCOVERED,
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
