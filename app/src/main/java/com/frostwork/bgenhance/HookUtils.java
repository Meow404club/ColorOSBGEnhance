package com.frostwork.bgenhance;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class HookUtils {
    private HookUtils() {}

    public interface MethodMatcher { boolean matches(Method method); }

    public static int hookMatching(String className, ClassLoader loader,
                                   MethodMatcher matcher, XC_MethodHook callback) {
        Class<?> target = XposedHelpers.findClassIfExists(className, loader);
        if (target == null) return -1;
        int count = 0;
        for (Method method : target.getDeclaredMethods()) {
            try {
                if (!matcher.matches(method)) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, callback);
                count++;
                ModuleLog.i("hooked " + className + "." + method.getName() + descriptor(method));
            } catch (Throwable t) {
                ModuleLog.w("hook candidate failed " + className + "." + method.getName() + ": " + t);
            }
        }
        return count;
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

    public static int intArg(Object[] args, int index, int defValue) {
        if (args == null || index < 0 || index >= args.length) return defValue;
        Object value = args[index];
        return value instanceof Number ? ((Number) value).intValue() : defValue;
    }

    public static String stringArg(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) return "";
        Object value = args[index];
        return value == null ? "" : String.valueOf(value);
    }

    public static List<Method> methodsNamed(Class<?> target, String name) {
        ArrayList<Method> methods = new ArrayList<>();
        if (target == null) return methods;
        for (Method method : target.getDeclaredMethods()) {
            if (name.equals(method.getName())) methods.add(method);
        }
        return methods;
    }
}
