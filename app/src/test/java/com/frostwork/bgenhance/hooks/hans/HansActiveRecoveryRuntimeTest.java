package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusHansManager;
import com.android.server.hans.OplusHansDBConfig;
import com.android.server.hans.OplusHansPackage;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime.Capability;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime.CapabilityMatrix;
import com.frostwork.bgenhance.hooks.hans.HansActiveRecoveryRuntime.Status;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public final class HansActiveRecoveryRuntimeTest {
    private static final Set<String> VENDOR_CLASSES = Set.of(
            "com.android.server.am.OplusHansManager",
            "com.android.server.hans.OplusHansDBConfig",
            "com.android.server.hans.OplusHansPackage");

    @Test
    public void discoversEveryExactActiveRecoveryCapability() {
        CapabilityMatrix matrix = HansActiveRecoveryRuntime.inspect(
                OplusHansPackage.class.getClassLoader());

        assertTrue(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.AVAILABLE, matrix.status(capability));
        }
        assertNull(matrix.methodOrNull(Capability.PACKAGE_ACCESS));
        assertExactMethod(
                matrix,
                Capability.MANAGER_INSTANCE,
                OplusHansManager.class,
                "getInstance",
                OplusHansManager.class,
                true);
        assertExactMethod(
                matrix,
                Capability.MANAGER_READY,
                OplusHansManager.class,
                "isReady",
                boolean.class,
                false);
        assertExactMethod(
                matrix,
                Capability.MANAGER_UNFREEZE,
                OplusHansManager.class,
                "hansUnFreeze",
                boolean.class,
                false,
                int.class,
                String.class,
                String.class);
        assertExactMethod(
                matrix,
                Capability.CONFIG_INSTANCE,
                OplusHansDBConfig.class,
                "getInstance",
                OplusHansDBConfig.class,
                true);
        assertExactMethod(
                matrix,
                Capability.CONFIG_PACKAGE,
                OplusHansDBConfig.class,
                "getHansPackage",
                OplusHansPackage.class,
                false,
                int.class);
    }

    @Test
    public void acceptsOnlyOneMethodFromEveryAbiDecoySet() {
        assertOnlyExactOverload(
                Capability.MANAGER_INSTANCE, OplusHansManager.class, "getInstance");
        assertOnlyExactOverload(
                Capability.MANAGER_READY, OplusHansManager.class, "isReady");
        assertOnlyExactOverload(
                Capability.MANAGER_UNFREEZE, OplusHansManager.class, "hansUnFreeze");
        assertOnlyExactOverload(
                Capability.CONFIG_INSTANCE, OplusHansDBConfig.class, "getInstance");
        assertOnlyExactOverload(
                Capability.CONFIG_PACKAGE, OplusHansDBConfig.class, "getHansPackage");
    }

    @Test
    public void missingPackageDisablesThePackageDependentBoundary() {
        ClassLoader blocked = new BlockingClassLoader(
                OplusHansPackage.class.getClassLoader(),
                Set.of("com.android.server.hans.OplusHansPackage"));

        CapabilityMatrix matrix = HansActiveRecoveryRuntime.inspect(blocked);

        assertEquals(Status.MISSING_CLASS, matrix.status(Capability.PACKAGE_ACCESS));
        assertEquals(
                Status.DEPENDENCY_UNAVAILABLE,
                matrix.status(Capability.CONFIG_PACKAGE));
        assertEquals(Status.AVAILABLE, matrix.status(Capability.MANAGER_UNFREEZE));
        assertFalse(matrix.allAvailable());
    }

    @Test
    public void blockedClassLoaderReturnsSafeUnsupportedMatrix() {
        ClassLoader blocked = new BlockingClassLoader(
                OplusHansPackage.class.getClassLoader(), VENDOR_CLASSES);

        CapabilityMatrix matrix = HansActiveRecoveryRuntime.inspect(blocked);

        assertFalse(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.MISSING_CLASS, matrix.status(capability));
            assertNull(matrix.methodOrNull(capability));
        }
    }

    @Test
    public void exposesEveryStatusAndAStableSummaryEntry() {
        EnumSet<Status> statuses = EnumSet.allOf(Status.class);
        assertTrue(statuses.contains(Status.AVAILABLE));
        assertTrue(statuses.contains(Status.MISSING_CLASS));
        assertTrue(statuses.contains(Status.MISSING_METHOD));
        assertTrue(statuses.contains(Status.INVALID_SIGNATURE));
        assertTrue(statuses.contains(Status.ACCESSOR_UNAVAILABLE));
        assertTrue(statuses.contains(Status.DEPENDENCY_UNAVAILABLE));
        assertTrue(statuses.contains(Status.LOOKUP_FAILED));

        String summary = HansActiveRecoveryRuntime.inspect(
                OplusHansPackage.class.getClassLoader()).summary();
        for (Capability capability : Capability.values()) {
            assertTrue(summary.contains(
                    capability.name().toLowerCase(Locale.ROOT) + "="));
        }
    }

    private static void assertExactMethod(
            CapabilityMatrix matrix,
            Capability capability,
            Class<?> owner,
            String name,
            Class<?> returnType,
            boolean staticMethod,
            Class<?>... parameterTypes) {
        Method method = matrix.methodOrNull(capability);
        assertNotNull(method);
        assertEquals(owner, method.getDeclaringClass());
        assertEquals(name, method.getName());
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(staticMethod, Modifier.isStatic(method.getModifiers()));
        assertTrue(HansActiveRecoveryRuntime.matches(capability, method));
    }

    private static void assertOnlyExactOverload(
            Capability capability, Class<?> owner, String methodName) {
        int candidates = 0;
        int matches = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            candidates++;
            if (HansActiveRecoveryRuntime.matches(capability, method)) matches++;
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
