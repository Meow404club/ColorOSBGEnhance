package com.frostwork.bgenhance.hooks;

import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.os.Process;
import com.frostwork.bgenhance.BridgeContract;
import com.frostwork.bgenhance.FeatureHook;
import com.frostwork.bgenhance.HookTargetIds;
import com.frostwork.bgenhance.HookUtils;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Allows only the module diagnostics provider to start when requested by system_server. */
public final class DiagnosticsBridgeStartupHook implements FeatureHook {
    private static final String CLASS_NAME =
            "com.android.server.am.OplusAppStartupManager";
    private static final String PROCESS_RECORD_CLASS =
            "com.android.server.am.ProcessRecord";
    private static final String PROVIDER_RECORD_CLASS =
            "com.android.server.am.ContentProviderRecord";
    private static final String PROVIDER_CLASS =
            "com.frostwork.bgenhance.ModuleBridgeProvider";

    private volatile Field providerInfoField;
    private volatile boolean accessorPrepared = true;

    @Override
    public String hookTargetId() {
        return HookTargetIds.DIAGNOSTICS_BRIDGE_STARTUP;
    }

    @Override
    public String prefKey() {
        return "";
    }

    @Override
    public String install(ClassLoader loader) {
        int count = HookUtils.hookMatching(
                CLASS_NAME, loader, this::isTarget, this::intercept);
        return status(count, accessorPrepared);
    }

    private boolean isTarget(Method method) {
        if (!matchesTarget(method)) return false;
        try {
            Field field = method.getParameterTypes()[1].getDeclaredField("info");
            if (field.getType() != ProviderInfo.class) {
                accessorPrepared = false;
                providerInfoField = null;
                return true;
            }
            field.setAccessible(true);
            providerInfoField = field;
        } catch (Throwable ignored) {
            accessorPrepared = false;
            providerInfoField = null;
        }
        return true;
    }

    static boolean matchesTarget(Method method) {
        if (method == null
                || !"shouldPreventStartProvider".equals(method.getName())
                || method.getReturnType() != boolean.class
                || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length == 5
                && PROCESS_RECORD_CLASS.equals(parameters[0].getName())
                && PROVIDER_RECORD_CLASS.equals(parameters[1].getName())
                && parameters[2] == ApplicationInfo.class
                && parameters[3] == String.class
                && parameters[4] == int.class;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        int callingUid = HookUtils.intArg(chain, 4, -1);
        ApplicationInfo applicationInfo = applicationInfo(HookUtils.objectArg(chain, 2));
        ProviderInfo providerInfo = providerInfo(HookUtils.objectArg(chain, 1));
        return complete(chain::proceed,
                isDiagnosticsBridgeRequest(callingUid, applicationInfo, providerInfo));
    }

    private ProviderInfo providerInfo(Object providerRecord) {
        Field field = providerInfoField;
        if (field == null || providerRecord == null) return null;
        try {
            Object value = field.get(providerRecord);
            return value instanceof ProviderInfo ? (ProviderInfo) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ApplicationInfo applicationInfo(Object value) {
        return value instanceof ApplicationInfo ? (ApplicationInfo) value : null;
    }

    static boolean isDiagnosticsBridgeRequest(
            int callingUid, ApplicationInfo applicationInfo, ProviderInfo providerInfo) {
        if (applicationInfo == null || providerInfo == null) return false;
        return matchesIdentity(
                callingUid,
                applicationInfo.packageName,
                providerInfo.name,
                providerInfo.authority);
    }

    static boolean matchesIdentity(
            int callingUid,
            String applicationPackage,
            String providerClass,
            String authorities) {
        return callingUid == Process.SYSTEM_UID
                && BridgeContract.MODULE_PACKAGE.equals(applicationPackage)
                && PROVIDER_CLASS.equals(providerClass)
                && containsExactAuthority(authorities, BridgeContract.AUTHORITY);
    }

    private static boolean containsExactAuthority(String authorities, String expected) {
        if (authorities == null || expected == null) return false;
        for (String authority : authorities.split(";", -1)) {
            if (expected.equals(authority)) return true;
        }
        return false;
    }

    static Object complete(OriginalCall original, boolean allowDiagnosticsBridge)
            throws Throwable {
        if (allowDiagnosticsBridge) return false;
        return original.proceed();
    }

    static String status(int count, boolean accessorPrepared) {
        if (count < 0) return "UNSUPPORTED_CLASS";
        if (count == 0) return "UNSUPPORTED_SIGNATURE";
        if (!accessorPrepared) {
            return "FAILED_FAIL_OPEN:PROVIDER_INFO_ACCESSOR:INSTALLED=" + count;
        }
        return "INSTALLED:" + count;
    }

    interface OriginalCall {
        Object proceed() throws Throwable;
    }
}
