package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class HookUtilsTest {
    @Before
    public void setUp() {
        HookUtils.rollbackTo(0);
    }

    @After
    public void tearDown() {
        HookUtils.rollbackTo(0);
    }

    @Test
    public void candidateFailureRollsBackHandlesInstalledByTheSameBatch() {
        FakeHandle first = new FakeHandle("first");
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(HookUtils.HookInstallationException.class, () ->
                HookUtils.hookDeclaredMethods(
                        Fixture.class,
                        method -> method.getName().startsWith("target"),
                        chain -> chain.proceed(),
                        (method, hooker) -> {
                            if (attempts.getAndIncrement() == 0) return first;
                            throw new IllegalStateException("expected install failure");
                        }));

        assertEquals(2, attempts.get());
        assertEquals(1, first.unhookCount.get());
        assertEquals(0, HookUtils.installedHookCount());
    }

    @Test
    public void matcherFailureDoesNotInstallAnyCandidate() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(HookUtils.HookInstallationException.class, () ->
                HookUtils.hookDeclaredMethods(
                        Fixture.class,
                        method -> { throw new IllegalArgumentException("bad matcher"); },
                        chain -> chain.proceed(),
                        (method, hooker) -> {
                            attempts.incrementAndGet();
                            return new FakeHandle(method.getName());
                        }));

        assertEquals(0, attempts.get());
        assertEquals(0, HookUtils.installedHookCount());
    }

    private static final class Fixture {
        void targetOne() {}
        void targetTwo() {}
        void ignored() {}
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
