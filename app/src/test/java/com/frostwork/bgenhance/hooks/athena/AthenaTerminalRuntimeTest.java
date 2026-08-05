package com.frostwork.bgenhance.hooks.athena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;

public final class AthenaTerminalRuntimeTest {
    @Test
    public void resolvesEveryTerminalAndDirectCallerExactly() {
        AthenaTerminalRuntime.CapabilityMatrix matrix = inspect();

        assertTrue(matrix.summary(), matrix.allAvailable());
        for (AthenaTerminalRuntime.Capability capability
                : AthenaTerminalRuntime.Capability.values()) {
            Method method = matrix.methodOrNull(capability);
            assertNotNull(capability.name(), method);
            assertEquals(Modifier.PRIVATE, method.getModifiers());
            assertEquals(capability.owner(), method.getDeclaringClass().getName());
            assertTrue(capability.name(), AthenaTerminalRuntime.matches(capability, method));

            int matches = 0;
            for (Method candidate : method.getDeclaringClass().getDeclaredMethods()) {
                if (AthenaTerminalRuntime.matches(capability, candidate)) matches++;
            }
            assertEquals(capability.name(), 1, matches);
        }

        for (AthenaTerminalRuntime.DirectCaller caller
                : AthenaTerminalRuntime.DirectCaller.values()) {
            Method method = matrix.callerMethodOrNull(caller);
            assertNotNull(caller.name(), method);
            assertEquals(caller.modifiers(), method.getModifiers());
            assertEquals(caller.owner(), method.getDeclaringClass().getName());
            assertTrue(caller.name(), AthenaTerminalRuntime.matches(caller, method));

            int matches = 0;
            for (Method candidate : method.getDeclaringClass().getDeclaredMethods()) {
                if (AthenaTerminalRuntime.matches(caller, candidate)) matches++;
            }
            assertEquals(caller.name(), 1, matches);
        }
    }

    @Test
    public void rejectsStaticBoxedWrongReturnParameterAndOwnerDecoys() {
        AthenaTerminalRuntime.CapabilityMatrix matrix = inspect();
        Map<AthenaTerminalRuntime.Capability, Integer> sameNameDecoys =
                new EnumMap<>(AthenaTerminalRuntime.Capability.class);

        for (AthenaTerminalRuntime.Capability capability
                : AthenaTerminalRuntime.Capability.values()) {
            Method target = matrix.methodOrNull(capability);
            int decoys = 0;
            for (Method candidate : target.getDeclaringClass().getDeclaredMethods()) {
                if (!candidate.equals(target)
                        && candidate.getName().equals(capability.methodName())) {
                    decoys++;
                    assertFalse(
                            capability + " accepted " + candidate,
                            AthenaTerminalRuntime.matches(capability, candidate));
                }
            }
            sameNameDecoys.put(capability, decoys);
        }

        assertTrue(sameNameDecoys.get(
                AthenaTerminalRuntime.Capability.PERIODIC_SINGLE) >= 2);
        assertTrue(sameNameDecoys.get(
                AthenaTerminalRuntime.Capability.TOURNAMENT_SINGLE) >= 2);
        assertTrue(sameNameDecoys.get(
                AthenaTerminalRuntime.Capability.MEMORY_SINGLE) >= 1);
        assertTrue(sameNameDecoys.get(
                AthenaTerminalRuntime.Capability.MEMORY_GROUP) >= 1);
        assertTrue(sameNameDecoys.get(
                AthenaTerminalRuntime.Capability.MEMORY_PRERELEASE) >= 1);

        try {
            Method wrongOwner = WrongOwner.class.getDeclaredMethod(
                    "h",
                    String.class,
                    int.class,
                    com.oplus.app.athena.ProcDetailInfo.class,
                    com.oplus.app.athena.ClearRecord.class);
            assertFalse(AthenaTerminalRuntime.matches(
                    AthenaTerminalRuntime.Capability.PERIODIC_SINGLE,
                    wrongOwner));
        } catch (NoSuchMethodException error) {
            throw new AssertionError(error);
        }

        for (AthenaTerminalRuntime.DirectCaller caller
                : AthenaTerminalRuntime.DirectCaller.values()) {
            Method target = matrix.callerMethodOrNull(caller);
            for (Method candidate : target.getDeclaringClass().getDeclaredMethods()) {
                if (!candidate.equals(target)
                        && candidate.getName().equals(caller.methodName())) {
                    assertFalse(
                            caller + " accepted " + candidate,
                            AthenaTerminalRuntime.matches(caller, candidate));
                }
            }
        }
    }

    @Test
    public void missingOwnerClassesAreReportedPerCapability() {
        ClassLoader loader = new FilteringClassLoader(
                getClass().getClassLoader(), "e4.t");
        AthenaTerminalRuntime.CapabilityMatrix matrix =
                AthenaTerminalRuntime.inspect(loader);

        assertFalse(matrix.allAvailable());
        assertEquals(
                AthenaTerminalRuntime.Status.AVAILABLE,
                matrix.status(AthenaTerminalRuntime.Capability.PERIODIC_SINGLE));
        assertEquals(
                AthenaTerminalRuntime.Status.MISSING_CLASS,
                matrix.status(AthenaTerminalRuntime.Capability.MEMORY_SINGLE));
        assertEquals(
                AthenaTerminalRuntime.Status.MISSING_CLASS,
                matrix.status(AthenaTerminalRuntime.Capability.MEMORY_GROUP));
        assertEquals(
                AthenaTerminalRuntime.Status.MISSING_CLASS,
                matrix.status(AthenaTerminalRuntime.Capability.MEMORY_PRERELEASE));
        assertEquals(
                AthenaTerminalRuntime.Status.MISSING_CLASS,
                matrix.callerStatus(
                        AthenaTerminalRuntime.DirectCaller.MEMORY_PRERELEASE_CALLER));
        assertEquals(
                AthenaTerminalRuntime.Status.MISSING_CLASS,
                matrix.callerStatus(
                        AthenaTerminalRuntime.DirectCaller.MEMORY_TERMINAL_CALLER));
    }

    private AthenaTerminalRuntime.CapabilityMatrix inspect() {
        return AthenaTerminalRuntime.inspect(getClass().getClassLoader());
    }

    private static final class WrongOwner {
        private void h(
                String key,
                int count,
                com.oplus.app.athena.ProcDetailInfo process,
                com.oplus.app.athena.ClearRecord record) {}
    }

    private static final class FilteringClassLoader extends ClassLoader {
        private final String blockedName;

        private FilteringClassLoader(ClassLoader parent, String blockedName) {
            super(parent);
            this.blockedName = blockedName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (blockedName.equals(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }
}
