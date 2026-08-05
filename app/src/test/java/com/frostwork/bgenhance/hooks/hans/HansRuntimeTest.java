package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.server.am.OplusHansManager;
import com.android.server.hans.OplusHansDBConfig;
import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.freeze.HansCGroup;
import com.frostwork.bgenhance.hooks.hans.HansRuntime.Capability;
import com.frostwork.bgenhance.hooks.hans.HansRuntime.CapabilityMatrix;
import com.frostwork.bgenhance.hooks.hans.HansRuntime.Status;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public final class HansRuntimeTest {
    private static final Set<String> VENDOR_CLASSES = Set.of(
            "com.android.server.hans.OplusHansPackage",
            "com.android.server.hans.OplusHansDBConfig",
            "com.android.server.am.OplusHansManager",
            "com.android.server.hans.freeze.HansCGroup");

    @Test
    public void discoversCoveredCapabilitiesAndUncoveredFastV2Path() {
        CapabilityMatrix matrix = HansRuntime.inspect(OplusHansPackage.class.getClassLoader());

        assertFalse(matrix.allAvailable());
        for (Capability capability : EnumSet.complementOf(
                EnumSet.of(Capability.FAST_FREEZE_V2_PATH))) {
            assertEquals(Status.AVAILABLE, matrix.status(capability));
        }
        assertEquals(
                Status.DETECTED_UNCOVERED,
                matrix.status(Capability.FAST_FREEZE_V2_PATH));
        assertNull(matrix.methodOrNull(Capability.PACKAGE_ACCESS));

        assertExactMethod(
                matrix,
                Capability.R_TO_M_TIMING,
                OplusHansDBConfig.class,
                "getRtoMCheckTime",
                long.class,
                int.class, String.class, int.class, long.class);
        assertExactMethod(
                matrix,
                Capability.M_TO_F_TIMING,
                OplusHansDBConfig.class,
                "getMtoFCheckTime",
                long.class,
                int.class, String.class, int.class, long.class, String.class);
        assertExactMethod(
                matrix,
                Capability.CORE_EXEMPTION,
                OplusHansManager.class,
                "isHansCoreApp",
                boolean.class,
                int.class, String.class);
        assertExactMethod(
                matrix,
                Capability.LCD_ON_EXEMPTION,
                OplusHansManager.class,
                "isLcdOnNonRestrictPkg",
                boolean.class,
                int.class, String.class);
        assertExactMethod(
                matrix,
                Capability.KERNEL_WAKE_7,
                OplusHansManager.class,
                "unfreezeForKernel",
                void.class,
                int.class, int.class, int.class, int.class,
                int.class, String.class, int.class);
        assertExactMethod(
                matrix,
                Capability.FREEZE_GATE,
                HansCGroup.class,
                "hansFreezeLocked",
                boolean.class,
                OplusHansPackage.class, String.class);
        assertExactMethod(
                matrix,
                Capability.UNFREEZE_GATE,
                HansCGroup.class,
                "hansUnfreezeLocked",
                boolean.class,
                OplusHansPackage.class, String.class, String.class);
        assertNull(matrix.methodOrNull(Capability.FAST_FREEZE_V2_PATH));
        assertTrue(HansRuntime.matches(
                Capability.FAST_FREEZE_V2_PATH,
                declaredMethod(HansCGroup.class, "FastFreezeEnter", int.class)));
    }

    @Test
    public void excludesPidAndOtherDecoyOverloads() throws Exception {
        assertSingleMatchingOverload(
                Capability.R_TO_M_TIMING,
                OplusHansDBConfig.class,
                "getRtoMCheckTime");
        assertSingleMatchingOverload(
                Capability.M_TO_F_TIMING,
                OplusHansDBConfig.class,
                "getMtoFCheckTime");
        assertSingleMatchingOverload(
                Capability.CORE_EXEMPTION,
                OplusHansManager.class,
                "isHansCoreApp");
        assertSingleMatchingOverload(
                Capability.LCD_ON_EXEMPTION,
                OplusHansManager.class,
                "isLcdOnNonRestrictPkg");
        assertSingleMatchingOverload(
                Capability.KERNEL_WAKE_7,
                OplusHansManager.class,
                "unfreezeForKernel");
        assertSingleMatchingOverload(
                Capability.FREEZE_GATE,
                HansCGroup.class,
                "hansFreezeLocked");
        assertSingleMatchingOverload(
                Capability.UNFREEZE_GATE,
                HansCGroup.class,
                "hansUnfreezeLocked");
        assertSingleMatchingOverload(
                Capability.FAST_FREEZE_V2_PATH,
                HansCGroup.class,
                "FastFreezeEnter");

        Method pidFreeze = HansCGroup.class.getDeclaredMethod(
                "hansFreezeLocked", int.class, String.class, String.class);
        Method pidUnfreeze = HansCGroup.class.getDeclaredMethod(
                "hansUnfreezeLocked",
                int.class, String.class, String.class, String.class);
        Method fiveArgumentKernelWake = OplusHansManager.class.getDeclaredMethod(
                "unfreezeForKernel",
                int.class, int.class, int.class, String.class, int.class);
        assertFalse(HansRuntime.matches(Capability.FREEZE_GATE, pidFreeze));
        assertFalse(HansRuntime.matches(Capability.UNFREEZE_GATE, pidUnfreeze));
        assertFalse(HansRuntime.matches(Capability.KERNEL_WAKE_7, fiveArgumentKernelWake));

        CapabilityMatrix matrix = HansRuntime.inspect(OplusHansPackage.class.getClassLoader());
        assertNotEquals(pidFreeze, matrix.methodOrNull(Capability.FREEZE_GATE));
        assertNotEquals(pidUnfreeze, matrix.methodOrNull(Capability.UNFREEZE_GATE));
        assertNotEquals(
                fiveArgumentKernelWake,
                matrix.methodOrNull(Capability.KERNEL_WAKE_7));
    }

    @Test
    public void rejectsStaticWrongReturnAndWrongParameterCandidates() throws Exception {
        Method staticTiming = OplusHansDBConfig.class.getDeclaredMethod(
                "getRtoMCheckTime",
                Integer.class, String.class, int.class, long.class);
        Method wrongReturnTiming = OplusHansDBConfig.class.getDeclaredMethod(
                "getRtoMCheckTime",
                int.class, CharSequence.class, int.class, long.class);
        Method staticExemption = OplusHansManager.class.getDeclaredMethod(
                "isHansCoreApp", Integer.class, String.class);
        Method wrongReturnExemption = OplusHansManager.class.getDeclaredMethod(
                "isHansCoreApp", int.class, CharSequence.class);
        Method staticKernelWake = OplusHansManager.class.getDeclaredMethod(
                "unfreezeForKernel",
                Integer.class, int.class, int.class, int.class,
                int.class, String.class, int.class);
        Method wrongReturnKernelWake = OplusHansManager.class.getDeclaredMethod(
                "unfreezeForKernel",
                int.class, int.class, int.class, int.class,
                int.class, CharSequence.class, int.class);
        Method wrongParameterKernelWake = OplusHansManager.class.getDeclaredMethod(
                "unfreezeForKernel",
                int.class, int.class, int.class, int.class,
                long.class, String.class, int.class);
        Method staticFreeze = HansCGroup.class.getDeclaredMethod(
                "hansFreezeLocked", Object.class, String.class);
        Method staticUnfreeze = HansCGroup.class.getDeclaredMethod(
                "hansUnfreezeLocked", Object.class, String.class, String.class);
        Method wrongReturnUnfreeze = HansCGroup.class.getDeclaredMethod(
                "hansUnfreezeLocked",
                OplusHansPackage.class, String.class, CharSequence.class);
        Method wrongParameterUnfreeze = HansCGroup.class.getDeclaredMethod(
                "hansUnfreezeLocked", Object.class, String.class, CharSequence.class);
        Method staticFastV2 = HansCGroup.class.getDeclaredMethod(
                "FastFreezeEnter", Integer.class);
        Method wrongReturnFastV2 = HansCGroup.class.getDeclaredMethod(
                "FastFreezeEnter", long.class);

        assertTrue(Modifier.isStatic(staticTiming.getModifiers()));
        assertFalse(HansRuntime.matches(Capability.R_TO_M_TIMING, staticTiming));
        assertEquals(int.class, wrongReturnTiming.getReturnType());
        assertFalse(HansRuntime.matches(Capability.R_TO_M_TIMING, wrongReturnTiming));
        assertTrue(Modifier.isStatic(staticExemption.getModifiers()));
        assertFalse(HansRuntime.matches(Capability.CORE_EXEMPTION, staticExemption));
        assertEquals(int.class, wrongReturnExemption.getReturnType());
        assertFalse(HansRuntime.matches(Capability.CORE_EXEMPTION, wrongReturnExemption));
        assertTrue(Modifier.isStatic(staticKernelWake.getModifiers()));
        assertFalse(HansRuntime.matches(Capability.KERNEL_WAKE_7, staticKernelWake));
        assertEquals(boolean.class, wrongReturnKernelWake.getReturnType());
        assertFalse(HansRuntime.matches(Capability.KERNEL_WAKE_7, wrongReturnKernelWake));
        assertFalse(HansRuntime.matches(Capability.KERNEL_WAKE_7, wrongParameterKernelWake));
        assertTrue(Modifier.isStatic(staticFreeze.getModifiers()));
        assertFalse(HansRuntime.matches(Capability.FREEZE_GATE, staticFreeze));
        assertTrue(Modifier.isStatic(staticUnfreeze.getModifiers()));
        assertFalse(HansRuntime.matches(Capability.UNFREEZE_GATE, staticUnfreeze));
        assertEquals(int.class, wrongReturnUnfreeze.getReturnType());
        assertFalse(HansRuntime.matches(Capability.UNFREEZE_GATE, wrongReturnUnfreeze));
        assertFalse(HansRuntime.matches(Capability.UNFREEZE_GATE, wrongParameterUnfreeze));
        assertTrue(Modifier.isStatic(staticFastV2.getModifiers()));
        assertFalse(HansRuntime.matches(Capability.FAST_FREEZE_V2_PATH, staticFastV2));
        assertEquals(boolean.class, wrongReturnFastV2.getReturnType());
        assertFalse(HansRuntime.matches(
                Capability.FAST_FREEZE_V2_PATH, wrongReturnFastV2));
    }

    @Test
    public void blockedClassLoaderReturnsSafeUnsupportedMatrix() {
        ClassLoader blocked = new BlockingClassLoader(OplusHansPackage.class.getClassLoader());

        CapabilityMatrix matrix = HansRuntime.inspect(blocked);

        assertFalse(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.MISSING_CLASS, matrix.status(capability));
            assertNull(matrix.methodOrNull(capability));
        }
        assertFalse(matrix.summary().isBlank());
    }

    @Test
    public void exposesEveryRequiredStatusAndSummarizesEveryCapability() {
        EnumSet<Status> statuses = EnumSet.allOf(Status.class);
        assertTrue(statuses.contains(Status.AVAILABLE));
        assertTrue(statuses.contains(Status.MISSING_CLASS));
        assertTrue(statuses.contains(Status.MISSING_METHOD));
        assertTrue(statuses.contains(Status.INVALID_SIGNATURE));
        assertTrue(statuses.contains(Status.ACCESSOR_UNAVAILABLE));
        assertTrue(statuses.contains(Status.DEPENDENCY_UNAVAILABLE));
        assertTrue(statuses.contains(Status.LOOKUP_FAILED));
        assertTrue(statuses.contains(Status.DETECTED_UNCOVERED));

        String summary = HansRuntime.inspect(
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
            Class<?>... parameterTypes) {
        Method method = matrix.methodOrNull(capability);
        assertNotNull(method);
        assertEquals(owner, method.getDeclaringClass());
        assertEquals(name, method.getName());
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isStatic(method.getModifiers()));
        assertTrue(HansRuntime.matches(capability, method));
    }

    private static Method declaredMethod(
            Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException error) {
            throw new AssertionError(error);
        }
    }

    private static void assertSingleMatchingOverload(
            Capability capability,
            Class<?> owner,
            String methodName) {
        int matches = 0;
        int candidates = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            candidates++;
            if (HansRuntime.matches(capability, method)) matches++;
        }
        assertTrue("fixture must contain decoy overloads", candidates > 1);
        assertEquals(1, matches);
    }

    private static final class BlockingClassLoader extends ClassLoader {
        private BlockingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (VENDOR_CLASSES.contains(name)) {
                throw new ClassNotFoundException("blocked test fixture: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
