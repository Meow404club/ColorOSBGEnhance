package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the exact public HANS entry points needed for asynchronous policy recovery. */
public final class HansActiveRecoveryRuntime {
    static final String MANAGER_CLASS = "com.android.server.am.OplusHansManager";
    static final String CONFIG_CLASS = "com.android.server.hans.OplusHansDBConfig";
    static final String PACKAGE_CLASS = "com.android.server.hans.OplusHansPackage";

    private HansActiveRecoveryRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        ClassLookup manager = load(MANAGER_CLASS, loader);
        ClassLookup config = load(CONFIG_CLASS, loader);
        ClassLookup packageType = load(PACKAGE_CLASS, loader);
        EnumMap<Capability, Entry> entries = new EnumMap<>(Capability.class);

        Status packageStatus = classStatus(packageType);
        if (packageStatus == Status.AVAILABLE
                && !HansObjectAdapter.prepare(packageType.type)) {
            packageStatus = Status.ACCESSOR_UNAVAILABLE;
        }
        entries.put(Capability.PACKAGE_ACCESS, new Entry(packageStatus, null));
        entries.put(Capability.MANAGER_INSTANCE, resolve(
                Capability.MANAGER_INSTANCE,
                manager,
                "getInstance",
                manager.type,
                true));
        entries.put(Capability.MANAGER_READY, resolve(
                Capability.MANAGER_READY,
                manager,
                "isReady",
                boolean.class,
                false));
        entries.put(Capability.MANAGER_UNFREEZE, resolve(
                Capability.MANAGER_UNFREEZE,
                manager,
                "hansUnFreeze",
                boolean.class,
                false,
                int.class,
                String.class,
                String.class));
        entries.put(Capability.CONFIG_INSTANCE, resolve(
                Capability.CONFIG_INSTANCE,
                config,
                "getInstance",
                config.type,
                true));

        Entry configPackage = resolve(
                Capability.CONFIG_PACKAGE,
                config,
                "getHansPackage",
                packageType.type,
                false,
                int.class);
        if (packageStatus != Status.AVAILABLE
                && configPackage.status == Status.AVAILABLE) {
            configPackage = new Entry(Status.DEPENDENCY_UNAVAILABLE, null);
        }
        entries.put(Capability.CONFIG_PACKAGE, configPackage);
        return new CapabilityMatrix(entries);
    }

    public static boolean matches(Capability capability, Method method) {
        if (capability == null || method == null
                || !Modifier.isPublic(method.getModifiers())) {
            return false;
        }
        String owner = method.getDeclaringClass().getName();
        Class<?>[] parameters = method.getParameterTypes();
        switch (capability) {
            case MANAGER_INSTANCE:
                return MANAGER_CLASS.equals(owner)
                        && Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("getInstance")
                        && method.getReturnType().getName().equals(MANAGER_CLASS)
                        && parameters.length == 0;
            case MANAGER_READY:
                return MANAGER_CLASS.equals(owner)
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("isReady")
                        && method.getReturnType() == boolean.class
                        && parameters.length == 0;
            case MANAGER_UNFREEZE:
                return MANAGER_CLASS.equals(owner)
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("hansUnFreeze")
                        && method.getReturnType() == boolean.class
                        && types(parameters, int.class, String.class, String.class);
            case CONFIG_INSTANCE:
                return CONFIG_CLASS.equals(owner)
                        && Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("getInstance")
                        && method.getReturnType().getName().equals(CONFIG_CLASS)
                        && parameters.length == 0;
            case CONFIG_PACKAGE:
                return CONFIG_CLASS.equals(owner)
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("getHansPackage")
                        && method.getReturnType().getName().equals(PACKAGE_CLASS)
                        && types(parameters, int.class);
            case PACKAGE_ACCESS:
            default:
                return false;
        }
    }

    private static Entry resolve(
            Capability capability,
            ClassLookup owner,
            String name,
            Class<?> returnType,
            boolean staticMethod,
            Class<?>... parameters) {
        Status ownerStatus = classStatus(owner);
        if (ownerStatus != Status.AVAILABLE) return new Entry(ownerStatus, null);
        if (returnType == null) return new Entry(Status.DEPENDENCY_UNAVAILABLE, null);
        try {
            Method method = owner.type.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != returnType
                    || Modifier.isStatic(method.getModifiers()) != staticMethod
                    || !matches(capability, method)) {
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
        MANAGER_INSTANCE,
        MANAGER_READY,
        MANAGER_UNFREEZE,
        CONFIG_INSTANCE,
        CONFIG_PACKAGE
    }

    public enum Status {
        AVAILABLE,
        MISSING_CLASS,
        MISSING_METHOD,
        INVALID_SIGNATURE,
        ACCESSOR_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE,
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
