package com.frostwork.bgenhance;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HookUtils {
    private HookUtils() {}

    public interface MethodMatcher { boolean matches(Method method); }

    interface MethodHookInstaller {
        XposedInterface.HookHandle install(
                Method method, XposedInterface.Hooker hooker) throws Throwable;
    }

    static final class HookInstallationException extends RuntimeException {
        HookInstallationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final List<XposedInterface.HookHandle> HANDLES = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static volatile XposedModule sModule;

    public static void initialize(XposedModule module) {
        sModule = module;
    }

    public static int hookMatching(String className, ClassLoader loader,
                                   MethodMatcher matcher, XposedInterface.Hooker hooker) {
        XposedModule module = sModule;
        if (module == null) throw new IllegalStateException("Xposed module not initialized");
        Class<?> target;
        try {
            target = Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            return -1;
        } catch (Throwable t) {
            safeLogWarning("target class lookup failed " + className + ": " + t);
            throw new HookInstallationException(
                    "target class lookup failed " + className, t);
        }
        return hookDeclaredMethods(
                target,
                matcher,
                hooker,
                (method, interceptor) -> module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(interceptor));
    }

    static int hookDeclaredMethods(
            Class<?> target,
            MethodMatcher matcher,
            XposedInterface.Hooker hooker,
            MethodHookInstaller installer) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : target.getDeclaredMethods()) {
            try {
                if (matcher.matches(method)) candidates.add(method);
            } catch (Throwable t) {
                throw new HookInstallationException(
                        "hook matcher failed " + target.getName() + "." + method.getName(), t);
            }
        }

        int checkpoint = installationCheckpoint();
        try {
            for (Method method : candidates) {
                XposedInterface.HookHandle handle = installer.install(method, hooker);
                if (handle == null) {
                    throw new IllegalStateException("hook installer returned null handle");
                }
                trackHandle(handle);
                safeLogInstalled("hooked " + target.getName() + "." + method.getName()
                        + descriptor(method));
            }
            return candidates.size();
        } catch (Throwable error) {
            boolean rolledBack = rollbackTo(checkpoint);
            String suffix = rolledBack ? "" : "; rollback failed";
            throw new HookInstallationException(
                    "hook candidate install failed " + target.getName() + suffix, error);
        }
    }

    public static int installedHookCount() {
        return HANDLES.size();
    }

    static int installationCheckpoint() {
        return HANDLES.size();
    }

    static void trackHandle(XposedInterface.HookHandle handle) {
        if (handle == null) throw new NullPointerException("handle");
        HANDLES.add(handle);
    }

    static boolean rollbackTo(int checkpoint) {
        if (checkpoint < 0 || checkpoint > HANDLES.size()) {
            throw new IllegalArgumentException("invalid Hook checkpoint " + checkpoint);
        }
        List<XposedInterface.HookHandle> added = new ArrayList<>(
                HANDLES.subList(checkpoint, HANDLES.size()));
        boolean complete = true;
        for (int index = added.size() - 1; index >= 0; index--) {
            XposedInterface.HookHandle handle = added.get(index);
            try {
                handle.unhook();
                HANDLES.remove(handle);
            } catch (Throwable error) {
                complete = false;
                safeLogWarning(
                        "hook rollback failed id=" + safeHandleId(handle) + ": " + error);
            }
        }
        return complete;
    }

    private static String safeHandleId(XposedInterface.HookHandle handle) {
        try {
            return String.valueOf(handle.getId());
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void safeLogInstalled(String message) {
        try {
            ModuleLog.i(message);
        } catch (Throwable ignored) {
            // Hook installation must not depend on diagnostics logging.
        }
    }

    private static void safeLogWarning(String message) {
        try {
            ModuleLog.w(message);
        } catch (Throwable ignored) {
            // Hook installation and rollback must not depend on diagnostics logging.
        }
    }

    public static boolean deoptimize(Executable executable) {
        XposedModule module = sModule;
        if (module == null) throw new IllegalStateException("Xposed module not initialized");
        if (executable == null) return false;
        return module.deoptimize(executable);
    }

    public static List<XposedInterface.HookHandle> hookHandles() {
        return Collections.unmodifiableList(new ArrayList<>(HANDLES));
    }

    public static String descriptor(Method method) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> type : method.getParameterTypes()) {
            if (out.length() > 1) out.append(',');
            out.append(type.getName());
        }
        out.append(")->").append(method.getReturnType().getName());
        return out.toString();
    }

    public static int intArg(XposedInterface.Chain chain, int index, int defValue) {
        if (chain == null || index < 0 || index >= chain.getArgs().size()) return defValue;
        Object value = chain.getArg(index);
        return value instanceof Number ? ((Number) value).intValue() : defValue;
    }

    public static String stringArg(XposedInterface.Chain chain, int index) {
        if (chain == null || index < 0 || index >= chain.getArgs().size()) return "";
        Object value = chain.getArg(index);
        return value == null ? "" : String.valueOf(value);
    }

    public static Object objectArg(XposedInterface.Chain chain, int index) {
        if (chain == null || index < 0 || index >= chain.getArgs().size()) return null;
        return chain.getArg(index);
    }

    public static String fieldString(Object object, String fieldName) {
        Object value = fieldValue(object, fieldName);
        return value == null ? "" : String.valueOf(value);
    }

    public static int fieldInt(Object object, String fieldName, int defValue) {
        Object value = fieldValue(object, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : defValue;
    }

    private static Object fieldValue(Object object, String fieldName) {
        if (object == null || fieldName == null || fieldName.isEmpty()) return null;
        Class<?> type = object.getClass();
        String key = type.getName() + '#' + fieldName;
        try {
            Field field = FIELD_CACHE.get(key);
            if (field == null) {
                field = findField(type, fieldName);
                if (field == null) return null;
                field.setAccessible(true);
                FIELD_CACHE.putIfAbsent(key, field);
            }
            return field.get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
