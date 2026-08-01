package com.frostwork.bgenhance.residency.codec;

import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.FreezePolicy;
import com.frostwork.bgenhance.residency.model.RefreezePolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.residency.model.WakeChannelPolicy;
import com.frostwork.bgenhance.residency.model.WakeMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies the safety limits shared by policy encoding and decoding. */
public final class ResidencyPolicyValidator {
    public static final int MAX_JSON_BYTES = 512 * 1024;
    public static final int MAX_RULES = 512;
    public static final long MIN_NON_ZERO_DELAY_MS = 1_000L;
    public static final long MAX_DELAY_MS = 24L * 60L * 60L * 1_000L;

    private static final int MAX_PACKAGE_NAME_LENGTH = 255;
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    /**
     * Validates a document and returns an immutable document with rules in canonical order.
     */
    public ResidencyPolicyDocument validate(ResidencyPolicyDocument document) {
        Objects.requireNonNull(document, "document");
        if (document.schemaVersion() != ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION) {
            throw invalid("Unsupported schemaVersion: " + document.schemaVersion());
        }
        if (document.policyRevision() < 0L) {
            throw invalid("policyRevision must be non-negative");
        }
        if (document.rules().size() > MAX_RULES) {
            throw invalid("rules exceeds the limit of " + MAX_RULES);
        }

        List<AppResidencyPolicy> canonicalRules = new ArrayList<>(document.rules().size());
        Set<String> packages = new HashSet<>();
        for (int index = 0; index < document.rules().size(); index++) {
            AppResidencyPolicy rule = document.rules().get(index);
            if (rule == null) {
                throw invalid("rules[" + index + "] must not be null");
            }
            validateRule(rule, index);
            if (!packages.add(rule.packageName())) {
                throw invalid("Duplicate packageName: " + rule.packageName());
            }
            canonicalRules.add(rule);
        }
        canonicalRules.sort(Comparator.comparing(AppResidencyPolicy::packageName));

        return new ResidencyPolicyDocument(
                document.schemaVersion(),
                document.policyRevision(),
                document.enabled(),
                document.defaultNewRuleMode(),
                canonicalRules);
    }

    /** Validates the UTF-8 payload limit before parsing or publishing JSON. */
    public void validateJsonSize(String json) {
        if (json == null) {
            throw invalid("JSON must not be null");
        }
        int byteCount = json.getBytes(StandardCharsets.UTF_8).length;
        if (byteCount > MAX_JSON_BYTES) {
            throw invalid("JSON exceeds the limit of " + MAX_JSON_BYTES + " UTF-8 bytes");
        }
    }

    private static void validateRule(AppResidencyPolicy rule, int index) {
        String prefix = "rules[" + index + "]";
        String packageName = rule.packageName();
        if (packageName.length() > MAX_PACKAGE_NAME_LENGTH
                || !PACKAGE_NAME.matcher(packageName).matches()) {
            throw invalid(prefix + ".packageName is not a valid Android package name");
        }

        FreezePolicy freeze = rule.freezePolicy();
        validateDelay(prefix + ".freezePolicy.rToMDelayMs", freeze.rToMDelayMs());
        validateDelay(prefix + ".freezePolicy.mToFDelayMs", freeze.mToFDelayMs());
        if (freeze.customTiming()
                && (freeze.rToMDelayMs() == 0L || freeze.mToFDelayMs() == 0L)) {
            throw invalid(prefix + ".freezePolicy custom timing requires non-zero delays");
        }

        validateWakeChannel(prefix + ".wakePolicy.packet", rule.wakePolicy().packet());
        validateWakeChannel(prefix + ".wakePolicy.alarm", rule.wakePolicy().alarm());

        RefreezePolicy refreeze = rule.refreezePolicy();
        validateDelay(prefix + ".refreezePolicy.defaultDelayMs", refreeze.defaultDelayMs());
        validateDelay(prefix + ".refreezePolicy.packetDelayMs", refreeze.packetDelayMs());
        validateDelay(prefix + ".refreezePolicy.alarmDelayMs", refreeze.alarmDelayMs());
        validateDelay(prefix + ".refreezePolicy.binderDelayMs", refreeze.binderDelayMs());
        validateDelay(prefix + ".refreezePolicy.serviceDelayMs", refreeze.serviceDelayMs());
        validateDelay(prefix + ".refreezePolicy.broadcastDelayMs", refreeze.broadcastDelayMs());
        validateDelay(prefix + ".refreezePolicy.jobDelayMs", refreeze.jobDelayMs());
    }

    private static void validateWakeChannel(String path, WakeChannelPolicy channel) {
        validateDelay(path + ".cooldownMs", channel.cooldownMs());
        if (channel.mode() == WakeMode.THROTTLE && channel.cooldownMs() == 0L) {
            throw invalid(path + ".cooldownMs must be non-zero when mode is THROTTLE");
        }
    }

    private static void validateDelay(String path, long value) {
        if (value != 0L && (value < MIN_NON_ZERO_DELAY_MS || value > MAX_DELAY_MS)) {
            throw invalid(path + " must be 0 or between " + MIN_NON_ZERO_DELAY_MS
                    + " and " + MAX_DELAY_MS + " milliseconds");
        }
    }

    private static ResidencyPolicyValidationException invalid(String message) {
        return new ResidencyPolicyValidationException(message);
    }
}
