package com.frostwork.bgenhance.hooks.alarm;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reads an Alarm using fields resolved completely during hook installation. */
public final class AlarmObjectAdapter {
    private static final String PENDING_INTENT_TYPE = "android.app.PendingIntent";
    private static final String ALARM_LISTENER_TYPE = "android.app.IAlarmListener";
    private static final String STRING_TYPE = "java.lang.String";
    private static final String WORK_SOURCE_TYPE = "android.os.WorkSource";
    private static final String INT_TYPE = "int";

    private static final ConcurrentMap<Class<?>, PreparedFields> PREPARED_FIELDS =
            new ConcurrentHashMap<>();

    private AlarmObjectAdapter() {}

    /** Resolves and validates the complete Alarm field ABI before callbacks are enabled. */
    public static boolean prepare(Class<?> alarmType) {
        if (alarmType == null) return false;
        try {
            return PREPARED_FIELDS.computeIfAbsent(
                    alarmType, AlarmObjectAdapter::load).isReady();
        } catch (LinkageError | RuntimeException error) {
            return false;
        }
    }

    /** Reads only fields selected by {@link #prepare(Class)}. */
    public static Resolution resolve(Object alarm) {
        if (alarm == null) return Resolution.failed(Failure.NULL_ALARM, null);

        PreparedFields fields = PREPARED_FIELDS.get(alarm.getClass());
        if (fields == null) {
            return Resolution.failed(Failure.ACCESSOR_NOT_PREPARED, null);
        }
        if (!fields.isReady()) {
            return Resolution.failed(fields.failure, fields.failureField);
        }

        try {
            Object operation = fields.operation.get(alarm);
            Object listener = fields.listener.get(alarm);
            String listenerTag = (String) fields.listenerTag.get(alarm);
            String statsTag = (String) fields.statsTag.get(alarm);
            Object workSource = fields.workSource.get(alarm);
            int schedulerUid = fields.uid.getInt(alarm);
            int ownerUid = fields.creatorUid.getInt(alarm);
            String schedulerPackage = (String) fields.packageName.get(alarm);
            String ownerPackage = (String) fields.sourcePackage.get(alarm);

            if ((operation == null) == (listener == null)) {
                return Resolution.failed(Failure.INVALID_DELIVERY_ENDPOINT, null);
            }
            if (schedulerUid < 0 || ownerUid < 0) {
                return Resolution.failed(Failure.INVALID_UID, null);
            }
            if (!isNonBlank(schedulerPackage) || !isNonBlank(ownerPackage)) {
                return Resolution.failed(Failure.INVALID_PACKAGE_NAME, null);
            }

            Kind kind;
            if (operation != null) {
                kind = Kind.PENDING_INTENT;
            } else {
                kind = Kind.DIRECT_LISTENER;
                if (ownerUid != schedulerUid || !ownerPackage.equals(schedulerPackage)) {
                    return Resolution.failed(Failure.LISTENER_IDENTITY_MISMATCH, null);
                }
            }

            return Resolution.resolved(new Target(
                    kind,
                    ownerUid,
                    ownerPackage,
                    schedulerUid,
                    schedulerPackage,
                    operation,
                    listener,
                    listenerTag,
                    workSource,
                    statsTag));
        } catch (IllegalAccessException | LinkageError | RuntimeException error) {
            return Resolution.failed(Failure.REFLECTION_FAILED, null);
        }
    }

    private static PreparedFields load(Class<?> alarmType) {
        try {
            return PreparedFields.ready(
                    requiredField(alarmType, "operation", PENDING_INTENT_TYPE),
                    requiredField(alarmType, "listener", ALARM_LISTENER_TYPE),
                    requiredField(alarmType, "listenerTag", STRING_TYPE),
                    requiredField(alarmType, "statsTag", STRING_TYPE),
                    requiredField(alarmType, "workSource", WORK_SOURCE_TYPE),
                    requiredField(alarmType, "uid", INT_TYPE),
                    requiredField(alarmType, "creatorUid", INT_TYPE),
                    requiredField(alarmType, "packageName", STRING_TYPE),
                    requiredField(alarmType, "sourcePackage", STRING_TYPE));
        } catch (FieldPreparationException error) {
            return PreparedFields.failed(error.failure, error.fieldName);
        } catch (LinkageError | RuntimeException error) {
            return PreparedFields.failed(Failure.REFLECTION_FAILED, null);
        }
    }

    private static Field requiredField(
            Class<?> alarmType, String fieldName, String expectedTypeName)
            throws FieldPreparationException {
        for (Class<?> current = alarmType;
                current != null;
                current = current.getSuperclass()) {
            Field field;
            try {
                field = current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                continue;
            }
            if (Modifier.isStatic(field.getModifiers())) {
                throw new FieldPreparationException(Failure.FIELD_STATIC, fieldName);
            }
            if (!field.getType().getName().equals(expectedTypeName)) {
                throw new FieldPreparationException(
                        Failure.FIELD_TYPE_MISMATCH, fieldName);
            }
            try {
                field.setAccessible(true);
            } catch (RuntimeException error) {
                throw new FieldPreparationException(
                        Failure.FIELD_INACCESSIBLE, fieldName);
            }
            return field;
        }
        throw new FieldPreparationException(Failure.FIELD_MISSING, fieldName);
    }

