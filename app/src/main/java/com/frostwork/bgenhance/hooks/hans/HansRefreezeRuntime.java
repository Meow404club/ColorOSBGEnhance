package com.frostwork.bgenhance.hooks.hans;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the exact reason-aware HANS refreeze state-machine surface. */
public final class HansRefreezeRuntime {
    private static final String PACKAGE_CLASS =
            "com.android.server.hans.OplusHansPackage";
    private static final String CONFIG_CLASS =
            "com.android.server.hans.OplusHansDBConfig";
    private static final String STATE_MACHINE_CLASS =
            "com.android.server.hans.states.HansAppStateMachine";
    private static final String MIDDLE_STATE_CLASS = STATE_MACHINE_CLASS + "$MiddleState";
    private static final String FROZEN_STATE_CLASS = STATE_MACHINE_CLASS + "$FrozenState";
    private static final String MESSAGE_CLASS = "android.os.Message";

    private HansRefreezeRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        EnumMap<Capability, Entry> entries = new EnumMap<>(Capability.class);
        ClassLookup packageType = load(PACKAGE_CLASS, loader);
        ClassLookup configType = load(CONFIG_CLASS, loader);
        ClassLookup stateMachineType = load(STATE_MACHINE_CLASS, loader);
        ClassLookup middleStateType = load(MIDDLE_STATE_CLASS, loader);
        ClassLookup frozenStateType = load(FROZEN_STATE_CLASS, loader);
        ClassLookup messageType = load(MESSAGE_CLASS, loader);

        entries.put(Capability.M_TO_F_TIMING, resolve(
                Capability.M_TO_F_TIMING,
                configType,
                "getMtoFCheckTime",
                long.class,
                int.class, String.class, int.class, long.class, String.class));
        entries.put(Capability.FROZEN_TO_MIDDLE, resolve(
                Capability.FROZEN_TO_MIDDLE,
                frozenStateType,
                "transToMiddle",
                void.class));
        entries.put(Capability.MIDDLE_ENTER, resolve(
                Capability.MIDDLE_ENTER,
                middleStateType,
                "enter",
                void.class));
        entries.put(Capability.MIDDLE_EXIT, resolve(
                Capability.MIDDLE_EXIT,
                middleStateType,
                "exit",
                void.class));

        Entry process;
        Status messageStatus = classStatus(messageType);
        if (messageStatus != Status.AVAILABLE) {
            process = new Entry(messageStatus, null);
        } else {
            process = resolve(
                    Capability.MIDDLE_PROCESS_MESSAGE,
                    middleStateType,
                    "processMessage",
                    boolean.class,
                    messageType.type);
        }
        entries.put(Capability.MIDDLE_PROCESS_MESSAGE, process);

        Status adapterStatus = firstUnavailable(
                packageType, stateMachineType, middleStateType, frozenStateType);
        if (adapterStatus == Status.AVAILABLE) {
            try {
                adapterStatus = HansRefreezeStateAdapter.prepare(
                        stateMachineType.type,
                        middleStateType.type,
                        frozenStateType.type,
                        packageType.type)
                        ? Status.AVAILABLE : Status.ACCESSOR_UNAVAILABLE;
            } catch (LinkageError | RuntimeException error) {
                adapterStatus = Status.LOOKUP_FAILED;
            }
        }
        entries.put(Capability.STATE_ACCESS, new Entry(adapterStatus, null));
        return new CapabilityMatrix(entries);
    }

    public static boolean matches(Capability capability, Method method) {
        if (capability == null
                || method == null
                || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        String owner = method.getDeclaringClass().getName();
        Class<?>[] parameters = method.getParameterTypes();
        switch (capability) {
            case M_TO_F_TIMING:
                return Modifier.isPublic(method.getModifiers())
                        && CONFIG_CLASS.equals(owner)
                        && method.getName().equals("getMtoFCheckTime")
                        && method.getReturnType() == long.class
                        && types(parameters, int.class, String.class, int.class,
                        long.class, String.class);
            case FROZEN_TO_MIDDLE:
                return Modifier.isPrivate(method.getModifiers())
                        && FROZEN_STATE_CLASS.equals(owner)
                        && method.getName().equals("transToMiddle")
                        && method.getReturnType() == void.class
                        && parameters.length == 0;
            case MIDDLE_ENTER:
                return Modifier.isPublic(method.getModifiers())
                        && MIDDLE_STATE_CLASS.equals(owner)
                        && method.getName().equals("enter")
                        && method.getReturnType() == void.class
                        && parameters.length == 0;
            case MIDDLE_EXIT:
                return Modifier.isPublic(method.getModifiers())
                        && MIDDLE_STATE_CLASS.equals(owner)
                        && method.getName().equals("exit")
                        && method.getReturnType() == void.class
                        && parameters.length == 0;
            case MIDDLE_PROCESS_MESSAGE:
                return Modifier.isPublic(method.getModifiers())
                        && MIDDLE_STATE_CLASS.equals(owner)
                        && method.getName().equals("processMessage")
                        && method.getReturnType() == boolean.class
                        && typeNames(parameters, MESSAGE_CLASS);
            case STATE_ACCESS:
            default:
                return false;
        }
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

    private static Status firstUnavailable(ClassLookup... lookups) {
        for (ClassLookup lookup : lookups) {
            Status status = classStatus(lookup);
            if (status != Status.AVAILABLE) return status;
        }
        return Status.AVAILABLE;
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
        STATE_ACCESS,
        M_TO_F_TIMING,
        FROZEN_TO_MIDDLE,
        MIDDLE_ENTER,
        MIDDLE_EXIT,
        MIDDLE_PROCESS_MESSAGE
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
