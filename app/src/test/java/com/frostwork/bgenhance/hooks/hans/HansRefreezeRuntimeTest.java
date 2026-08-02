package com.frostwork.bgenhance.hooks.hans;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Message;
import com.android.server.hans.OplusHansDBConfig;
import com.android.server.hans.OplusHansPackage;
import com.android.server.hans.states.HansAppStateMachine;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRuntime.Capability;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRuntime.CapabilityMatrix;
import com.frostwork.bgenhance.hooks.hans.HansRefreezeRuntime.Status;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import org.junit.Test;

public final class HansRefreezeRuntimeTest {
    @Test
    public void discoversEveryExactRefreezeCapability() {
        CapabilityMatrix matrix = HansRefreezeRuntime.inspect(
                OplusHansPackage.class.getClassLoader());

        assertTrue(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.AVAILABLE, matrix.status(capability));
        }
        assertNull(matrix.methodOrNull(Capability.STATE_ACCESS));
        assertMethod(
                matrix, Capability.M_TO_F_TIMING, OplusHansDBConfig.class,
                "getMtoFCheckTime", long.class,
                int.class, String.class, int.class, long.class, String.class);
        assertMethod(
                matrix, Capability.FROZEN_TO_MIDDLE,
                HansAppStateMachine.FrozenState.class,
                "transToMiddle", void.class);
        assertMethod(
                matrix, Capability.MIDDLE_ENTER,
                HansAppStateMachine.MiddleState.class,
                "enter", void.class);
        assertMethod(
                matrix, Capability.MIDDLE_EXIT,
                HansAppStateMachine.MiddleState.class,
                "exit", void.class);
        assertMethod(
                matrix, Capability.MIDDLE_PROCESS_MESSAGE,
                HansAppStateMachine.MiddleState.class,
                "processMessage", boolean.class, Message.class);
    }

    @Test
    public void excludesEveryDecoyOverload() {
        assertSingleMatch(Capability.M_TO_F_TIMING, OplusHansDBConfig.class,
                "getMtoFCheckTime");
        assertSingleMatch(Capability.FROZEN_TO_MIDDLE,
                HansAppStateMachine.FrozenState.class, "transToMiddle");
        assertSingleMatch(Capability.MIDDLE_ENTER,
                HansAppStateMachine.MiddleState.class, "enter");
        assertSingleMatch(Capability.MIDDLE_EXIT,
                HansAppStateMachine.MiddleState.class, "exit");
        assertSingleMatch(Capability.MIDDLE_PROCESS_MESSAGE,
                HansAppStateMachine.MiddleState.class, "processMessage");
    }

    @Test
    public void blockedClassLoaderFailsClosedAtCapabilityDetectionOnly() {
        CapabilityMatrix matrix = HansRefreezeRuntime.inspect(
                new BlockingClassLoader(OplusHansPackage.class.getClassLoader()));

        assertFalse(matrix.allAvailable());
        for (Capability capability : Capability.values()) {
            assertEquals(Status.MISSING_CLASS, matrix.status(capability));
            assertNull(matrix.methodOrNull(capability));
        }
    }

    @Test
    public void summaryIncludesEveryCapability() {
        String summary = HansRefreezeRuntime.inspect(
                OplusHansPackage.class.getClassLoader()).summary();
        for (Capability capability : Capability.values()) {
            assertTrue(summary.contains(
                    capability.name().toLowerCase(Locale.ROOT) + "="));
        }
    }

    private static void assertMethod(
            CapabilityMatrix matrix,
            Capability capability,
            Class<?> owner,
            String name,
            Class<?> returnType,
            Class<?>... parameters) {
        Method method = matrix.methodOrNull(capability);
        assertNotNull(method);
        assertEquals(owner, method.getDeclaringClass());
        assertEquals(name, method.getName());
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameters, method.getParameterTypes());
        assertFalse(Modifier.isStatic(method.getModifiers()));
        assertTrue(HansRefreezeRuntime.matches(capability, method));
    }

    private static void assertSingleMatch(
            Capability capability, Class<?> owner, String name) {
        int candidates = 0;
        int matches = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            candidates++;
            if (HansRefreezeRuntime.matches(capability, method)) matches++;
        }
        assertTrue("fixture must include an ABI decoy", candidates > 1);
        assertEquals(1, matches);
    }

    private static final class BlockingClassLoader extends ClassLoader {
        private BlockingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("com.android.server.hans")
                    || name.equals("android.os.Message")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
