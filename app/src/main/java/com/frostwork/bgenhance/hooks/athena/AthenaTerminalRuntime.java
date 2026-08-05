package com.frostwork.bgenhance.hooks.athena;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves Athena's six terminal methods and every verified direct caller. */
public final class AthenaTerminalRuntime {
    private AthenaTerminalRuntime() {}

    public static CapabilityMatrix inspect(ClassLoader loader) {
        EnumMap<Capability, Entry> capabilities = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            capabilities.put(capability, resolve(capability, loader));
        }
        EnumMap<DirectCaller, Entry> callers = new EnumMap<>(DirectCaller.class);
        for (DirectCaller caller : DirectCaller.values()) {
            callers.put(caller, resolve(caller, loader));
        }
        return new CapabilityMatrix(capabilities, callers);
    }

    public static boolean matches(Capability capability, Method method) {
        return matchesExact(capability, method);
    }

    public static boolean matches(DirectCaller caller, Method method) {
        return matchesExact(caller, method);
    }

    private static boolean matchesExact(MethodSpec spec, Method method) {
        if (spec == null
                || method == null
                || method.getModifiers() != spec.modifiers()
                || !spec.owner().equals(method.getDeclaringClass().getName())
                || !spec.methodName().equals(method.getName())
                || !spec.returnType().equals(method.getReturnType().getName())) {
            return false;
        }
        Class<?>[] actual = method.getParameterTypes();
        String[] expected = spec.parameterTypes();
        if (actual.length != expected.length) return false;
        for (int index = 0; index < actual.length; index++) {
            if (!expected[index].equals(actual[index].getName())) return false;
        }
        return true;
    }

    private static Entry resolve(MethodSpec spec, ClassLoader loader) {
        Class<?> owner;
        try {
            owner = Class.forName(spec.owner(), false, loader);
        } catch (ClassNotFoundException error) {
            return new Entry(Status.MISSING_CLASS, null);
        } catch (LinkageError | RuntimeException error) {
            return new Entry(Status.LOOKUP_FAILED, null);
        }

        Method found = null;
        try {
            for (Method candidate : owner.getDeclaredMethods()) {
                if (!matchesExact(spec, candidate)) continue;
                if (found != null) return new Entry(Status.AMBIGUOUS_METHOD, null);
                found = candidate;
            }
            if (found == null) return new Entry(Status.MISSING_METHOD, null);
            found.setAccessible(true);
            return new Entry(Status.AVAILABLE, found);
        } catch (LinkageError | RuntimeException error) {
            return new Entry(Status.LOOKUP_FAILED, null);
        }
    }

    public enum Capability implements MethodSpec {
        PERIODIC_SINGLE(
                "b4.e1",
                "h",
                "void",
                "java.lang.String",
                "int",
                "com.oplus.app.athena.ProcDetailInfo",
                "com.oplus.app.athena.ClearRecord"),
        PERIODIC_LIST(
                "b4.e1",
                "l",
                "void",
                "java.util.List",
                "com.oplus.app.athena.ClearRecord",
                "com.oplus.app.athena.KeepRecord",
                "int"),
        TOURNAMENT_SINGLE(
                "b4.x",
                "C0",
                "void",
                "com.oplus.app.athena.ProcDetailInfo",
                "com.oplus.app.athena.ClearRecord",
                "com.oplus.app.athena.KeepRecord",
                "int",
                "int",
                "java.lang.String"),
        MEMORY_SINGLE(
                "e4.t",
                "T0",
                "void",
                "e4.a0",
                "e4.y"),
        MEMORY_GROUP(
                "e4.t",
                "S0",
                "void",
                "e4.z",
                "e4.y",
                "d3.a"),
        MEMORY_PRERELEASE(
                "e4.t",
                "m1",
                "java.util.List",
                "java.util.List",
                "java.util.Map",
                "e4.y",
                "java.util.Map");

        private final String owner;
        private final String methodName;
        private final String returnType;
        private final String[] parameterTypes;

        Capability(
                String owner,
                String methodName,
                String returnType,
                String... parameterTypes) {
            this.owner = owner;
            this.methodName = methodName;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes.clone();
        }

        @Override public String owner() { return owner; }
        @Override public String methodName() { return methodName; }
        @Override public String returnType() { return returnType; }
        @Override public String[] parameterTypes() { return parameterTypes.clone(); }
        @Override public int modifiers() { return Modifier.PRIVATE; }
    }

    public enum DirectCaller implements MethodSpec {
        PERIODIC_SINGLE_CALLER(
                "b4.e1",
                "j",
                Modifier.PRIVATE,
                "void",
                "java.util.List",
                "java.util.Map",
                "com.oplus.app.athena.ClearRecord",
                "com.oplus.app.athena.KeepRecord",
                "int"),
        PERIODIC_LIST_CALLER(
                "b4.e1",
                "k",
                Modifier.PUBLIC,
                "void"),
        TOURNAMENT_D0_CALLER(
                "b4.x",
                "D0",
                Modifier.PRIVATE,
                "void",
                "com.oplus.app.athena.ClearRecord",
                "int",
                "int",
                "java.lang.String",
                "int",
                "java.lang.String"),
        TOURNAMENT_E0_CALLER(
                "b4.x",
                "E0",
                Modifier.PRIVATE,
                "void",
                "com.oplus.app.athena.ClearRecord",
                "int",
                "int",
                "java.lang.String",
                "int",
                "java.lang.String"),
        TOURNAMENT_F0_CALLER(
                "b4.x",
                "F0",
                Modifier.PRIVATE,
                "void",
                "com.oplus.app.athena.ClearRecord",
                "java.util.ArrayList",
                "java.lang.String",
                "int",
                "java.lang.String",
                "int"),
        TOURNAMENT_P0_CALLER(
                "b4.x",
                "P0",
                Modifier.PRIVATE,
                "void",
                "java.util.ArrayList",
                "int",
                "java.lang.String"),
        TOURNAMENT_R0_CALLER(
                "b4.x",
                "R0",
                Modifier.PRIVATE,
                "void",
                "com.oplus.app.athena.ClearRecord",
                "com.oplus.app.athena.KeepRecord",
                "int",
                "int",
                "java.lang.String",
                "boolean",
                "int",
                "java.lang.String"),
        TOURNAMENT_S0_CALLER(
                "b4.x",
                "S0",
                Modifier.PRIVATE,
                "void",
                "com.oplus.app.athena.ClearRecord",
                "com.oplus.app.athena.KeepRecord",
                "java.util.ArrayList",
                "boolean",
                "int",
                "java.lang.String"),
        TOURNAMENT_Z0_CALLER(
                "b4.x",
                "z0",
                Modifier.PRIVATE,
                "void",
                "com.oplus.app.athena.ClearRecord",
                "com.oplus.app.athena.KeepRecord",
                "int",
                "int",
                "java.lang.String",
                "int",
                "java.lang.String"),
        MEMORY_PRERELEASE_CALLER(
                "e4.t",
                "I",
                Modifier.PRIVATE,
                "java.util.List",
                "java.util.List",
                "java.util.Map",
                "java.util.Map",
                "e4.y",
                "boolean",
                "boolean"),
        MEMORY_TERMINAL_CALLER(
                "e4.t",
                "U0",
                Modifier.PRIVATE,
                "void",
                "java.util.List",
                "e4.y",
                "java.util.Map");

        private final String owner;
        private final String methodName;
        private final int modifiers;
        private final String returnType;
        private final String[] parameterTypes;

        DirectCaller(
                String owner,
                String methodName,
                int modifiers,
                String returnType,
                String... parameterTypes) {
            this.owner = owner;
            this.methodName = methodName;
            this.modifiers = modifiers;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes.clone();
        }

        @Override public String owner() { return owner; }
        @Override public String methodName() { return methodName; }
        @Override public String returnType() { return returnType; }
        @Override public String[] parameterTypes() { return parameterTypes.clone(); }
        @Override public int modifiers() { return modifiers; }
    }

    public enum Status {
        AVAILABLE,
        MISSING_CLASS,
        MISSING_METHOD,
        AMBIGUOUS_METHOD,
        LOOKUP_FAILED
    }

    public static final class CapabilityMatrix {
        private final Map<Capability, Entry> capabilities;
        private final Map<DirectCaller, Entry> callers;

        private CapabilityMatrix(
                EnumMap<Capability, Entry> capabilities,
                EnumMap<DirectCaller, Entry> callers) {
            this.capabilities = Collections.unmodifiableMap(new EnumMap<>(capabilities));
            this.callers = Collections.unmodifiableMap(new EnumMap<>(callers));
        }

        public Status status(Capability capability) {
            Entry entry = capabilities.get(Objects.requireNonNull(capability, "capability"));
            return entry == null ? Status.LOOKUP_FAILED : entry.status;
        }

        public Method methodOrNull(Capability capability) {
            Entry entry = capabilities.get(Objects.requireNonNull(capability, "capability"));
            return entry == null ? null : entry.method;
        }

        public Status callerStatus(DirectCaller caller) {
            Entry entry = callers.get(Objects.requireNonNull(caller, "caller"));
            return entry == null ? Status.LOOKUP_FAILED : entry.status;
        }

        public Method callerMethodOrNull(DirectCaller caller) {
            Entry entry = callers.get(Objects.requireNonNull(caller, "caller"));
            return entry == null ? null : entry.method;
        }

        public boolean allAvailable() {
            for (Capability capability : Capability.values()) {
                if (status(capability) != Status.AVAILABLE) return false;
            }
            for (DirectCaller caller : DirectCaller.values()) {
                if (callerStatus(caller) != Status.AVAILABLE) return false;
            }
            return true;
        }

        public String summary() {
            StringBuilder out = new StringBuilder();
            for (Capability capability : Capability.values()) {
                appendSummary(out, "terminal_" + capability.name(), status(capability));
            }
            for (DirectCaller caller : DirectCaller.values()) {
                appendSummary(out, "caller_" + caller.name(), callerStatus(caller));
            }
            return out.toString();
        }

        private static void appendSummary(StringBuilder out, String name, Status status) {
            if (out.length() > 0) out.append(',');
            out.append(name.toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(status.name());
        }
    }

    private interface MethodSpec {
        String owner();
        String methodName();
        String returnType();
        String[] parameterTypes();
        int modifiers();
    }

    private static final class Entry {
        private final Status status;
        private final Method method;

        private Entry(Status status, Method method) {
            this.status = Objects.requireNonNull(status, "status");
            this.method = method;
        }
    }
}
