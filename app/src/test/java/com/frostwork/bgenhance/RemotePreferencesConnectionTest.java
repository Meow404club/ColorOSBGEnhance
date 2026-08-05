package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RemotePreferencesConnectionTest {
    @Test
    public void acquisitionFailureRetriesTheWholeConnection() {
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger registrations = new AtomicInteger();
        SharedPreferences preferences = preferences(registrations, false);
        RemotePreferencesConnection connection = new RemotePreferencesConnection(() -> {
            if (acquisitions.incrementAndGet() == 1) {
                throw new IllegalStateException("service unavailable");
            }
            return preferences;
        }, (sharedPreferences, key) -> {});

        assertThrows(IllegalStateException.class, connection::ensureConnected);
        assertSame(preferences, connection.ensureConnected());
        assertEquals(2, acquisitions.get());
        assertEquals(1, registrations.get());
    }

    @Test
    public void listenerFailureCannotPublishAStableConnection() {
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger registrations = new AtomicInteger();
        SharedPreferences failing = preferences(registrations, true);
        SharedPreferences healthy = preferences(registrations, false);
        RemotePreferencesConnection connection = new RemotePreferencesConnection(
                () -> acquisitions.incrementAndGet() == 1 ? failing : healthy,
                (sharedPreferences, key) -> {});

        assertThrows(IllegalStateException.class, connection::ensureConnected);
        assertSame(healthy, connection.ensureConnected());
        assertSame(healthy, connection.ensureConnected());
        assertEquals(2, acquisitions.get());
        assertEquals(2, registrations.get());
    }

    private static SharedPreferences preferences(
            AtomicInteger registrations, boolean failRegistration) {
        return (SharedPreferences) Proxy.newProxyInstance(
                RemotePreferencesConnectionTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("registerOnSharedPreferenceChangeListener".equals(method.getName())) {
                        registrations.incrementAndGet();
                        if (failRegistration) {
                            throw new IllegalStateException("listener unavailable");
                        }
                        return null;
                    }
                    if ("toString".equals(method.getName())) return "preferences";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
