package com.frostwork.bgenhance.ui.residency;

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
        List<AppResidencyPolicy> rules = new ArrayList<>(current.rules().size() + 1);
        boolean replaced = false;
        for (AppResidencyPolicy rule : current.rules()) {
            if (rule.packageName().equals(replacement.packageName())) {
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
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(packageName, "packageName");
        List<AppResidencyPolicy> rules = new ArrayList<>(current.rules().size());
        for (AppResidencyPolicy rule : current.rules()) {
            if (!rule.packageName().equals(packageName)) rules.add(rule);
        }
        if (rules.size() == current.rules().size()) return current;
        return documentWithRules(current, rules);
    }

    public static AppResidencyPolicy find(
            ResidencyPolicyDocument document, String packageName) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(packageName, "packageName");
        for (AppResidencyPolicy rule : document.rules()) {
            if (rule.packageName().equals(packageName)) return rule;
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
