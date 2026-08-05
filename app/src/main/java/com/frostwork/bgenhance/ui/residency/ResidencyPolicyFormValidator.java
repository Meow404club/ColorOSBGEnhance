package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidationException;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidator;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;

/** Converts user-entered seconds and validates the complete draft before publication. */
public final class ResidencyPolicyFormValidator {
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MAX_SECONDS = ResidencyPolicyValidator.MAX_DELAY_MS
            / MILLIS_PER_SECOND;
    private static final ResidencyPolicyValidator POLICY_VALIDATOR =
            new ResidencyPolicyValidator();

    private ResidencyPolicyFormValidator() {}

    public enum InputFailure {
        REQUIRED,
        NOT_A_NUMBER,
        OUT_OF_RANGE
    }

    public static final class InputException extends IllegalArgumentException {
        private final InputFailure failure;

        private InputException(InputFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public InputFailure failure() {
            return failure;
        }
    }

    public static long parseSeconds(String text, boolean required) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            if (required) throw new InputException(InputFailure.REQUIRED);
            return 0L;
        }
        final BigDecimal seconds;
        try {
            seconds = new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            throw new InputException(InputFailure.NOT_A_NUMBER);
        }
        if (seconds.signum() == 0 && !required) return 0L;
        if (seconds.compareTo(BigDecimal.ONE) < 0
                || seconds.compareTo(BigDecimal.valueOf(MAX_SECONDS)) > 0) {
            throw new InputException(InputFailure.OUT_OF_RANGE);
        }
        try {
            return seconds.movePointRight(3).longValueExact();
        } catch (ArithmeticException exception) {
            throw new InputException(InputFailure.NOT_A_NUMBER);
        }
    }

    public static long parseSecondsWhenActive(String text, boolean active) {
        return active ? parseSeconds(text, true) : 0L;
    }

    public static boolean isValid(String packageName, ResidencyPolicyDraft draft) {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(draft, "draft");
        try {
            POLICY_VALIDATOR.validate(new ResidencyPolicyDocument(
                    ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                    0L,
                    true,
                    ResidencyMode.BALANCED,
                    Collections.singletonList(draft.toPolicy(packageName))));
            return true;
        } catch (ResidencyPolicyValidationException exception) {
            return false;
        }
    }
}
