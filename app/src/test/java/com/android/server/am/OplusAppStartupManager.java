package com.android.server.am;

import android.content.pm.ApplicationInfo;

public final class OplusAppStartupManager {
    public boolean shouldPreventStartProvider(
            ProcessRecord process,
            ContentProviderRecord provider,
            ApplicationInfo applicationInfo,
            String callingPackage,
            int callingUid) {
        return true;
    }

    public boolean shouldPreventStartProvider(
            ProcessRecord process,
            ContentProviderRecord provider,
            ApplicationInfo applicationInfo,
            String callingPackage) {
        return true;
    }
}
