package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusHansManager;
import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.states.action.Action;
import com.frostwork.bgenhance.hooks.hans.HansResourceRuntime.Capability;
import com.frostwork.bgenhance.hooks.hans.HansResourceRuntime.CapabilityMatrix;
import com.frostwork.bgenhance.hooks.hans.HansResourceRuntime.Status;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public final class HansResourceRuntimeTest {
    private static final Set<String> VENDOR_CLASSES = Set.of(
            "com.android.server.hans.states.action.Action",
            "com.android.server.hans.OplusHansPackage",
            "com.android.server.am.OplusHansManager");

    @Test
    public void discoversEveryExactResourceAndNetworkCapability() {
        CapabilityMatrix matrix = HansResourceRuntime.inspect(
                OplusHansPackage.class.getClassLoader());

        assertTrue(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.AVAILABLE, matrix.status(capability));
        }
        assertNull(matrix.methodOrNull(Capability.ACTION_ACCESS));

        assertActionMethod(matrix, Capability.PROXY_SERVICE, "proxyService");
        assertActionMethod(matrix, Capability.PROXY_BROADCAST, "proxyBroadcast");
        assertActionMethod(matrix, Capability.PROXY_JOB, "proxyJob");
        assertActionMethod(matrix, Capability.PROXY_SENSOR, "proxySensor");
        assertActionMethod(matrix, Capability.PROXY_BINDER, "proxyBinder");
        assertActionMethod(matrix, Capability.MANAGE_ALARM, "manageAlarm");
        assertActionMethod(matrix, Capability.PROXY_WAKE_LOCK, "proxyWakeLock");
        assertActionMethod(matrix, Capability.PROXY_GPS, "proxyGPS");
        assertActionMethod(matrix, Capability.PROXY_AUDIO, "proxyAudio");
        assertActionMethod(matrix, Capability.PROXY_BT_SCAN, "proxyBtScan");
        assertExactMethod(
                matrix,
                Capability.TRY_PROXY_WAKE_LOCK,
                OplusHansManager.class,
                "tryProxyWakeLock",
                boolean.class,
                OplusHansPackage.class);
        assertExactMethod(
                matrix,
                Capability.FIREWALL,
                OplusHansManager.class,
                "nwPowerSetFirewall",
                void.class,
                OplusHansPackage.class, boolean.class, int.class, int.class);
        assertExactMethod(
                matrix,
                Capability.RSTATE_NETWORK,
                OplusHansManager.class,
                "restrictRStateNonKeyProcsNet",
                boolean.class,
                String.class, int.class, String.class);
    }

    @Test
    public void acceptsOnlyOneCandidateAmongEveryAbiDecoySet() {
        assertOnlyExactOverload(Capability.PROXY_SERVICE, Action.class, "proxyService");
        assertOnlyExactOverload(
                Capability.PROXY_BROADCAST, Action.class, "proxyBroadcast");
        assertOnlyExactOverload(Capability.PROXY_JOB, Action.class, "proxyJob");
        assertOnlyExactOverload(Capability.PROXY_SENSOR, Action.class, "proxySensor");
        assertOnlyExactOverload(Capability.PROXY_BINDER, Action.class, "proxyBinder");
        assertOnlyExactOverload(Capability.MANAGE_ALARM, Action.class, "manageAlarm");
        assertOnlyExactOverload(
                Capability.PROXY_WAKE_LOCK, Action.class, "proxyWakeLock");
        assertOnlyExactOverload(Capability.PROXY_GPS, Action.class, "proxyGPS");
        assertOnlyExactOverload(Capability.PROXY_AUDIO, Action.class, "proxyAudio");
        assertOnlyExactOverload(Capability.PROXY_BT_SCAN, Action.class, "proxyBtScan");
        assertOnlyExactOverload(
                Capability.TRY_PROXY_WAKE_LOCK,
                OplusHansManager.class,
                "tryProxyWakeLock");
        assertOnlyExactOverload(
                Capability.FIREWALL,
                OplusHansManager.class,
                "nwPowerSetFirewall");
        assertOnlyExactOverload(
                Capability.RSTATE_NETWORK,
                OplusHansManager.class,
                "restrictRStateNonKeyProcsNet");
    }

    @Test
    public void packageLookupFailureDisablesOnlyDependentCapabilities() {
        ClassLoader blocked = new BlockingClassLoader(
                OplusHansPackage.class.getClassLoader(),
                Set.of("com.android.server.hans.OplusHansPackage"));

        CapabilityMatrix matrix = HansResourceRuntime.inspect(blocked);

        assertEquals(Status.DEPENDENCY_UNAVAILABLE, matrix.status(Capability.ACTION_ACCESS));
        assertEquals(
                Status.DEPENDENCY_UNAVAILABLE,
                matrix.status(Capability.TRY_PROXY_WAKE_LOCK));
        assertEquals(Status.DEPENDENCY_UNAVAILABLE, matrix.status(Capability.FIREWALL));
        assertEquals(Status.AVAILABLE, matrix.status(Capability.PROXY_SERVICE));
        assertEquals(Status.AVAILABLE, matrix.status(Capability.RSTATE_NETWORK));
        assertFalse(matrix.allAvailable());
    }

    @Test
    public void blockedClassLoaderReturnsSafeUnsupportedMatrix() {
        ClassLoader blocked = new BlockingClassLoader(
                OplusHansPackage.class.getClassLoader(), VENDOR_CLASSES);

        CapabilityMatrix matrix = HansResourceRuntime.inspect(blocked);

        assertFalse(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.MISSING_CLASS, matrix.status(capability));
            assertNull(matrix.methodOrNull(capability));
        }
        assertFalse(matrix.summary().isBlank());
    }

    @Test
    public void exposesAndSummarizesEveryCapabilityStatus() {
        EnumSet<Status> statuses = EnumSet.allOf(Status.class);
        assertTrue(statuses.contains(Status.AVAILABLE));
        assertTrue(statuses.contains(Status.MISSING_CLASS));
        assertTrue(statuses.contains(Status.MISSING_METHOD));
        assertTrue(statuses.contains(Status.INVALID_SIGNATURE));
        assertTrue(statuses.contains(Status.ACCESSOR_UNAVAILABLE));
        assertTrue(statuses.contains(Status.DEPENDENCY_UNAVAILABLE));
        assertTrue(statuses.contains(Status.LOOKUP_FAILED));

        String summary = HansResourceRuntime.inspect(
                OplusHansPackage.class.getClassLoader()).summary();
        for (Capability capability : Capability.values()) {
            assertTrue(summary.contains(
                    capability.name().toLowerCase(Locale.ROOT) + "="));
        }
    }

    private static void assertActionMethod(
            CapabilityMatrix matrix, Capability capability, String methodName) {
        assertExactMethod(
                matrix, capability, Action.class, methodName, void.class);
    }

    private static void assertExactMethod(
            CapabilityMatrix matrix,
            Capability capability,
            Class<?> owner,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        Method method = matrix.methodOrNull(capability);
        assertNotNull(method);
        assertEquals(owner, method.getDeclaringClass());
        assertEquals(name, method.getName());
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isStatic(method.getModifiers()));
        assertTrue(HansResourceRuntime.matches(capability, method));
    }

    private static void assertOnlyExactOverload(
            Capability capability, Class<?> owner, String methodName) {
        int candidates = 0;
        int matches = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            candidates++;
            boolean matched = HansResourceRuntime.matches(capability, method);
            if (matched) {
                matches++;
            } else {
                assertFalse(matched);
            }
        }
        assertTrue("fixture must contain ABI decoys", candidates > 1);
        assertEquals(1, matches);
    }

    private static final class BlockingClassLoader extends ClassLoader {
        private final Set<String> blockedNames;

        private BlockingClassLoader(ClassLoader parent, Set<String> blockedNames) {
            super(parent);
            this.blockedNames = blockedNames;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (blockedNames.contains(name)) {
                throw new ClassNotFoundException("blocked test fixture: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
