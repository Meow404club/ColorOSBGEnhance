package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidationException;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyValidator;
import com.frostwork.bgenhance.residency.config.ModuleConfigRepository;
import com.frostwork.bgenhance.residency.legacy.LegacyHansImportResult;
import com.frostwork.bgenhance.residency.model.AppInstanceKey;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Serializes UI rule changes through validation, one commit and a read-back check. */
public final class ResidencyRuleStore {
    private static final Object WRITE_LOCK = new Object();
    private static final ResidencyPolicyValidator VALIDATOR = new ResidencyPolicyValidator();

    private ResidencyRuleStore() {}

    public enum Failure {
        PACKAGE_UNAVAILABLE,
        PACKAGE_RESTRICTED,
        RULE_LIMIT,
        INVALID_POLICY,
        SAVE_FAILED,
        VERIFY_FAILED
    }

    public static final class StoreException extends Exception {
        private final Failure failure;

        private StoreException(Failure failure, Throwable cause) {
            super(failure.name(), cause);
            this.failure = failure;
        }

        private StoreException(Failure failure) {
            this(failure, null);
        }

        public Failure failure() {
            return failure;
        }
    }

    public static final class ImportResult {
        private final int selectedCount;
        private final int changedCount;

        private ImportResult(int selectedCount, int changedCount) {
            this.selectedCount = selectedCount;
            this.changedCount = changedCount;
        }

        public int selectedCount() {
            return selectedCount;
        }

        public int changedCount() {
            return changedCount;
        }
    }

    public static ResidencyPolicyDocument read(Context context) {
        ModuleConfigRepository.ensureInitialized(Objects.requireNonNull(context, "context"));
        return ModuleConfigRepository.readResidencyPolicy(context);
    }

