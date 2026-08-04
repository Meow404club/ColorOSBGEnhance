package com.frostwork.bgenhance.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import com.android.server.am.ContentProviderRecord;
import com.android.server.am.OplusAppStartupManager;
import com.android.server.am.ProcessRecord;
import java.lang.reflect.Method;
import org.junit.Test;

public final class DiagnosticsBridgeStartupHookTest {
    private static final String MODULE_PACKAGE = "com.frostwork.bgenhance";
    private static final String PROVIDER_CLASS =
            "com.frostwork.bgenhance.ModuleBridgeProvider";
    private static final String PROVIDER_AUTHORITY =
            "com.frostwork.bgenhance.bridge";

    @Test
    public void acceptsOnlyExactFiveParameterBooleanAbi() throws Exception {
        Method exact = OplusAppStartupManager.class.getDeclaredMethod(
                "shouldPreventStartProvider",
                ProcessRecord.class,
                ContentProviderRecord.class,
                ApplicationInfo.class,
                String.class,
                int.class);
        Method wrapper = OplusAppStartupManager.class.getDeclaredMethod(
                "shouldPreventStartProvider",
                ProcessRecord.class,
                ContentProviderRecord.class,
                ApplicationInfo.class,
                String.class);

        assertTrue(DiagnosticsBridgeStartupHook.matchesTarget(exact));
        assertFalse(DiagnosticsBridgeStartupHook.matchesTarget(wrapper));
        assertFalse(DiagnosticsBridgeStartupHook.matchesTarget(
                WrongReturn.class.getDeclaredMethods()[0]));
        assertFalse(DiagnosticsBridgeStartupHook.matchesTarget(
                StaticTarget.class.getDeclaredMethods()[0]));
    }

    @Test
    public void matchesOnlyExactSystemDiagnosticsProviderIdentity() {
        assertTrue(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, MODULE_PACKAGE, PROVIDER_CLASS, PROVIDER_AUTHORITY));
        assertTrue(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, MODULE_PACKAGE, PROVIDER_CLASS,
                "other;" + PROVIDER_AUTHORITY));

        assertFalse(DiagnosticsBridgeStartupHook.matchesIdentity(
                10_044, MODULE_PACKAGE, PROVIDER_CLASS, PROVIDER_AUTHORITY));
        assertFalse(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, "com.example.other", PROVIDER_CLASS, PROVIDER_AUTHORITY));
        assertFalse(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, MODULE_PACKAGE,
                "com.example.OtherProvider", PROVIDER_AUTHORITY));
        assertFalse(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, MODULE_PACKAGE, PROVIDER_CLASS,
                PROVIDER_AUTHORITY + ".other"));
        assertFalse(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, null, PROVIDER_CLASS, PROVIDER_AUTHORITY));
        assertFalse(DiagnosticsBridgeStartupHook.matchesIdentity(
                Process.SYSTEM_UID, MODULE_PACKAGE, PROVIDER_CLASS, null));
    }

    @Test
    public void bypassesTargetWithoutProceedAndPassesOthersExactlyOnce() throws Throwable {
        ChainRecorder allowed = new ChainRecorder();
        assertEquals(Boolean.FALSE,
                DiagnosticsBridgeStartupHook.complete(allowed.call(), true));
        assertEquals(0, allowed.proceedCount);

        ChainRecorder unrelated = new ChainRecorder();
        assertSame(unrelated.result,
                DiagnosticsBridgeStartupHook.complete(unrelated.call(), false));
        assertEquals(1, unrelated.proceedCount);
    }

    @Test
    public void exposesProviderAccessorFailureAsFailOpenInstallation() {
        assertEquals("UNSUPPORTED_CLASS", DiagnosticsBridgeStartupHook.status(-1, true));
        assertEquals("UNSUPPORTED_SIGNATURE", DiagnosticsBridgeStartupHook.status(0, true));
        assertEquals("INSTALLED:1", DiagnosticsBridgeStartupHook.status(1, true));
        assertEquals(
                "UNSUPPORTED_SIGNATURE:EXPECTED=1:FOUND=2",
                DiagnosticsBridgeStartupHook.status(2, true));
        assertEquals(
                "FAILED_FAIL_OPEN:PROVIDER_INFO_ACCESSOR:INSTALLED=1",
                DiagnosticsBridgeStartupHook.status(1, false));
    }

    private static final class ChainRecorder {
        private final Object result = new Object();
        private int proceedCount;

        DiagnosticsBridgeStartupHook.OriginalCall call() {
            return () -> {
                proceedCount++;
                return result;
            };
        }
    }

    private static final class WrongReturn {
        public void shouldPreventStartProvider(
                ProcessRecord process,
                ContentProviderRecord provider,
                ApplicationInfo applicationInfo,
                String callingPackage,
                int callingUid) {}
    }

    private static final class StaticTarget {
        public static boolean shouldPreventStartProvider(
                ProcessRecord process,
                ContentProviderRecord provider,
                ApplicationInfo applicationInfo,
                String callingPackage,
                int callingUid) {
            return true;
        }
    }
}
