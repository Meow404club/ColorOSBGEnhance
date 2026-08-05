package com.frostwork.bgenhance.ui.residency;

import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidator;
import com.frostwork.bgenhance.residency.legacy.LegacyHansImportResult;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.KillPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Immutable preview and atomic merge plan for imported legacy HANS rules. */
public final class ResidencyLegacyImportPlan {
    public enum Status {
        NEW,
        UPDATE,
        UNCHANGED,
        SKIPPED
    }

    /** One imported package and how it compares with the current policy document. */
    public static final class Entry {
        private final String packageName;
        private final AppResidencyPolicy currentPolicy;
        private final AppResidencyPolicy importedPolicy;
        private final Status status;
        private final boolean selectedByDefault;

        private Entry(
                String packageName,
                AppResidencyPolicy currentPolicy,
                AppResidencyPolicy importedPolicy,
                Status status) {
            this.packageName = packageName;
            this.currentPolicy = currentPolicy;
            this.importedPolicy = importedPolicy;
            this.status = status;
            this.selectedByDefault = status == Status.NEW || status == Status.UPDATE;
        }

        public String packageName() { return packageName; }
        public AppResidencyPolicy currentPolicy() { return currentPolicy; }
        public AppResidencyPolicy importedPolicy() { return importedPolicy; }
        public Status status() { return status; }
        public boolean selectedByDefault() { return selectedByDefault; }
    }

    private final ResidencyPolicyDocument current;
    private final int sourceVersion;
    private final boolean sourceEnabled;
    private final long sourceRevision;
    private final List<Entry> entries;
    private final Map<String, Entry> entriesByPackage;
    private final Set<String> defaultSelectedPackages;
    private final int newCount;
    private final int updateCount;
    private final int unchangedCount;
    private final int skippedCount;

    public ResidencyLegacyImportPlan(
            ResidencyPolicyDocument current,
            LegacyHansImportResult legacy,
            Set<String> importablePackageNames) {
        this(current, legacy, importablePackageNames, false);
    }

    public ResidencyLegacyImportPlan(
            ResidencyPolicyDocument current,
            LegacyHansImportResult legacy,
            Set<String> importablePackageNames,
            boolean enableKillProtection) {
        this.current = Objects.requireNonNull(current, "current");
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(importablePackageNames, "importablePackageNames");

        Map<String, AppResidencyPolicy> currentByPackage = indexCurrentRules(current.rules());
        Set<String> importablePackages = copyPackageSet(
                importablePackageNames, "importablePackageNames");
        TreeMap<String, AppResidencyPolicy> importedByPackage =
                indexImportedRules(legacy.policies(), enableKillProtection);

        List<Entry> mutableEntries = new ArrayList<>(importedByPackage.size());
        Map<String, Entry> mutableEntriesByPackage = new HashMap<>(importedByPackage.size());
        LinkedHashSet<String> mutableDefaultSelection = new LinkedHashSet<>();
        int mutableNewCount = 0;
        int mutableUpdateCount = 0;
        int mutableUnchangedCount = 0;
        int mutableSkippedCount = 0;

        for (Map.Entry<String, AppResidencyPolicy> imported : importedByPackage.entrySet()) {
            String packageName = imported.getKey();
            AppResidencyPolicy currentPolicy = currentByPackage.get(packageName);
            Status status;
            if (!importablePackages.contains(packageName)) {
                status = Status.SKIPPED;
                mutableSkippedCount++;
            } else if (currentPolicy == null) {
                status = Status.NEW;
                mutableNewCount++;
            } else if (currentPolicy.equals(imported.getValue())) {
                status = Status.UNCHANGED;
                mutableUnchangedCount++;
            } else {
                status = Status.UPDATE;
                mutableUpdateCount++;
            }

            Entry entry = new Entry(packageName, currentPolicy, imported.getValue(), status);
            mutableEntries.add(entry);
            mutableEntriesByPackage.put(packageName, entry);
            if (entry.selectedByDefault()) mutableDefaultSelection.add(packageName);
        }

        sourceVersion = legacy.sourceVersion();
        sourceEnabled = legacy.sourceEnabled();
        sourceRevision = legacy.sourceRevision();
        entries = Collections.unmodifiableList(mutableEntries);
        entriesByPackage = Collections.unmodifiableMap(mutableEntriesByPackage);
        defaultSelectedPackages = Collections.unmodifiableSet(mutableDefaultSelection);
        newCount = mutableNewCount;
        updateCount = mutableUpdateCount;
        unchangedCount = mutableUnchangedCount;
        skippedCount = mutableSkippedCount;
    }

    public static ResidencyLegacyImportPlan create(
            ResidencyPolicyDocument current,
            LegacyHansImportResult legacy,
            Set<String> importablePackageNames) {
        return new ResidencyLegacyImportPlan(current, legacy, importablePackageNames);
    }

