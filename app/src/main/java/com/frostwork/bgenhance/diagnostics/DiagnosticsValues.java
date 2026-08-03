package com.frostwork.bgenhance.diagnostics;

import com.frostwork.bgenhance.DiagnosticsResult;
import java.util.Locale;

final class DiagnosticsValues {
    static final int MAX_ID_LENGTH = 128;
    static final int MAX_PACKAGE_LENGTH = 255;
    static final int MAX_TEXT_LENGTH = 512;
    static final int MAX_ERROR_LENGTH = 1_024;
    static final long DAY_MILLIS = 86_400_000L;

    private DiagnosticsValues() {}

    static String id(String value) {
        return text(value, MAX_ID_LENGTH);
    }

    static String packageName(String value) {
        return text(value, MAX_PACKAGE_LENGTH);
    }

    static String text(String value) {
        return text(value, MAX_TEXT_LENGTH);
    }

    static String error(String value) {
        return text(value, MAX_ERROR_LENGTH);
    }

    static String text(String value, int maxLength) {
        if (value == null || value.isEmpty()) return "";
        int length = Math.min(value.length(), maxLength);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char current = value.charAt(i);
            if (current == 0 || (Character.isISOControl(current)
                    && current != '\t' && current != '\n')) {
                out.append(' ');
            } else {
                out.append(current);
            }
        }
        return out.toString().trim();
    }

    static boolean isStrictId(String value, boolean allowEmpty) {
        if (!isStrictString(value, MAX_ID_LENGTH, allowEmpty)) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return false;
        }
        return true;
    }

    static boolean isStrictPackageName(String value, boolean allowEmpty) {
        if (!isStrictString(value, MAX_PACKAGE_LENGTH, allowEmpty)) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return false;
        }
        return true;
    }

    static boolean isStrictText(String value, int maxLength, boolean allowEmpty) {
        return isStrictString(value, maxLength, allowEmpty);
    }

    static boolean isSha256(String value, boolean allowEmpty) {
        if (value == null || (value.isEmpty() && !allowEmpty)) return false;
        if (value.isEmpty()) return true;
        if (value.length() != 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) return false;
        }
        return true;
    }

    private static boolean isStrictString(String value, int maxLength, boolean allowEmpty) {
        if (value == null || value.length() > maxLength) return false;
        if (value.isEmpty()) return allowEmpty;
        if (!value.equals(value.trim())) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == 0 || (Character.isISOControl(current)
                    && current != '\t' && current != '\n')) {
                return false;
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    static long timestamp(long value, long fallback) {
        if (value <= 0L) return Math.max(1L, fallback);
        long maximum = fallback > 0L ? fallback + 300_000L : Long.MAX_VALUE;
        return Math.min(value, maximum);
    }

    static int revision(int value) {
        return Math.max(-1, value);
    }

    static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    static int positiveCount(int value) {
        return Math.max(0, value);
    }

    static long dayBucket(long timestamp) {
        return Math.floorDiv(timestamp, DAY_MILLIS) * DAY_MILLIS;
    }

    static boolean isBlocked(String result) {
        return DiagnosticsResult.countsAsBlocked(result);
    }

    static boolean isFailOpen(String result) {
        return DiagnosticsResult.countsAsFailOpen(result);
    }

    static String throwableCode(Throwable throwable) {
        if (throwable == null) return "";
        String simpleName = throwable.getClass().getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) return "diagnostics_error";
        return text(simpleName.toLowerCase(Locale.ROOT), 64);
    }
}
