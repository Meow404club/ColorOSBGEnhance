package com.frostwork.bgenhance.residency.config;

/** Indicates that a runtime configuration JSON envelope cannot be decoded. */
public final class RuntimeConfigCodecException extends IllegalArgumentException {
    public RuntimeConfigCodecException(String message) {
        super(message);
    }

    public RuntimeConfigCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
