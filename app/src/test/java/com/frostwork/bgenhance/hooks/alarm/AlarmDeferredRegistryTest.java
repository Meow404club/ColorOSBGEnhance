package com.frostwork.bgenhance.hooks.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AlarmDeferredRegistryTest {
    @Test
    public void sameObjectIsPresentAndKeepsOriginalValue() {
        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(2);
        Object key = new Object();

        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(key, "first"));
        assertEquals(AlarmDeferredRegistry.PutResult.PRESENT,
                registry.putIfAbsent(key, "second"));
        assertEquals(1, registry.sizeForTest());
        assertEquals("first", registry.remove(key));
    }

    @Test
    public void equalKeysWithDifferentIdentitiesCanCoexistWithoutKeyCallbacks() {
        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(2);
        EqualKey first = new EqualKey();
        EqualKey second = new EqualKey();
        assertTrue(first.equals(second));
        first.resetCalls();
        second.resetCalls();

        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(first, "first"));
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(second, "second"));
        assertEquals(2, registry.sizeForTest());
        assertEquals("first", registry.remove(first));
        assertEquals("second", registry.remove(second));
        assertEquals(0, first.equalsCalls + second.equalsCalls);
        assertEquals(0, first.hashCodeCalls + second.hashCodeCalls);
    }

    @Test
    public void removeOnlyMatchesExactIdentity() {
        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(2);
        EqualKey stored = new EqualKey();
        EqualKey equalButDistinct = new EqualKey();
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(stored, "stored"));

        assertNull(registry.remove(equalButDistinct));
        assertEquals(1, registry.sizeForTest());
        assertEquals("stored", registry.remove(stored));
        assertNull(registry.remove(stored));
    }

    @Test
    public void fullRegistryDoesNotEvictActiveEntries() {
        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(2);
        Object first = new Object();
        Object second = new Object();
        Object rejected = new Object();
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(first, "first"));
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(second, "second"));

        assertEquals(AlarmDeferredRegistry.PutResult.FULL,
                registry.putIfAbsent(rejected, "rejected"));
        assertEquals(2, registry.sizeForTest());
        assertEquals("first", registry.remove(first));
        assertEquals("second", registry.remove(second));
        assertNull(registry.remove(rejected));
    }

    @Test
    public void explicitRemovalRestoresCapacity() {
        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(1);
        Object first = new Object();
        Object second = new Object();
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(first, "first"));

        assertEquals("first", registry.remove(first));
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(second, "second"));
        assertEquals("second", registry.remove(second));
    }

    @Test
    public void collectedWeakKeyRestoresCapacity() {
        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(1);
        Object collected = new Object();
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(collected, "value"));

        assertTrue(registry.enqueueKeyForTest(collected));
        assertEquals(0, registry.sizeForTest());

        Object replacement = new Object();
        assertEquals(AlarmDeferredRegistry.PutResult.ADDED,
                registry.putIfAbsent(replacement, "replacement"));
        assertEquals("replacement", registry.remove(replacement));
    }

    @Test
    public void rejectsInvalidCapacityAndNullInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlarmDeferredRegistry<>(0));
        assertThrows(IllegalArgumentException.class,
                () -> new AlarmDeferredRegistry<>(-1));

        AlarmDeferredRegistry<String> registry = new AlarmDeferredRegistry<>(1);
        Object key = new Object();
        assertThrows(NullPointerException.class,
                () -> registry.putIfAbsent(null, "value"));
        assertThrows(NullPointerException.class,
                () -> registry.putIfAbsent(key, null));
        assertThrows(NullPointerException.class,
                () -> registry.remove(null));
        assertThrows(NullPointerException.class,
                () -> registry.enqueueKeyForTest(null));
        assertFalse(registry.enqueueKeyForTest(new Object()));
        assertEquals(0, registry.sizeForTest());
    }

    private static final class EqualKey {
        private int equalsCalls;
        private int hashCodeCalls;

        @Override
        public boolean equals(Object other) {
            equalsCalls++;
            return other instanceof EqualKey;
        }

        @Override
        public int hashCode() {
            hashCodeCalls++;
            return 1;
        }

        private void resetCalls() {
            equalsCalls = 0;
            hashCodeCalls = 0;
        }
    }
}
