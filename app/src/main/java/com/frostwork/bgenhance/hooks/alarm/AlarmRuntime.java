package com.frostwork.bgenhance.hooks.alarm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the exact Alarm delivery ABI and prewarms its completion path. */
public final class AlarmRuntime {
    private static final String ALARM_CLASS = "com.android.server.alarm.Alarm";
    private static final String EXT_CLASS =
            "com.android.server.alarm.AlarmManagerServiceExtImpl";
    private static final String SERVICE_CLASS =
            "com.android.server.alarm.AlarmManagerService";
    private static final String INJECTOR_CLASS =
            "com.android.server.alarm.AlarmManagerService$Injector";
    private static final String HANS_MANAGER_CLASS =
            "com.android.server.am.OplusHansManager";
    private static final String ACTIVITY_MANAGER_INTERNAL_CLASS =
            "android.app.ActivityManagerInternal";
    private static final String PENDING_INTENT_CLASS = "android.app.PendingIntent";
    private static final String WORK_SOURCE_CLASS = "android.os.WorkSource";

    private AlarmRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        ClassLookup alarm = load(ALARM_CLASS, loader);
        ClassLookup ext = load(EXT_CLASS, loader);
        ClassLookup service = load(SERVICE_CLASS, loader);
        ClassLookup injector = load(INJECTOR_CLASS, loader);
        ClassLookup hansManager = load(HANS_MANAGER_CLASS, loader);
        ClassLookup activityManagerInternal = load(
                ACTIVITY_MANAGER_INTERNAL_CLASS, loader);
        ClassLookup pendingIntent = load(PENDING_INTENT_CLASS, loader);
        ClassLookup workSource = load(WORK_SOURCE_CLASS, loader);

        EnumMap<Capability, Entry> entries = new EnumMap<>(Capability.class);
        Status alarmStatus = classStatus(alarm);
        if (alarmStatus == Status.AVAILABLE
                && !AlarmObjectAdapter.prepare(alarm.type)) {
            alarmStatus = Status.ACCESSOR_UNAVAILABLE;
        }
        entries.put(Capability.ALARM_ACCESS, new Entry(alarmStatus, null));
        entries.put(Capability.FILTER_HANS, resolve(
                Capability.FILTER_HANS,
                ext,
                "filterAlarmForHans",
                boolean.class,
                alarm));
        entries.put(Capability.DELIVERY_GATE, resolve(
                Capability.DELIVERY_GATE,
                ext,
                "interceptDeliverAlarmsLockedInLoop",
                boolean.class,
                alarm));
        entries.put(Capability.HANS_CHECK, resolve(
                Capability.HANS_CHECK,
                hansManager,
                "checkAlarmIfRestricted",
                boolean.class,
                ClassLookup.available(int.class),
                ClassLookup.available(String.class),
                ClassLookup.available(String.class)));

