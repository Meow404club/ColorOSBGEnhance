package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds complete policy documents before the repository publishes them. */
public final class ResidencyRuleMutation {
    private ResidencyRuleMutation() {}

    public static ResidencyPolicyDocument upsert(
            ResidencyPolicyDocument current, AppResidencyPolicy replacement) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        AppInstanceKey key = replacement.instanceKey();
        List<AppResidencyPolicy> rules = new ArrayList<>(current.rules().size() + 1);
        boolean replaced = false;
        for (AppResidencyPolicy rule : current.rules()) {
            if (rule.instanceKey().equals(key)) {
                rules.add(replacement);
                replaced = true;
            } else {
                rules.add(rule);
            }
        }
        if (!replaced) rules.add(replacement);
        return documentWithRules(current, rules);
    }

    public static ResidencyPolicyDocument delete(
            ResidencyPolicyDocument current, String packageName) {
        return delete(current, new AppInstanceKey(
                packageName, AppResidencyPolicy.PRIMARY_USER_ID));
    }

    public static ResidencyPolicyDocument delete(
            ResidencyPolicyDocument current, AppInstanceKey key) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(key, "key");
        List<AppResidencyPolicy> rules = new ArrayList<>(current.rules().size());
        for (AppResidencyPolicy rule : current.rules()) {
            if (!rule.instanceKey().equals(key)) rules.add(rule);
        }
        if (rules.size() == current.rules().size()) return current;
        return documentWithRules(current, rules);
    }

    public static AppResidencyPolicy find(
            ResidencyPolicyDocument document, String packageName) {
        return find(document, new AppInstanceKey(
                packageName, AppResidencyPolicy.PRIMARY_USER_ID));
    }

    public static AppResidencyPolicy find(
            ResidencyPolicyDocument document, AppInstanceKey key) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(key, "key");
        for (AppResidencyPolicy rule : document.rules()) {
            if (rule.instanceKey().equals(key)) return rule;
        }
        return null;
    }

    private static ResidencyPolicyDocument documentWithRules(
            ResidencyPolicyDocument current, List<AppResidencyPolicy> rules) {
        return new ResidencyPolicyDocument(
                current.schemaVersion(),
                current.policyRevision(),
                current.enabled(),
                current.defaultNewRuleMode(),
                rules);
    }
}
