package com.frostwork.bgenhance.residency.config;

/** Indicates that a runtime configuration violates the persisted schema contract. */
public final class RuntimeConfigValidationException extends IllegalArgumentException {
    public RuntimeConfigValidationException(String message) {
        super(message);
    }

    public RuntimeConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
