package com.frostwork.bgenhance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class FeatureIdsTest {
    @Test
    public void serializedValuesRemainBackwardCompatible() {
        assertEquals(Arrays.asList(
                "athena_periodic",
                "oguard_kill",
                "oguard_force_stop",
                "fast_clear_camera_start",
                "fast_clear_camera_operation",
                "fast_clear_game_start",
                "fast_clear_ai",
                "tournament",
                "memory_guard_force",
                "memory_guard_over",
                "nirvana",
                "camera_low_free",
                "game_low_free",
                "osense_per_process",
                "hans_freeze",
                "hans_wake",
                "super_freeze",
                "android_freeze"
        ), FeatureIds.all());
    }

    @Test
    public void allIsABijectionWithPublicIdentifiers() throws Exception {
        List<String> identifiers = FeatureIds.all();
        List<String> publicValues = publicStringConstants(FeatureIds.class);

        assertEquals(identifiers.size(), new HashSet<>(identifiers).size());
        assertEquals(publicValues.size(), new HashSet<>(publicValues).size());
        assertEquals(new HashSet<>(publicValues), new HashSet<>(identifiers));
        assertEquals(publicValues.size(), identifiers.size());
        try {
            identifiers.add("unexpected");
            fail("feature IDs must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static List<String> publicStringConstants(Class<?> type) throws Exception {
        List<String> result = new ArrayList<>();
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == String.class
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)) {
                result.add((String) field.get(null));
            }
        }
        return result;
    }
}
