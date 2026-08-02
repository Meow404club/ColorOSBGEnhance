package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves exact HANS resource and network capabilities without installing hooks. */
public final class HansResourceRuntime {
    private static final String ACTION_CLASS =
            "com.android.server.hans.states.action.Action";
    private static final String PACKAGE_CLASS =
            "com.android.server.hans.OplusHansPackage";
    private static final String MANAGER_CLASS =
            "com.android.server.am.OplusHansManager";

    private HansResourceRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        ClassLookup action = load(ACTION_CLASS, loader);
        ClassLookup hansPackage = load(PACKAGE_CLASS, loader);
        ClassLookup manager = load(MANAGER_CLASS, loader);
        EnumMap<Capability, Entry> entries = new EnumMap<>(Capability.class);

        Status actionStatus = classStatus(action);
        if (actionStatus == Status.AVAILABLE) {
            if (classStatus(hansPackage) != Status.AVAILABLE) {
                actionStatus = Status.DEPENDENCY_UNAVAILABLE;
            } else if (!HansActionAdapter.prepare(action.type, hansPackage.type)) {
                actionStatus = Status.ACCESSOR_UNAVAILABLE;
            }
        }
        entries.put(Capability.ACTION_ACCESS, new Entry(actionStatus, null));

        putAction(entries, Capability.PROXY_SERVICE, action, "proxyService");
        putAction(entries, Capability.PROXY_BROADCAST, action, "proxyBroadcast");
        putAction(entries, Capability.PROXY_JOB, action, "proxyJob");
        putAction(entries, Capability.PROXY_SENSOR, action, "proxySensor");
        putAction(entries, Capability.PROXY_BINDER, action, "proxyBinder");
        putAction(entries, Capability.MANAGE_ALARM, action, "manageAlarm");
        putAction(entries, Capability.PROXY_WAKE_LOCK, action, "proxyWakeLock");
        putAction(entries, Capability.PROXY_GPS, action, "proxyGPS");
        putAction(entries, Capability.PROXY_AUDIO, action, "proxyAudio");
        putAction(entries, Capability.PROXY_BT_SCAN, action, "proxyBtScan");

        entries.put(Capability.TRY_PROXY_WAKE_LOCK, resolve(
                Capability.TRY_PROXY_WAKE_LOCK,
                manager,
                "tryProxyWakeLock",
                boolean.class,
                hansPackage));
        entries.put(Capability.FIREWALL, resolve(
                Capability.FIREWALL,
                manager,
                "nwPowerSetFirewall",
                void.class,
                hansPackage,
                ClassLookup.available(boolean.class),
                ClassLookup.available(int.class),
                ClassLookup.available(int.class)));
        entries.put(Capability.RSTATE_NETWORK, resolve(
                Capability.RSTATE_NETWORK,
                manager,
                "restrictRStateNonKeyProcsNet",
                boolean.class,
                ClassLookup.available(String.class),
                ClassLookup.available(int.class),
                ClassLookup.available(String.class)));
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
            case PROXY_SERVICE:
                return actionMethod(owner, method, parameters, "proxyService");
            case PROXY_BROADCAST:
                return actionMethod(owner, method, parameters, "proxyBroadcast");
            case PROXY_JOB:
                return actionMethod(owner, method, parameters, "proxyJob");
            case PROXY_SENSOR:
                return actionMethod(owner, method, parameters, "proxySensor");
            case PROXY_BINDER:
                return actionMethod(owner, method, parameters, "proxyBinder");
            case MANAGE_ALARM:
                return actionMethod(owner, method, parameters, "manageAlarm");
            case PROXY_WAKE_LOCK:
                return actionMethod(owner, method, parameters, "proxyWakeLock");
            case PROXY_GPS:
                return actionMethod(owner, method, parameters, "proxyGPS");
            case PROXY_AUDIO:
                return actionMethod(owner, method, parameters, "proxyAudio");
            case PROXY_BT_SCAN:
                return actionMethod(owner, method, parameters, "proxyBtScan");
            case TRY_PROXY_WAKE_LOCK:
                return MANAGER_CLASS.equals(owner)
                        && method.getName().equals("tryProxyWakeLock")
                        && method.getReturnType() == boolean.class
                        && typeNames(parameters, PACKAGE_CLASS);
            case FIREWALL:
                return MANAGER_CLASS.equals(owner)
                        && method.getName().equals("nwPowerSetFirewall")
                        && method.getReturnType() == void.class
                        && typeNames(
                                parameters,
                                PACKAGE_CLASS,
                                boolean.class.getName(),
                                int.class.getName(),
                                int.class.getName());
            case RSTATE_NETWORK:
                return MANAGER_CLASS.equals(owner)
                        && method.getName().equals("restrictRStateNonKeyProcsNet")
                        && method.getReturnType() == boolean.class
                        && types(parameters, String.class, int.class, String.class);
            case ACTION_ACCESS:
            default:
                return false;
        }
    }

    private static boolean actionMethod(
            String owner, Method method, Class<?>[] parameters, String name) {
        return ACTION_CLASS.equals(owner)
                && method.getName().equals(name)
                && method.getReturnType() == void.class
                && parameters.length == 0;
    }

    private static void putAction(
            EnumMap<Capability, Entry> entries,
            Capability capability,
            ClassLookup action,
            String name) {
        entries.put(capability, resolve(capability, action, name, void.class));
    }

    private static Entry resolve(
            Capability capability,
            ClassLookup owner,
            String name,
            Class<?> returnType,
            ClassLookup... parameters) {
        Status ownerStatus = classStatus(owner);
        if (ownerStatus != Status.AVAILABLE) return new Entry(ownerStatus, null);

        Class<?>[] parameterTypes = new Class<?>[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Status status = classStatus(parameters[index]);
            if (status != Status.AVAILABLE) {
                return new Entry(Status.DEPENDENCY_UNAVAILABLE, null);
            }
            parameterTypes[index] = parameters[index].type;
        }
        try {
            Method method = owner.type.getDeclaredMethod(name, parameterTypes);
            if (method.getReturnType() != returnType || !matches(capability, method)) {
                return new Entry(Status.INVALID_SIGNATURE, null);
            }
            method.setAccessible(true);
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
        ACTION_ACCESS,
        PROXY_SERVICE,
        PROXY_BROADCAST,
        PROXY_JOB,
        PROXY_SENSOR,
        PROXY_BINDER,
        MANAGE_ALARM,
        PROXY_WAKE_LOCK,
        PROXY_GPS,
        PROXY_AUDIO,
        PROXY_BT_SCAN,
        TRY_PROXY_WAKE_LOCK,
        FIREWALL,
        RSTATE_NETWORK
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