        CompletionResolution completion = resolveCompletionAccess(
                ext,
                service,
                activityManagerInternal,
                pendingIntent,
                workSource);
        entries.put(Capability.NOTE_FINISH_ACCESS, new Entry(completion.status, null));
        DriverResolution driver = resolveDriverAccess(ext, service, injector);
        entries.put(Capability.NATIVE_DRIVER_ACCESS, new Entry(driver.status, null));
        return new CapabilityMatrix(entries, completion.access, driver.access);
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
            case FILTER_HANS:
                return EXT_CLASS.equals(owner)
                        && method.getName().equals("filterAlarmForHans")
                        && method.getReturnType() == boolean.class
                        && typeNames(parameters, ALARM_CLASS);
            case DELIVERY_GATE:
                return EXT_CLASS.equals(owner)
                        && method.getName().equals(
                                "interceptDeliverAlarmsLockedInLoop")
                        && method.getReturnType() == boolean.class
                        && typeNames(parameters, ALARM_CLASS);
            case HANS_CHECK:
                return HANS_MANAGER_CLASS.equals(owner)
                        && method.getName().equals("checkAlarmIfRestricted")
                        && method.getReturnType() == boolean.class
                        && types(parameters, int.class, String.class, String.class);
            case ALARM_ACCESS:
            case NOTE_FINISH_ACCESS:
            case NATIVE_DRIVER_ACCESS:
            default:
                return false;
        }
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

    private static CompletionResolution resolveCompletionAccess(
            ClassLookup ext,
            ClassLookup service,
            ClassLookup activityManagerInternal,
            ClassLookup pendingIntent,
            ClassLookup workSource) {
        if (!allAvailable(
                ext, service, activityManagerInternal, pendingIntent, workSource)) {
            return CompletionResolution.failed(Status.DEPENDENCY_UNAVAILABLE);
        }
        try {
            Field serviceField = exactInstanceField(ext.type, "mAlarmMS", service.type);
            Field activityManagerField = exactInstanceField(
                    service.type,
                    "mActivityManagerInternal",
                    activityManagerInternal.type);
            Method finish = activityManagerInternal.type.getMethod(
                    "noteAlarmFinish",
                    pendingIntent.type,
                    workSource.type,
                    int.class,
                    String.class);
            if (finish.getReturnType() != void.class
                    || Modifier.isStatic(finish.getModifiers())
                    || !Modifier.isPublic(finish.getModifiers())) {
                return CompletionResolution.failed(Status.INVALID_SIGNATURE);
            }
            serviceField.setAccessible(true);
            activityManagerField.setAccessible(true);
            finish.setAccessible(true);
            return CompletionResolution.available(new CompletionAccess(
                    serviceField, activityManagerField, finish));
        } catch (InvalidFieldException error) {
            return CompletionResolution.failed(Status.INVALID_SIGNATURE);
        } catch (NoSuchFieldException error) {
            return CompletionResolution.failed(Status.MISSING_FIELD);
        } catch (NoSuchMethodException error) {
            return CompletionResolution.failed(Status.MISSING_METHOD);
        } catch (LinkageError | RuntimeException error) {
            return CompletionResolution.failed(Status.LOOKUP_FAILED);
        }
    }

    private static DriverResolution resolveDriverAccess(
            ClassLookup ext, ClassLookup service, ClassLookup injector) {
        if (!allAvailable(ext, service, injector)) {
            return DriverResolution.failed(Status.DEPENDENCY_UNAVAILABLE);
        }
        try {
            Field serviceField = exactInstanceField(ext.type, "mAlarmMS", service.type);
            Field injectorField = exactInstanceField(
                    service.type, "mInjector", injector.type);
            Method present = injector.type.getDeclaredMethod("isAlarmDriverPresent");
            if (present.getReturnType() != boolean.class
                    || Modifier.isStatic(present.getModifiers())) {
                return DriverResolution.failed(Status.INVALID_SIGNATURE);
            }
            serviceField.setAccessible(true);
            injectorField.setAccessible(true);
            present.setAccessible(true);
            return DriverResolution.available(new DriverAccess(
                    serviceField, injectorField, present));
        } catch (InvalidFieldException error) {
            return DriverResolution.failed(Status.INVALID_SIGNATURE);
        } catch (NoSuchFieldException error) {
            return DriverResolution.failed(Status.MISSING_FIELD);
        } catch (NoSuchMethodException error) {
            return DriverResolution.failed(Status.MISSING_METHOD);
        } catch (LinkageError | RuntimeException error) {
            return DriverResolution.failed(Status.LOOKUP_FAILED);
        }
    }

    private static Field exactInstanceField(
            Class<?> owner, String name, Class<?> expectedType)
            throws NoSuchFieldException, InvalidFieldException {
        Field field = owner.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers()) || field.getType() != expectedType) {
            throw new InvalidFieldException(name);
        }
        return field;
    }

    private static boolean allAvailable(ClassLookup... lookups) {
        for (ClassLookup lookup : lookups) {
            if (classStatus(lookup) != Status.AVAILABLE) return false;
        }
        return true;
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
        ALARM_ACCESS,
        FILTER_HANS,
        DELIVERY_GATE,
        HANS_CHECK,
        NOTE_FINISH_ACCESS,
        NATIVE_DRIVER_ACCESS
    }

    public enum Status {
        AVAILABLE,
        MISSING_CLASS,
        MISSING_METHOD,
        MISSING_FIELD,
        INVALID_SIGNATURE,
        ACCESSOR_UNAVAILABLE,
        DEPENDENCY_UNAVAILABLE,
        LOOKUP_FAILED
    }

    public static final class CapabilityMatrix {
        private final Map<Capability, Entry> entries;
        private final CompletionAccess completionAccess;
        private final DriverAccess driverAccess;

        private CapabilityMatrix(
                EnumMap<Capability, Entry> entries,
                CompletionAccess completionAccess,
                DriverAccess driverAccess) {
            this.entries = Collections.unmodifiableMap(new EnumMap<>(entries));
            this.completionAccess = completionAccess;
            this.driverAccess = driverAccess;
        }

        public Status status(Capability capability) {
            Entry entry = entries.get(Objects.requireNonNull(capability, "capability"));
            return entry == null ? Status.LOOKUP_FAILED : entry.status;
        }

        public Method methodOrNull(Capability capability) {
            Entry entry = entries.get(Objects.requireNonNull(capability, "capability"));
            return entry == null ? null : entry.method;
        }

        public CompletionAccess completionAccessOrNull() {
            return completionAccess;
        }

        public DriverAccess driverAccessOrNull() {
            return driverAccess;
        }

        public boolean allAvailable() {
            for (Capability capability : Capability.values()) {
                if (status(capability) != Status.AVAILABLE) return false;
            }
            return completionAccess != null && driverAccess != null;
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

    /** Immutable, pre-resolved path used after noteAlarmStart has already run. */
    public static final class CompletionAccess {
        private final Field serviceField;
        private final Field activityManagerField;
        private final Method finishMethod;

        private CompletionAccess(
                Field serviceField,
                Field activityManagerField,
                Method finishMethod) {
            this.serviceField = serviceField;
            this.activityManagerField = activityManagerField;
            this.finishMethod = finishMethod;
        }

        public void noteAlarmFinish(
                Object extension,
                Object operation,
                Object workSource,
                int uid,
                String statsTag) throws ReflectiveOperationException {
            Object service = serviceField.get(Objects.requireNonNull(
                    extension, "extension"));
            if (service == null) throw new IllegalStateException("alarm service unavailable");
            Object activityManager = activityManagerField.get(service);
            if (activityManager == null) {
                throw new IllegalStateException("activity manager unavailable");
            }
            finishMethod.invoke(activityManager, operation, workSource, uid, statsTag);
        }
    }

    /** Immutable path used to reject the framework's direct-send fallback. */
    public static final class DriverAccess {
        private final Field serviceField;
        private final Field injectorField;
        private final Method presentMethod;

        private DriverAccess(
                Field serviceField, Field injectorField, Method presentMethod) {
            this.serviceField = serviceField;
            this.injectorField = injectorField;
            this.presentMethod = presentMethod;
        }

        public boolean isNativeDriverPresent(Object extension)
                throws ReflectiveOperationException {
            Object service = serviceField.get(Objects.requireNonNull(
                    extension, "extension"));
            if (service == null) throw new IllegalStateException("alarm service unavailable");
            Object injector = injectorField.get(service);
            if (injector == null) throw new IllegalStateException("injector unavailable");
            Object value = presentMethod.invoke(injector);
            if (!(value instanceof Boolean)) {
                throw new IllegalStateException("invalid driver state");
            }
            return ((Boolean) value).booleanValue();
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

    private static final class CompletionResolution {
        private final Status status;
        private final CompletionAccess access;

        private CompletionResolution(Status status, CompletionAccess access) {
            this.status = status;
            this.access = access;
        }

        private static CompletionResolution available(CompletionAccess access) {
            return new CompletionResolution(Status.AVAILABLE, access);
        }

        private static CompletionResolution failed(Status status) {
            return new CompletionResolution(status, null);
        }
    }

    private static final class DriverResolution {
        private final Status status;
        private final DriverAccess access;

        private DriverResolution(Status status, DriverAccess access) {
            this.status = status;
            this.access = access;
        }

        private static DriverResolution available(DriverAccess access) {
            return new DriverResolution(Status.AVAILABLE, access);
        }

        private static DriverResolution failed(Status status) {
            return new DriverResolution(status, null);
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

    private static final class InvalidFieldException extends Exception {
        private InvalidFieldException(String fieldName) {
            super(fieldName);
        }
    }
}
