package com.frostwork.bgenhance.diagnostics;

import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Canonical SHA-256 fingerprint of one diagnostics wire Bundle. */
@SuppressWarnings("deprecation")
final class DiagnosticsPayloadFingerprint {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_DEPTH = 4;

    private DiagnosticsPayloadFingerprint() {}

    static String compute(Bundle payload) {
        if (payload == null) throw new IllegalArgumentException("payload is null");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FORMAT_VERSION);
            writeBundle(out, payload, new IdentityHashMap<>(), 0, true);
            out.flush();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                encoded.append(Character.forDigit(value & 0x0f, 16));
            }
            return encoded.toString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("cannot fingerprint diagnostics payload", error);
        }
    }

    static void seal(Bundle payload) {
        payload.putString(DiagnosticsWireParser.KEY_PAYLOAD_HASH, compute(payload));
    }

    private static void writeBundle(
            DataOutputStream out,
            Bundle value,
            IdentityHashMap<Object, Boolean> active,
            int depth,
            boolean root) throws Exception {
        enter(value, active, depth);
        try {
            ArrayList<String> keys = new ArrayList<>();
            for (String key : value.keySet()) {
                if (root && DiagnosticsWireParser.KEY_PAYLOAD_HASH.equals(key)) continue;
                if (key == null) throw new IllegalArgumentException("null bundle key");
                keys.add(key);
            }
            Collections.sort(keys);
            out.writeInt(keys.size());
            for (String key : keys) {
                writeString(out, key);
                writeValue(out, value.get(key), active, depth + 1);
            }
        } finally {
            active.remove(value);
        }
    }

    private static void writeValue(
            DataOutputStream out,
            Object value,
            IdentityHashMap<Object, Boolean> active,
            int depth) throws Exception {
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof Integer) {
            out.writeByte(1);
            out.writeInt((Integer) value);
        } else if (value instanceof Long) {
            out.writeByte(2);
            out.writeLong((Long) value);
        } else if (value instanceof Boolean) {
            out.writeByte(3);
            out.writeBoolean((Boolean) value);
        } else if (value instanceof String) {
            out.writeByte(4);
            writeString(out, (String) value);
        } else if (value instanceof Bundle) {
            out.writeByte(5);
            writeBundle(out, (Bundle) value, active, depth, false);
        } else if (value instanceof ArrayList<?>) {
            out.writeByte(6);
            writeList(out, (ArrayList<?>) value, active, depth);
        } else {
            throw new IllegalArgumentException(
                    "unsupported diagnostics wire value: " + value.getClass().getName());
        }
    }

    private static void writeList(
            DataOutputStream out,
            ArrayList<?> values,
            IdentityHashMap<Object, Boolean> active,
            int depth) throws Exception {
        enter(values, active, depth);
        try {
            out.writeInt(values.size());
            for (Object value : values) writeValue(out, value, active, depth + 1);
        } finally {
            active.remove(values);
        }
    }

    private static void enter(
            Object value, IdentityHashMap<Object, Boolean> active, int depth) {
        if (depth > MAX_DEPTH || active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic or deeply nested diagnostics payload");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
