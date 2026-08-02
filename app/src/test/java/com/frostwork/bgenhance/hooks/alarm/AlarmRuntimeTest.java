package com.frostwork.bgenhance.hooks.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManagerInternal;
import com.android.server.alarm.Alarm;
import com.android.server.alarm.AlarmManagerService;
import com.android.server.alarm.AlarmManagerServiceExtImpl;
import com.android.server.am.OplusHansManager;
import com.frostwork.bgenhance.hooks.alarm.AlarmRuntime.Capability;
import com.frostwork.bgenhance.hooks.alarm.AlarmRuntime.CapabilityMatrix;
import com.frostwork.bgenhance.hooks.alarm.AlarmRuntime.Status;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

public final class AlarmRuntimeTest {
    @Test
    public void discoversExactAlarmCapabilitiesAndCompletionPath() throws Exception {
        CapabilityMatrix matrix = AlarmRuntime.inspect(Alarm.class.getClassLoader());

        assertTrue(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.AVAILABLE, matrix.status(capability));
        }
        assertExactMethod(
                matrix,
                Capability.FILTER_HANS,
                AlarmManagerServiceExtImpl.class,
                "filterAlarmForHans",
                boolean.class,
                Alarm.class);
        assertExactMethod(
                matrix,
                Capability.DELIVERY_GATE,
                AlarmManagerServiceExtImpl.class,
                "interceptDeliverAlarmsLockedInLoop",
                boolean.class,
                Alarm.class);
        assertExactMethod(
                matrix,
                Capability.HANS_CHECK,
                OplusHansManager.class,
                "checkAlarmIfRestricted",
                boolean.class,
                int.class, String.class, String.class);
        assertNull(matrix.methodOrNull(Capability.ALARM_ACCESS));
        assertNull(matrix.methodOrNull(Capability.NOTE_FINISH_ACCESS));

        ActivityManagerInternal activityManager = new ActivityManagerInternal();
        AlarmManagerService service = new AlarmManagerService(activityManager);
        AlarmManagerServiceExtImpl extension = new AlarmManagerServiceExtImpl(service);
        matrix.completionAccessOrNull().noteAlarmFinish(
                extension, null, null, 10_611, "alarm-tag");

        assertEquals(1, activityManager.finishCount());
        assertEquals(10_611, activityManager.uid());
        assertEquals("alarm-tag", activityManager.statsTag());
        assertNull(activityManager.operation());
        assertNull(activityManager.workSource());
    }

    @Test
    public void excludesStaticWrongReturnAndWrongParameterOverloads() {
        int matchingFilter = 0;
        int matchingDelivery = 0;
        for (Method method : AlarmManagerServiceExtImpl.class.getDeclaredMethods()) {
            if (AlarmRuntime.matches(Capability.FILTER_HANS, method)) matchingFilter++;
            if (AlarmRuntime.matches(Capability.DELIVERY_GATE, method)) matchingDelivery++;
        }
        int matchingHans = 0;
        for (Method method : OplusHansManager.class.getDeclaredMethods()) {
            if (AlarmRuntime.matches(Capability.HANS_CHECK, method)) matchingHans++;
        }

        assertEquals(1, matchingFilter);
        assertEquals(1, matchingDelivery);
        assertEquals(1, matchingHans);
        assertFalse(AlarmRuntime.matches(Capability.ALARM_ACCESS, null));
        assertFalse(AlarmRuntime.matches(Capability.NOTE_FINISH_ACCESS, null));
    }

    @Test
    public void reportsMissingClassesWithoutPublishingPartialAccess() {
        Set<String> hidden = Set.of(
                "com.android.server.alarm.Alarm",
                "android.app.ActivityManagerInternal");
        CapabilityMatrix matrix = AlarmRuntime.inspect(new FilteringClassLoader(
                Alarm.class.getClassLoader(), hidden));

        assertFalse(matrix.allAvailable());
        assertEquals(Status.MISSING_CLASS, matrix.status(Capability.ALARM_ACCESS));
        assertEquals(
                Status.DEPENDENCY_UNAVAILABLE,
                matrix.status(Capability.FILTER_HANS));
        assertEquals(
                Status.DEPENDENCY_UNAVAILABLE,
                matrix.status(Capability.DELIVERY_GATE));
        assertEquals(Status.AVAILABLE, matrix.status(Capability.HANS_CHECK));
        assertEquals(
                Status.DEPENDENCY_UNAVAILABLE,
                matrix.status(Capability.NOTE_FINISH_ACCESS));
        assertNull(matrix.completionAccessOrNull());
    }

    @Test
    public void summaryContainsEveryStableCapabilityName() {
        String summary = AlarmRuntime.inspect(Alarm.class.getClassLoader()).summary();

        for (Capability capability : EnumSet.allOf(Capability.class)) {
            assertTrue(summary.contains(
                    capability.name().toLowerCase(java.util.Locale.ROOT) + '='));
        }
    }

    private static void assertExactMethod(
            CapabilityMatrix matrix,
            Capability capability,
            Class<?> owner,
            String name,
            Class<?> returnType,
            Class<?>... parameters) throws Exception {
        Method expected = owner.getDeclaredMethod(name, parameters);
        assertEquals(expected, matrix.methodOrNull(capability));
        assertEquals(returnType, expected.getReturnType());
        assertTrue(AlarmRuntime.matches(capability, expected));
        assertNotNull(matrix.methodOrNull(capability));
    }

    private static final class FilteringClassLoader extends ClassLoader {
        private final Set<String> hidden;

        private FilteringClassLoader(ClassLoader parent, Set<String> hidden) {
            super(parent);
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (hidden.contains(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }
}