    public static void save(Context context, AppResidencyPolicy policy) throws StoreException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policy, "policy");
        synchronized (WRITE_LOCK) {
            ResidencyAppItem installed = new ResidencyAppCatalog(context).loadSingle(
                    policy.packageName(), policy.userId());
            if (!installed.installed()
                    || installed.uid() < 0
                    || installed.userId() != policy.userId()) {
                throw new StoreException(Failure.PACKAGE_UNAVAILABLE);
            }
            if (installed.safety().isRestricted()) {
                throw new StoreException(Failure.PACKAGE_RESTRICTED);
            }

            ResidencyPolicyDocument current = read(context);
            if (ResidencyRuleMutation.find(current, policy.instanceKey()) == null
                    && current.rules().size() >= ResidencyPolicyValidator.MAX_RULES) {
                throw new StoreException(Failure.RULE_LIMIT);
            }
            ResidencyPolicyDocument candidate = ResidencyRuleMutation.upsert(current, policy);
            try {
                candidate = VALIDATOR.validate(candidate);
            } catch (ResidencyPolicyValidationException exception) {
                throw new StoreException(Failure.INVALID_POLICY, exception);
            }
            try {
                if (!ModuleConfigRepository.writeResidencyPolicy(context, candidate)) {
                    throw new StoreException(Failure.SAVE_FAILED);
                }
            } catch (StoreException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new StoreException(Failure.SAVE_FAILED, exception);
            }

            AppResidencyPolicy published;
            try {
                published = ResidencyRuleMutation.find(
                        ModuleConfigRepository.readResidencyPolicy(context), policy.instanceKey());
            } catch (RuntimeException exception) {
                throw new StoreException(Failure.VERIFY_FAILED, exception);
            }
            if (!policy.equals(published)) {
                throw new StoreException(Failure.VERIFY_FAILED);
            }
        }
    }

    public static void delete(Context context, String packageName) throws StoreException {
        delete(context, new AppInstanceKey(packageName, AppResidencyPolicy.PRIMARY_USER_ID));
    }

    public static void delete(Context context, AppInstanceKey key) throws StoreException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        synchronized (WRITE_LOCK) {
            ResidencyPolicyDocument current = read(context);
            ResidencyPolicyDocument candidate = ResidencyRuleMutation.delete(current, key);
            if (candidate == current) return;
            try {
                candidate = VALIDATOR.validate(candidate);
                if (!ModuleConfigRepository.writeResidencyPolicy(context, candidate)) {
                    throw new StoreException(Failure.SAVE_FAILED);
                }
            } catch (StoreException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new StoreException(Failure.SAVE_FAILED, exception);
            }
            try {
                if (ResidencyRuleMutation.find(
                        ModuleConfigRepository.readResidencyPolicy(context), key) != null) {
                    throw new StoreException(Failure.VERIFY_FAILED);
                }
            } catch (StoreException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new StoreException(Failure.VERIFY_FAILED, exception);
            }
        }
    }

    /** Rebuilds and publishes an import against the latest document in one transaction. */
    public static ImportResult importLegacy(
            Context context,
            LegacyHansImportResult legacy,
            Collection<String> selectedPackages,
            boolean enableKillProtection) throws StoreException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(selectedPackages, "selectedPackages");
        synchronized (WRITE_LOCK) {
            Set<String> selection = copySelection(selectedPackages);
            Set<String> importable = new HashSet<>();
            for (AppResidencyPolicy policy : legacy.policies()) {
                ResidencyAppSafety.Result safety = ResidencyAppSafety.inspect(
                        context, policy.packageName());
                if (!safety.isRestricted()) importable.add(policy.packageName());
            }
            for (String packageName : selection) {
                ResidencyAppSafety.Result safety = ResidencyAppSafety.inspect(context, packageName);
                if (safety.restriction() == ResidencyAppSafety.Restriction.NOT_INSTALLED) {
                    throw new StoreException(Failure.PACKAGE_UNAVAILABLE);
                }
                if (safety.isRestricted()) {
                    throw new StoreException(Failure.PACKAGE_RESTRICTED);
                }
            }

            ResidencyPolicyDocument current = read(context);
            ResidencyPolicyDocument candidate;
            try {
                candidate = new ResidencyLegacyImportPlan(current, legacy, importable)
                        .apply(selection, enableKillProtection);
                candidate = VALIDATOR.validate(candidate);
            } catch (IllegalArgumentException exception) {
                if (wouldExceedRuleLimit(current, legacy, selection)) {
                    throw new StoreException(Failure.RULE_LIMIT, exception);
                }
                throw new StoreException(Failure.INVALID_POLICY, exception);
            }

            int changedCount = changedRuleCount(current, candidate, selection);
            try {
                if (!ModuleConfigRepository.writeResidencyPolicy(context, candidate)) {
                    throw new StoreException(Failure.SAVE_FAILED);
                }
            } catch (StoreException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new StoreException(Failure.SAVE_FAILED, exception);
            }

            ResidencyPolicyDocument published;
            try {
                published = ModuleConfigRepository.readResidencyPolicy(context);
            } catch (RuntimeException exception) {
                throw new StoreException(Failure.VERIFY_FAILED, exception);
            }
            long expectedRevision = changedCount == 0
                    ? current.policyRevision() : current.policyRevision() + 1L;
            if (published.policyRevision() != expectedRevision
                    || published.enabled() != current.enabled()
                    || published.defaultNewRuleMode() != current.defaultNewRuleMode()
                    || !published.rules().equals(candidate.rules())) {
                throw new StoreException(Failure.VERIFY_FAILED);
            }
            return new ImportResult(selection.size(), changedCount);
        }
    }

    private static Set<String> copySelection(Collection<String> selectedPackages)
            throws StoreException {
        Set<String> selection = new HashSet<>();
        for (String packageName : selectedPackages) {
            if (packageName == null || !selection.add(packageName)) {
                throw new StoreException(Failure.INVALID_POLICY);
            }
        }
        return selection;
    }

    private static boolean wouldExceedRuleLimit(
            ResidencyPolicyDocument current,
            LegacyHansImportResult legacy,
            Set<String> selection) {
        Set<AppInstanceKey> known = new HashSet<>();
        for (AppResidencyPolicy policy : current.rules()) known.add(policy.instanceKey());
        int count = known.size();
        for (AppResidencyPolicy policy : legacy.policies()) {
            if (selection.contains(policy.packageName())
                    && known.add(new AppInstanceKey(
                            policy.packageName(), AppResidencyPolicy.PRIMARY_USER_ID))) {
                count++;
            }
        }
        return count > ResidencyPolicyValidator.MAX_RULES;
    }

    private static int changedRuleCount(
            ResidencyPolicyDocument current,
            ResidencyPolicyDocument candidate,
            Set<String> selection) {
        int changed = 0;
        for (String packageName : selection) {
            AppResidencyPolicy before = ResidencyRuleMutation.find(current, packageName);
            AppResidencyPolicy after = ResidencyRuleMutation.find(candidate, packageName);
            if (!Objects.equals(before, after)) changed++;
        }
        return changed;
    }
}
