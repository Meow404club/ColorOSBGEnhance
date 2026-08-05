package com.frostwork.bgenhance.residency.legacy;

/** Rejects a legacy import without producing a partial policy document. */
public final class LegacyHansImportException extends Exception {
    private final String code;

    public LegacyHansImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LegacyHansImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
