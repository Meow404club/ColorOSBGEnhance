package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.SparseArray;
import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.freeze.HansCGroup;
import com.android.server.hans.scene.HansSceneManager;
import com.frostwork.bgenhance.hooks.hans.HansFastFreezeRuntime.Capability;
import com.frostwork.bgenhance.hooks.hans.HansFastFreezeRuntime.CapabilityMatrix;
import com.frostwork.bgenhance.hooks.hans.HansFastFreezeRuntime.Status;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public final class HansFastFreezeRuntimeTest {
    private static final Set<String> VENDOR_CLASSES = Set.of(
            "com.android.server.hans.OplusHansPackage",
            "com.android.server.hans.freeze.HansCGroup",
            "com.android.server.hans.scene.HansSceneManager");

    @Test
    public void discoversEveryRequiredBehaviorCapability() {
        CapabilityMatrix matrix = HansFastFreezeRuntime.inspect(
                HansSceneManager.class.getClassLoader());

        assertTrue(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.AVAILABLE, matrix.status(capability));
        }
        assertNull(matrix.methodOrNull(Capability.PACKAGE_ACCESS));
        assertExactMethod(
                matrix,
                Capability.ENTER_FAST_FREEZER,
                HansSceneManager.class,
                "enterFastFreezer",
                String.class,
                true,
                int.class, String.class, int.class, String.class);
        assertExactBridge(matrix);
        assertExactMethod(
                matrix,
                Capability.FILTER_FAST_FREEZER_CANDIDATES,
                HansSceneManager.class,
                "fastFreezeAppList",
                ArrayList.class,
                true,
                SparseArray.class);
        assertExactMethod(
                matrix,
                Capability.FAST_FREEZE_V2_TERMINAL,
                HansCGroup.class,
                "FastFreezeEnter",
                void.class,
                false,
                int.class);
    }

    @Test
    public void everyMethodCapabilityMatchesExactlyOneOverload() {
        assertSingleMatchingOverload(
                Capability.ENTER_FAST_FREEZER,
                HansSceneManager.class,
                "enterFastFreezer");
        assertSingleMatchingBridge();
        assertSingleMatchingOverload(
                Capability.FILTER_FAST_FREEZER_CANDIDATES,
                HansSceneManager.class,
                "fastFreezeAppList");
        assertSingleMatchingOverload(
                Capability.FAST_FREEZE_V2_TERMINAL,
                HansCGroup.class,
                "FastFreezeEnter");
    }

    @Test
    public void rejectsStaticBoxedWrongReturnAndWrongParameterDecoys()
            throws Exception {
        Method boxedEntry = HansSceneManager.class.getDeclaredMethod(
                "enterFastFreezer",
                Integer.class, String.class, int.class, String.class);
        Method wrongReturnEntry = HansSceneManager.class.getDeclaredMethod(
                "enterFastFreezer",
                int.class, String.class, long.class, String.class);
        Method wrongParameterEntry = HansSceneManager.class.getDeclaredMethod(
                "enterFastFreezer",
                int.class, CharSequence.class, int.class, String.class);
        Method wrongOwnerBridge = HansSceneManager.class.getDeclaredMethod(
                "access$enterFastFreezer",
                Object.class,
                int.class, String.class, int.class, String.class);
        Method nonSyntheticBridge = HansSceneManager.class.getDeclaredMethod(
                "access$enterFastFreezer",
                HansSceneManager.class,
                int.class, String.class, int.class, String.class);
        Method staticCandidates = HansSceneManager.class.getDeclaredMethod(
                "fastFreezeAppList", Object.class);
        Method wrongReturnCandidates = HansSceneManager.class.getDeclaredMethod(
                "fastFreezeAppList", java.util.List.class);
        Method wrongParameterCandidates = HansSceneManager.class.getDeclaredMethod(
                "fastFreezeAppList", long.class);
        Method boxedTerminal = HansCGroup.class.getDeclaredMethod(
                "FastFreezeEnter", Integer.class);
        Method wrongTerminal = HansCGroup.class.getDeclaredMethod(
                "FastFreezeEnter", long.class);

        assertTrue(Modifier.isStatic(boxedEntry.getModifiers()));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER, boxedEntry));
        assertEquals(CharSequence.class, wrongReturnEntry.getReturnType());
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER, wrongReturnEntry));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER, wrongParameterEntry));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER_BRIDGE, wrongOwnerBridge));
        assertFalse(nonSyntheticBridge.isSynthetic());
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER_BRIDGE, nonSyntheticBridge));
        assertTrue(Modifier.isStatic(staticCandidates.getModifiers()));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.FILTER_FAST_FREEZER_CANDIDATES, staticCandidates));
        assertEquals(java.util.List.class, wrongReturnCandidates.getReturnType());
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.FILTER_FAST_FREEZER_CANDIDATES, wrongReturnCandidates));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.FILTER_FAST_FREEZER_CANDIDATES, wrongParameterCandidates));
        assertTrue(Modifier.isStatic(boxedTerminal.getModifiers()));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.FAST_FREEZE_V2_TERMINAL, boxedTerminal));
        assertEquals(boolean.class, wrongTerminal.getReturnType());
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.FAST_FREEZE_V2_TERMINAL, wrongTerminal));
    }

    @Test
    public void ownerMismatchNeverMatches() throws Exception {
        Method packageAccessor = OplusHansPackage.class.getDeclaredMethod("getUid");
        for (Capability capability : Capability.values()) {
            assertFalse(HansFastFreezeRuntime.matches(capability, packageAccessor));
        }
        assertFalse(HansFastFreezeRuntime.matches(null, packageAccessor));
        assertFalse(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER, null));
    }

    @Test
    public void missingFirmwareClassesProduceSafeUnsupportedMatrix() {
        CapabilityMatrix matrix = HansFastFreezeRuntime.inspect(
                new BlockingClassLoader(HansSceneManager.class.getClassLoader()));

        assertFalse(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.MISSING_CLASS, matrix.status(capability));
            assertNull(matrix.methodOrNull(capability));
        }
    }

    @Test
    public void summaryContainsEveryCapabilityAndStatusIsComplete() {
        assertEquals(
                EnumSet.of(
                        Status.AVAILABLE,
                        Status.MISSING_CLASS,
                        Status.MISSING_METHOD,
                        Status.INVALID_SIGNATURE,
                        Status.ACCESSOR_UNAVAILABLE,
                        Status.LOOKUP_FAILED),
                EnumSet.allOf(Status.class));
        String summary = HansFastFreezeRuntime.inspect(
                HansSceneManager.class.getClassLoader()).summary();
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
            boolean privateMethod,
            Class<?>... parameters) {
        Method method = matrix.methodOrNull(capability);
        assertNotNull(method);
        assertEquals(owner, method.getDeclaringClass());
        assertEquals(name, method.getName());
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameters, method.getParameterTypes());
        assertEquals(privateMethod, Modifier.isPrivate(method.getModifiers()));
        assertFalse(Modifier.isStatic(method.getModifiers()));
        assertTrue(HansFastFreezeRuntime.matches(capability, method));
    }

    private static void assertExactBridge(CapabilityMatrix matrix) {
        Method method = matrix.methodOrNull(Capability.ENTER_FAST_FREEZER_BRIDGE);
        assertNotNull(method);
        assertEquals(HansSceneManager.class, method.getDeclaringClass());
        assertTrue(method.getName().contains("enterFastFreezer"));
        assertTrue(method.isSynthetic());
        assertEquals(String.class, method.getReturnType());
        assertArrayEquals(
                new Class<?>[] {
                        HansSceneManager.class,
                        int.class,
                        String.class,
                        int.class,
                        String.class
                },
                method.getParameterTypes());
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertTrue(HansFastFreezeRuntime.matches(
                Capability.ENTER_FAST_FREEZER_BRIDGE, method));
    }

    private static void assertSingleMatchingBridge() {
        int candidates = 0;
        int matches = 0;
        for (Method method : HansSceneManager.class.getDeclaredMethods()) {
            if (!method.getName().contains("enterFastFreezer")) continue;
            candidates++;
            if (HansFastFreezeRuntime.matches(
                    Capability.ENTER_FAST_FREEZER_BRIDGE, method)) {
                matches++;
            }
        }
        assertTrue("fixture must contain bridge decoys", candidates > 1);
        assertEquals(1, matches);
    }

    private static void assertSingleMatchingOverload(
            Capability capability, Class<?> owner, String methodName) {
        int candidates = 0;
        int matches = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            candidates++;
            if (HansFastFreezeRuntime.matches(capability, method)) matches++;
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
                throw new ClassNotFoundException("blocked fixture: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
