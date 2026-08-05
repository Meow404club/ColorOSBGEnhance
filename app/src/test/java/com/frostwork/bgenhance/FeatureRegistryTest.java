package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class FeatureRegistryTest {
    private static final ClassLoader LOADER = FeatureRegistryTest.class.getClassLoader();

    @Before
    public void resetBeforeTest() {
        FeatureRegistry.resetForTests();
    }

    @After
    public void resetAfterTest() {
        FeatureRegistry.resetForTests();
    }

    @Test
    public void retainsFinalStatusAndRecognizesOnlyInstalledPrefix() {
        String installedTarget = "registry_test_installed";
        String unavailableTarget = "registry_test_unavailable";

        FeatureRegistry.install(hook(installedTarget, () -> "INSTALLED:METHODS=2"), LOADER);
        FeatureRegistry.install(hook(unavailableTarget, () -> "UNSUPPORTED_METHOD"), LOADER);

        assertEquals("INSTALLED:METHODS=2",
                FeatureRegistry.installationStatus(installedTarget).orElseThrow());
        assertTrue(FeatureRegistry.isInstalled(installedTarget));
        assertEquals("UNSUPPORTED_METHOD",
                FeatureRegistry.installationStatus(unavailableTarget).orElseThrow());
        assertFalse(FeatureRegistry.isInstalled(unavailableTarget));
    }

    @Test
    public void unknownTargetHasNoStatusAndIsNotInstalled() {
        assertTrue(FeatureRegistry.installationStatus("registry_test_unknown").isEmpty());
        assertTrue(FeatureRegistry.installationStatus(null).isEmpty());
        assertFalse(FeatureRegistry.isInstalled("registry_test_unknown"));
        assertFalse(FeatureRegistry.isInstalled(null));
    }

    @Test
    public void thrownInstallationIsRetainedAndSameTargetIsNotRetried() {
        String target = "registry_test_failed";
        AtomicInteger calls = new AtomicInteger();
        FeatureHook failing = hook(target, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("expected");
        });

        FeatureRegistry.install(failing, LOADER);
        FeatureRegistry.install(hook(target, () -> {
            calls.incrementAndGet();
            return "INSTALLED";
        }), LOADER);

        assertEquals(1, calls.get());
        assertEquals("FAILED_FAIL_OPEN:IllegalStateException",
                FeatureRegistry.installationStatus(target).orElseThrow());
        assertFalse(FeatureRegistry.isInstalled(target));
    }

    @Test
    public void unsupportedInstallationRollsBackEveryNewHandle() {
        FakeHandle first = new FakeHandle("first");
        FakeHandle second = new FakeHandle("second");

        FeatureRegistry.install(hook("registry_test_rollback", () -> {
            HookUtils.trackHandle(first);
            HookUtils.trackHandle(second);
            return "UNSUPPORTED_PARTIAL";
        }), LOADER);

        assertEquals(1, first.unhookCount.get());
        assertEquals(1, second.unhookCount.get());
        assertEquals(0, HookUtils.installedHookCount());
        assertFalse(FeatureRegistry.isInstalled("registry_test_rollback"));
    }

    @Test
    public void successfulInstallationCommitsNewHandles() {
        FakeHandle handle = new FakeHandle("committed");

        FeatureRegistry.install(hook("registry_test_commit", () -> {
            HookUtils.trackHandle(handle);
            return "INSTALLED:1";
        }), LOADER);

        assertEquals(0, handle.unhookCount.get());
        assertEquals(1, HookUtils.installedHookCount());
        assertTrue(FeatureRegistry.isInstalled("registry_test_commit"));
    }

    @Test
    public void concurrentDuplicateDoesNotInstallAndStatusAppearsOnlyWhenFinal() throws Exception {
        String target = "registry_test_concurrent";
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FeatureHook first = hook(target, () -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return "INSTALLED:PRIMARY";
        });

        Thread installer = new Thread(() -> FeatureRegistry.install(first, LOADER));
        installer.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(FeatureRegistry.installationStatus(target).isEmpty());
        assertFalse(FeatureRegistry.isInstalled(target));

        FeatureRegistry.install(hook(target, () -> {
            calls.incrementAndGet();
            return "INSTALLED:DUPLICATE";
        }), LOADER);
        release.countDown();
        installer.join(2_000L);

        assertFalse(installer.isAlive());
        assertEquals(1, calls.get());
        assertEquals("INSTALLED:PRIMARY",
                FeatureRegistry.installationStatus(target).orElseThrow());
        assertTrue(FeatureRegistry.isInstalled(target));
    }

    private static FeatureHook hook(String target, Installer installer) {
        return new FeatureHook() {
            @Override
            public String hookTargetId() {
                return target;
            }

            @Override
            public String prefKey() {
                return "registry_test";
            }

            @Override
            public String install(ClassLoader classLoader) {
                return installer.install();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private interface Installer {
        String install();
    }

    private static final class FakeHandle implements XposedInterface.HookHandle {
        private final String id;
        private final AtomicInteger unhookCount = new AtomicInteger();

        FakeHandle(String id) {
            this.id = id;
        }

        @Override public Executable getExecutable() { return null; }
        @Override public void unhook() { unhookCount.incrementAndGet(); }
        @Override public String getId() { return id; }
        @Override public XposedInterface.HookHandle replaceHook(
                XposedInterface.Hooker hooker) { return this; }
    }
}
