package com.frostwork.bgenhance.residency.codec;

/** Indicates that a residency policy violates the persisted schema contract. */
public final class ResidencyPolicyValidationException extends IllegalArgumentException {
    public ResidencyPolicyValidationException(String message) {
        super(message);
    }
}
