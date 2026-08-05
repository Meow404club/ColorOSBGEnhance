package com.frostwork.bgenhance.residency.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ModuleConfigRepositoryConcurrencyTest {
    @Test
    public void policyAndOrdinarySettingWritesShareOneRevisionLock() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        ModuleSettings.ensureDefaults(context);
        int initialRevision = ModuleSettings.getConfigRevision(context);
        ResidencyPolicyDocument current = ModuleSettings.getResidencyPolicy(context);
        ResidencyPolicyDocument changed = new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                current.policyRevision(),
                !current.enabled(),
                ResidencyMode.BALANCED,
                Collections.emptyList());

        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicBoolean settingSaved = new AtomicBoolean();
        AtomicBoolean policySaved = new AtomicBoolean();
        Thread settingWriter;
        Thread policyWriter;
        synchronized (ModuleSettings.class) {
            settingWriter = new Thread(() -> {
                started.countDown();
                settingSaved.set(ModuleSettings.setBoolean(
                        context, ModuleSettings.KEY_DRY_RUN, true));
                completed.countDown();
            }, "setting-writer");
            policyWriter = new Thread(() -> {
                started.countDown();
                policySaved.set(ModuleConfigRepository.writeResidencyPolicy(
                        context, changed));
                completed.countDown();
            }, "policy-writer");
            settingWriter.start();
            policyWriter.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        settingWriter.join();
        policyWriter.join();
        assertTrue(settingSaved.get());
        assertTrue(policySaved.get());
        assertEquals(initialRevision + 2, ModuleSettings.getConfigRevision(context));
        assertTrue(ModuleSettings.getLocalBoolean(
                context, ModuleSettings.KEY_DRY_RUN));
        assertEquals(!current.enabled(),
                ModuleSettings.getResidencyPolicy(context).enabled());
    }
}