    public int sourceVersion() { return sourceVersion; }
    public boolean sourceEnabled() { return sourceEnabled; }
    public long sourceRevision() { return sourceRevision; }
    public List<Entry> entries() { return entries; }
    public Set<String> defaultSelectedPackages() { return defaultSelectedPackages; }
    public int newCount() { return newCount; }
    public int updateCount() { return updateCount; }
    public int unchangedCount() { return unchangedCount; }
    public int skippedCount() { return skippedCount; }

    /**
     * Applies the selected imported rules without publishing or advancing the policy revision.
     */
    public ResidencyPolicyDocument apply(
            Collection<String> selectedPackages, boolean enableKillProtection) {
        Objects.requireNonNull(selectedPackages, "selectedPackages");
        LinkedHashSet<String> selection = validateSelection(selectedPackages);

        int selectedNewRules = 0;
        for (String packageName : selection) {
            if (entriesByPackage.get(packageName).status() == Status.NEW) selectedNewRules++;
        }
        if (current.rules().size() + selectedNewRules > ResidencyPolicyValidator.MAX_RULES) {
            throw new IllegalArgumentException(
                    "Imported selection exceeds the maximum rule count of "
                            + ResidencyPolicyValidator.MAX_RULES);
        }

        TreeMap<String, AppResidencyPolicy> merged = new TreeMap<>();
        for (AppResidencyPolicy rule : current.rules()) {
            merged.put(rule.packageName(), rule);
        }
        for (String packageName : selection) {
            AppResidencyPolicy imported = entriesByPackage.get(packageName).importedPolicy();
            merged.put(packageName, enableKillProtection
                    ? withKillPolicy(imported, KillPolicy.all()) : imported);
        }

        if (merged.size() > ResidencyPolicyValidator.MAX_RULES) {
            throw new IllegalArgumentException(
                    "Imported selection exceeds the maximum rule count of "
                            + ResidencyPolicyValidator.MAX_RULES);
        }
        return new ResidencyPolicyDocument(
                current.schemaVersion(),
                current.policyRevision(),
                current.enabled(),
                current.defaultNewRuleMode(),
                new ArrayList<>(merged.values()));
    }

    private LinkedHashSet<String> validateSelection(Collection<String> selectedPackages) {
        LinkedHashSet<String> selection = new LinkedHashSet<>();
        for (String packageName : selectedPackages) {
            if (packageName == null) {
                throw new IllegalArgumentException("selectedPackages contains null");
            }
            if (!selection.add(packageName)) {
                throw new IllegalArgumentException("Duplicate selected package: " + packageName);
            }
            Entry entry = entriesByPackage.get(packageName);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown imported package: " + packageName);
            }
            if (entry.status() == Status.SKIPPED) {
                throw new IllegalArgumentException("Package is not importable: " + packageName);
            }
        }
        return selection;
    }

    private static Map<String, AppResidencyPolicy> indexCurrentRules(
            List<AppResidencyPolicy> rules) {
        Map<String, AppResidencyPolicy> indexed = new HashMap<>(rules.size());
        for (AppResidencyPolicy rule : rules) {
            Objects.requireNonNull(rule, "current.rules contains null");
            if (indexed.put(rule.packageName(), rule) != null) {
                throw new IllegalArgumentException(
                        "Duplicate current package: " + rule.packageName());
            }
        }
        return indexed;
    }

    private static TreeMap<String, AppResidencyPolicy> indexImportedRules(
            List<AppResidencyPolicy> policies, boolean enableKillProtection) {
        TreeMap<String, AppResidencyPolicy> indexed = new TreeMap<>();
        for (AppResidencyPolicy policy : policies) {
            Objects.requireNonNull(policy, "legacy.policies contains null");
            AppResidencyPolicy effective = enableKillProtection
                    ? withKillPolicy(policy, KillPolicy.all()) : policy;
            if (indexed.put(effective.packageName(), effective) != null) {
                throw new IllegalArgumentException(
                        "Duplicate imported package: " + effective.packageName());
            }
        }
        return indexed;
    }

    private static Set<String> copyPackageSet(Set<String> packages, String name) {
        Set<String> copy = new HashSet<>(packages.size());
        for (String packageName : packages) {
            if (packageName == null) throw new IllegalArgumentException(name + " contains null");
            copy.add(packageName);
        }
        return copy;
    }

    private static AppResidencyPolicy withKillPolicy(
            AppResidencyPolicy policy, KillPolicy killPolicy) {
        return new AppResidencyPolicy(
                policy.packageName(),
                policy.enabled(),
                policy.mode(),
                killPolicy,
                policy.freezePolicy(),
                policy.wakePolicy(),
                policy.resourcePolicy(),
                policy.refreezePolicy());
    }
}
