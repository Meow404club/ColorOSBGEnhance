package com.frostwork.bgenhance.ui;

import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Assigns collision-free, stable RecyclerView IDs for one diagnostics list. */
final class DiagnosticsStableIds {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private DiagnosticsStableIds() {}

    static Map<String, Long> reconcile(
            Map<String, Long> previous,
            List<? extends DiagnosticsUiMapper.UiRow> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyMap();
        Map<String, Long> old = previous == null ? Collections.emptyMap() : previous;
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        LinkedHashMap<Long, String> owners = new LinkedHashMap<>();
        for (DiagnosticsUiMapper.UiRow row : rows) {
            String key = Objects.requireNonNull(row, "row").stableId();
            if (key == null) throw new IllegalArgumentException("stableId must not be null");

            Long candidate = old.get(key);
            if (candidate == null || owners.containsKey(candidate)) {
                candidate = hash(key);
                while (owners.containsKey(candidate)) candidate = next(candidate);
            }
            result.put(key, candidate);
            owners.put(candidate, key);
        }
        return Collections.unmodifiableMap(result);
    }

    private static long hash(String value) {
        long hash = FNV_OFFSET_BASIS;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static long next(long value) {
        return value == Long.MAX_VALUE ? Long.MIN_VALUE : value + 1L;
    }
}
