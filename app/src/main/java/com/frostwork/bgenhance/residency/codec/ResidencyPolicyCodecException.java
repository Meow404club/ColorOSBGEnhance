package com.frostwork.bgenhance.residency.codec;

/** Indicates that residency policy JSON cannot be encoded or decoded. */
public final class ResidencyPolicyCodecException extends IllegalArgumentException {
    public ResidencyPolicyCodecException(String message) {
        super(message);
    }

    public ResidencyPolicyCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