    private static boolean isNonBlank(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return true;
        }
        return false;
    }

    public enum Kind {
        PENDING_INTENT,
        DIRECT_LISTENER
    }

    public enum Failure {
        NULL_ALARM,
        ACCESSOR_NOT_PREPARED,
        FIELD_MISSING,
        FIELD_TYPE_MISMATCH,
        FIELD_STATIC,
        FIELD_INACCESSIBLE,
        REFLECTION_FAILED,
        INVALID_DELIVERY_ENDPOINT,
        INVALID_UID,
        INVALID_PACKAGE_NAME,
        LISTENER_IDENTITY_MISMATCH
    }

    public static final class Target {
        private final Kind kind;
        private final int ownerUid;
        private final String ownerPackage;
        private final int schedulerUid;
        private final String schedulerPackage;
        private final Object operation;
        private final Object listener;
        private final String listenerTag;
        private final Object workSource;
        private final String statsTag;

        private Target(
                Kind kind,
                int ownerUid,
                String ownerPackage,
                int schedulerUid,
                String schedulerPackage,
                Object operation,
                Object listener,
                String listenerTag,
                Object workSource,
                String statsTag) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.ownerUid = ownerUid;
            this.ownerPackage = Objects.requireNonNull(ownerPackage, "ownerPackage");
            this.schedulerUid = schedulerUid;
            this.schedulerPackage =
                    Objects.requireNonNull(schedulerPackage, "schedulerPackage");
            this.operation = operation;
            this.listener = listener;
            this.listenerTag = listenerTag;
            this.workSource = workSource;
            this.statsTag = statsTag;
        }

        public Kind kind() { return kind; }
        public int ownerUid() { return ownerUid; }
        public String ownerPackage() { return ownerPackage; }
        public int schedulerUid() { return schedulerUid; }
        public String schedulerPackage() { return schedulerPackage; }
        public Object operation() { return operation; }
        public Object listener() { return listener; }
        public String listenerTag() { return listenerTag; }
        public Object workSource() { return workSource; }
        public String statsTag() { return statsTag; }
    }

    public static final class Resolution {
        private final Target target;
        private final Failure failure;
        private final String failureField;

        private Resolution(Target target, Failure failure, String failureField) {
            this.target = target;
            this.failure = failure;
            this.failureField = failureField;
        }

        private static Resolution resolved(Target target) {
            return new Resolution(Objects.requireNonNull(target, "target"), null, null);
        }

        private static Resolution failed(Failure failure, String failureField) {
            return new Resolution(
                    null, Objects.requireNonNull(failure, "failure"), failureField);
        }

        public boolean isResolved() { return target != null; }
        public Target targetOrNull() { return target; }
        public Failure failureOrNull() { return failure; }
        public String failureFieldOrNull() { return failureField; }
    }

    private static final class PreparedFields {
        private final Field operation;
        private final Field listener;
        private final Field listenerTag;
        private final Field statsTag;
        private final Field workSource;
        private final Field uid;
        private final Field creatorUid;
        private final Field packageName;
        private final Field sourcePackage;
        private final Failure failure;
        private final String failureField;

        private PreparedFields(
                Field operation,
                Field listener,
                Field listenerTag,
                Field statsTag,
                Field workSource,
                Field uid,
                Field creatorUid,
                Field packageName,
                Field sourcePackage,
                Failure failure,
                String failureField) {
            this.operation = operation;
            this.listener = listener;
            this.listenerTag = listenerTag;
            this.statsTag = statsTag;
            this.workSource = workSource;
            this.uid = uid;
            this.creatorUid = creatorUid;
            this.packageName = packageName;
            this.sourcePackage = sourcePackage;
            this.failure = failure;
            this.failureField = failureField;
        }

        private static PreparedFields ready(
                Field operation,
                Field listener,
                Field listenerTag,
                Field statsTag,
                Field workSource,
                Field uid,
                Field creatorUid,
                Field packageName,
                Field sourcePackage) {
            return new PreparedFields(
                    operation,
                    listener,
                    listenerTag,
                    statsTag,
                    workSource,
                    uid,
                    creatorUid,
                    packageName,
                    sourcePackage,
                    null,
                    null);
        }

        private static PreparedFields failed(Failure failure, String failureField) {
            return new PreparedFields(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Objects.requireNonNull(failure, "failure"),
                    failureField);
        }

        private boolean isReady() { return operation != null; }
    }

    private static final class FieldPreparationException extends Exception {
        private final Failure failure;
        private final String fieldName;

        private FieldPreparationException(Failure failure, String fieldName) {
            this.failure = failure;
            this.fieldName = fieldName;
        }
    }
}
