package com.frostwork.bgenhance.hooks.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.IAlarmListener;
import android.app.PendingIntent;
import android.os.WorkSource;
import com.android.server.alarm.Alarm;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.Test;

public final class AlarmObjectAdapterTest {
    private static final int APP_UID = 10_611;
    private static final int SYSTEM_UID = 1_000;
    private static final String APP_PACKAGE = "com.example.alarm.target";
    private static final String SYSTEM_PACKAGE = "android";
    private static final String STATS_TAG = "*walarm*:fixture-tag";

    @Test
    public void resolvesPendingIntentWithoutCallingFrameworkMethods() throws Exception {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));
        PendingIntent operation = allocate(PendingIntent.class);
        WorkSource workSource = allocate(WorkSource.class);

        AlarmObjectAdapter.Resolution resolution = AlarmObjectAdapter.resolve(new Alarm(
                operation,
                null,
                null,
                STATS_TAG,
                workSource,
                APP_UID,
                APP_UID,
                APP_PACKAGE,
                APP_PACKAGE));

        assertResolved(resolution);
        AlarmObjectAdapter.Target target = resolution.targetOrNull();
        assertEquals(AlarmObjectAdapter.Kind.PENDING_INTENT, target.kind());
        assertEquals(APP_UID, target.ownerUid());
        assertEquals(APP_PACKAGE, target.ownerPackage());
        assertEquals(APP_UID, target.schedulerUid());
        assertEquals(APP_PACKAGE, target.schedulerPackage());
        assertSame(operation, target.operation());
        assertNull(target.listener());
        assertSame(workSource, target.workSource());
        assertEquals(STATS_TAG, target.statsTag());
    }

    @Test
    public void pendingIntentUsesCreatorInsteadOfSystemScheduler() throws Exception {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));

        AlarmObjectAdapter.Resolution resolution = AlarmObjectAdapter.resolve(new Alarm(
                allocate(PendingIntent.class),
                null,
                null,
                STATS_TAG,
                null,
                SYSTEM_UID,
                APP_UID,
                SYSTEM_PACKAGE,
                APP_PACKAGE));

        assertResolved(resolution);
        AlarmObjectAdapter.Target target = resolution.targetOrNull();
        assertEquals(APP_UID, target.ownerUid());
        assertEquals(APP_PACKAGE, target.ownerPackage());
        assertEquals(SYSTEM_UID, target.schedulerUid());
        assertEquals(SYSTEM_PACKAGE, target.schedulerPackage());
    }

    @Test
    public void resolvesDirectListenerWithMatchingOwner() {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));
        IAlarmListener listener = listenerThatRejectsCalls();

        AlarmObjectAdapter.Resolution resolution = AlarmObjectAdapter.resolve(new Alarm(
                null,
                listener,
                "listener-tag",
                STATS_TAG,
                null,
                APP_UID,
                APP_UID,
                APP_PACKAGE,
                APP_PACKAGE));

        assertResolved(resolution);
        AlarmObjectAdapter.Target target = resolution.targetOrNull();
        assertEquals(AlarmObjectAdapter.Kind.DIRECT_LISTENER, target.kind());
        assertEquals(APP_UID, target.ownerUid());
        assertEquals(APP_PACKAGE, target.ownerPackage());
        assertNull(target.operation());
        assertSame(listener, target.listener());
        assertEquals("listener-tag", target.listenerTag());
    }

    @Test
    public void rejectsMissingOrAmbiguousDeliveryEndpoint() throws Exception {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));
        assertFailure(
                AlarmObjectAdapter.Failure.INVALID_DELIVERY_ENDPOINT,
                AlarmObjectAdapter.resolve(new Alarm(
                        null,
                        null,
                        null,
                        STATS_TAG,
                        null,
                        APP_UID,
                        APP_UID,
                        APP_PACKAGE,
                        APP_PACKAGE)));
        assertFailure(
                AlarmObjectAdapter.Failure.INVALID_DELIVERY_ENDPOINT,
                AlarmObjectAdapter.resolve(new Alarm(
                        allocate(PendingIntent.class),
                        listenerThatRejectsCalls(),
                        "listener-tag",
                        STATS_TAG,
                        null,
                        APP_UID,
                        APP_UID,
                        APP_PACKAGE,
                        APP_PACKAGE)));
    }

    @Test
    public void rejectsDirectListenerOwnerMismatch() {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));
        IAlarmListener listener = listenerThatRejectsCalls();

        assertFailure(
                AlarmObjectAdapter.Failure.LISTENER_IDENTITY_MISMATCH,
                AlarmObjectAdapter.resolve(new Alarm(
                        null,
                        listener,
                        "listener-tag",
                        STATS_TAG,
                        null,
                        SYSTEM_UID,
                        APP_UID,
                        APP_PACKAGE,
                        APP_PACKAGE)));
        assertFailure(
                AlarmObjectAdapter.Failure.LISTENER_IDENTITY_MISMATCH,
                AlarmObjectAdapter.resolve(new Alarm(
                        null,
                        listener,
                        "listener-tag",
                        STATS_TAG,
                        null,
                        APP_UID,
                        APP_UID,
                        SYSTEM_PACKAGE,
                        APP_PACKAGE)));
    }

    @Test
    public void rejectsNegativeUidsAndBlankPackages() throws Exception {
        assertTrue(AlarmObjectAdapter.prepare(Alarm.class));
        PendingIntent operation = allocate(PendingIntent.class);

        assertFailure(
                AlarmObjectAdapter.Failure.INVALID_UID,
                AlarmObjectAdapter.resolve(new Alarm(
                        operation,
                        null,
                        null,
                        STATS_TAG,
                        null,
                        -1,
                        APP_UID,
                        APP_PACKAGE,
                        APP_PACKAGE)));
        assertFailure(
                AlarmObjectAdapter.Failure.INVALID_UID,
                AlarmObjectAdapter.resolve(new Alarm(
                        operation,
                        null,
                        null,
                        STATS_TAG,
                        null,
                        APP_UID,
                        -1,
                        APP_PACKAGE,
                        APP_PACKAGE)));
        assertFailure(
                AlarmObjectAdapter.Failure.INVALID_PACKAGE_NAME,
                AlarmObjectAdapter.resolve(new Alarm(
                        operation,
                        null,
                        null,
                        STATS_TAG,
                        null,
                        APP_UID,
                        APP_UID,
                        " \t\r\n",
                        APP_PACKAGE)));
        assertFailure(
                AlarmObjectAdapter.Failure.INVALID_PACKAGE_NAME,
                AlarmObjectAdapter.resolve(new Alarm(
                        operation,
                        null,
                        null,
                        STATS_TAG,
                        null,
                        APP_UID,
                        APP_UID,
                        APP_PACKAGE,
                        "")));
    }

    @Test
    public void requiresExplicitPreparationForRuntimeClass() {
        assertFalse(AlarmObjectAdapter.prepare(null));
        assertFailure(
                AlarmObjectAdapter.Failure.NULL_ALARM,
                AlarmObjectAdapter.resolve(null));
        assertFailure(
                AlarmObjectAdapter.Failure.ACCESSOR_NOT_PREPARED,
                AlarmObjectAdapter.resolve(new UnpreparedAlarmFixture()));
    }

    @Test
    public void reportsMissingWrongAndStaticFieldsDuringPreparation() {
        assertFalse(AlarmObjectAdapter.prepare(MissingFieldAlarm.class));
        assertFailure(
                AlarmObjectAdapter.Failure.FIELD_MISSING,
                "statsTag",
                AlarmObjectAdapter.resolve(new MissingFieldAlarm()));

        assertFalse(AlarmObjectAdapter.prepare(WrongTypeAlarm.class));
        assertFailure(
                AlarmObjectAdapter.Failure.FIELD_TYPE_MISMATCH,
                "operation",
                AlarmObjectAdapter.resolve(new WrongTypeAlarm()));

        assertFalse(AlarmObjectAdapter.prepare(StaticFieldAlarm.class));
        assertFailure(
                AlarmObjectAdapter.Failure.FIELD_STATIC,
                "operation",
                AlarmObjectAdapter.resolve(new StaticFieldAlarm()));
    }

    @Test
    public void resolvesFieldsInheritedByRuntimeAlarmClass() throws Exception {
        assertTrue(AlarmObjectAdapter.prepare(InheritedAlarm.class));

        AlarmObjectAdapter.Resolution resolution = AlarmObjectAdapter.resolve(
                new InheritedAlarm(allocate(PendingIntent.class)));

        assertResolved(resolution);
        assertEquals(APP_UID, resolution.targetOrNull().ownerUid());
        assertEquals(APP_PACKAGE, resolution.targetOrNull().ownerPackage());
    }

    @Test
    public void failsOpenWhenPreparedFieldReadBecomesInaccessible() throws Exception {
        assertTrue(AlarmObjectAdapter.prepare(ReadFailureAlarm.class));
        Field operationField = preparedOperationField(ReadFailureAlarm.class);
        operationField.setAccessible(false);
        try {
            assertFailure(
                    AlarmObjectAdapter.Failure.REFLECTION_FAILED,
                    AlarmObjectAdapter.resolve(new ReadFailureAlarm(
                            allocate(PendingIntent.class))));
        } finally {
            operationField.setAccessible(true);
        }
    }

    private static void assertResolved(AlarmObjectAdapter.Resolution resolution) {
        assertTrue(resolution.isResolved());
        assertNull(resolution.failureOrNull());
        assertNull(resolution.failureFieldOrNull());
    }

    private static void assertFailure(
            AlarmObjectAdapter.Failure expected,
            AlarmObjectAdapter.Resolution resolution) {
        assertFailure(expected, null, resolution);
    }

    private static void assertFailure(
            AlarmObjectAdapter.Failure expected,
            String expectedField,
            AlarmObjectAdapter.Resolution resolution) {
        assertFalse(resolution.isResolved());
        assertNull(resolution.targetOrNull());
        assertEquals(expected, resolution.failureOrNull());
        assertEquals(expectedField, resolution.failureFieldOrNull());
    }

    private static IAlarmListener listenerThatRejectsCalls() {
        return (IAlarmListener) Proxy.newProxyInstance(
                IAlarmListener.class.getClassLoader(),
                new Class<?>[] {IAlarmListener.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("listener method must not be called");
                });
    }

    private static Field preparedOperationField(Class<?> alarmType) throws Exception {
        Field cacheField = AlarmObjectAdapter.class.getDeclaredField("PREPARED_FIELDS");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        Object prepared = cache.get(alarmType);
        Field slot = prepared.getClass().getDeclaredField("operation");
        slot.setAccessible(true);
        return (Field) slot.get(prepared);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
        return type.cast(allocateInstance.invoke(unsafe, type));
    }

    private static class ValidInheritedFields {
        private final PendingIntent operation;
        private final IAlarmListener listener = null;
        private final String listenerTag = null;
        private final String statsTag = STATS_TAG;
        private final WorkSource workSource = null;
        private final int uid = APP_UID;
        private final int creatorUid = APP_UID;
        private final String packageName = APP_PACKAGE;
        private final String sourcePackage = APP_PACKAGE;

        private ValidInheritedFields(PendingIntent operation) {
            this.operation = operation;
        }
    }

    private static final class UnpreparedAlarmFixture extends ValidInheritedFields {
        private UnpreparedAlarmFixture() {
            super(null);
        }
    }

    private static final class WrongTypeAlarm extends ValidInheritedFields {
        @SuppressWarnings("unused")
        private final Object operation = null;

        private WrongTypeAlarm() {
            super(null);
        }
    }

    private static final class StaticFieldAlarm extends ValidInheritedFields {
        @SuppressWarnings("unused")
        private static PendingIntent operation;

        private StaticFieldAlarm() {
            super(null);
        }
    }

    private static final class ReadFailureAlarm extends ValidInheritedFields {
        private ReadFailureAlarm(PendingIntent operation) {
            super(operation);
        }
    }

    private static final class InheritedAlarm extends Alarm {
        private InheritedAlarm(PendingIntent operation) {
            super(
                    operation,
                    null,
                    null,
                    STATS_TAG,
                    null,
                    APP_UID,
                    APP_UID,
                    APP_PACKAGE,
                    APP_PACKAGE);
        }
    }

    private static final class MissingFieldAlarm {
        @SuppressWarnings("unused") private final PendingIntent operation = null;
        @SuppressWarnings("unused") private final IAlarmListener listener = null;
        @SuppressWarnings("unused") private final String listenerTag = null;
        @SuppressWarnings("unused") private final WorkSource workSource = null;
        @SuppressWarnings("unused") private final int uid = APP_UID;
        @SuppressWarnings("unused") private final int creatorUid = APP_UID;
        @SuppressWarnings("unused") private final String packageName = APP_PACKAGE;
        @SuppressWarnings("unused") private final String sourcePackage = APP_PACKAGE;
    }
}
