package com.frostwork.bgenhance;

import android.content.SharedPreferences;
import java.util.Objects;

/** Acquires RemotePreferences and treats listener registration as part of one connection. */
final class RemotePreferencesConnection {
    interface Source {
        SharedPreferences acquire();
    }

    private final Source source;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private SharedPreferences connected;

    RemotePreferencesConnection(
            Source source,
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        this.source = Objects.requireNonNull(source, "source");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    synchronized SharedPreferences ensureConnected() {
        if (connected != null) return connected;
        SharedPreferences candidate = Objects.requireNonNull(
                source.acquire(), "remote preferences");
        try {
            candidate.registerOnSharedPreferenceChangeListener(listener);
            connected = candidate;
            return candidate;
        } catch (RuntimeException | Error failure) {
            connected = null;
            throw failure;
        }
    }
}
