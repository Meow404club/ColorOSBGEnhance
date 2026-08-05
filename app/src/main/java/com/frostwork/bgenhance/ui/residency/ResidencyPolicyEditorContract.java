package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import com.frostwork.bgenhance.residency.codec.ResidencyPolicyCodec;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyMode;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import java.util.Collections;
import java.util.Objects;

/** Validated intent transport for editing one section of a policy draft. */
final class ResidencyPolicyEditorContract {
    private static final String EXTRA_PACKAGE_NAME = "editor_package_name";
    private static final String EXTRA_SECTION = "editor_section";
    private static final String EXTRA_POLICY = "editor_policy";
    private static final ResidencyPolicyCodec CODEC = new ResidencyPolicyCodec();

    enum Section {
        KILL,
        FREEZE,
        WAKE,
        RESOURCE,
        REFREEZE
    }

    private ResidencyPolicyEditorContract() {}

    static Intent createIntent(
            Context context,
            String packageName,
            Section section,
            ResidencyPolicyDraft draft) {
        return new Intent(context, ResidencyPolicyDetailActivity.class)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_SECTION, Objects.requireNonNull(section, "section").name())
                .putExtra(EXTRA_POLICY, encodeDraft(packageName, draft));
    }

    static Request readRequest(Intent intent) {
        Objects.requireNonNull(intent, "intent");
        String packageName = requirePackageName(intent.getStringExtra(EXTRA_PACKAGE_NAME));
        Section section = parseSection(intent.getStringExtra(EXTRA_SECTION));
        ResidencyPolicyDraft draft = decodeDraft(
                packageName, intent.getStringExtra(EXTRA_POLICY));
        return new Request(packageName, section, draft);
    }

    static Intent createResult(
            String packageName,
            Section section,
            ResidencyPolicyDraft draft) {
        return new Intent()
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_SECTION, Objects.requireNonNull(section, "section").name())
                .putExtra(EXTRA_POLICY, encodeDraft(packageName, draft));
    }

    static ResidencyPolicyDraft readResult(
            @Nullable Intent data,
            String expectedPackageName,
            Section expectedSection) {
        if (data == null) throw new IllegalArgumentException("Missing editor result");
        String packageName = requirePackageName(data.getStringExtra(EXTRA_PACKAGE_NAME));
        if (!packageName.equals(expectedPackageName)) {
            throw new IllegalArgumentException("Editor package changed");
        }
        Section section = parseSection(data.getStringExtra(EXTRA_SECTION));
        if (section != expectedSection) {
            throw new IllegalArgumentException("Editor section changed");
        }
        return decodeDraft(packageName, data.getStringExtra(EXTRA_POLICY));
    }

    static String encodeDraft(String packageName, ResidencyPolicyDraft draft) {
        String checkedPackage = requirePackageName(packageName);
        ResidencyPolicyDraft checkedDraft = Objects.requireNonNull(draft, "draft");
        AppResidencyPolicy policy = new AppResidencyPolicy(
                checkedPackage,
                checkedDraft.enabled(),
                ResidencyMode.CUSTOM,
                checkedDraft.customKillPolicy(),
                checkedDraft.customFreezePolicy(),
                checkedDraft.customWakePolicy(),
                checkedDraft.customResourcePolicy(),
                checkedDraft.customRefreezePolicy());
        ResidencyPolicyDocument document = new ResidencyPolicyDocument(
                ResidencyPolicyDocument.CURRENT_SCHEMA_VERSION,
                0L,
                true,
                ResidencyMode.CUSTOM,
                Collections.singletonList(policy));
        return CODEC.encode(document);
    }

    static ResidencyPolicyDraft decodeDraft(String packageName, @Nullable String encoded) {
        String checkedPackage = requirePackageName(packageName);
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Missing editor policy");
        }
        try {
            ResidencyPolicyDocument document = CODEC.decode(encoded);
            if (document.rules().size() != 1) {
                throw new IllegalArgumentException("Editor policy must contain one rule");
            }
            AppResidencyPolicy policy = document.rules().get(0);
            if (!checkedPackage.equals(policy.packageName())
                    || policy.mode() != ResidencyMode.CUSTOM) {
                throw new IllegalArgumentException("Editor policy identity changed");
            }
            return ResidencyPolicyDraft.from(policy);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) throw exception;
            throw new IllegalArgumentException("Invalid editor policy", exception);
        }
    }

    private static String requirePackageName(@Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("Missing package name");
        }
        return packageName;
    }

    private static Section parseSection(@Nullable String name) {
        if (name == null) throw new IllegalArgumentException("Missing editor section");
        try {
            return Section.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown editor section", exception);
        }
    }

    static final class Request {
        private final String packageName;
        private final Section section;
        private final ResidencyPolicyDraft draft;

        Request(String packageName, Section section, ResidencyPolicyDraft draft) {
            this.packageName = packageName;
            this.section = section;
            this.draft = draft;
        }

        String packageName() { return packageName; }
        Section section() { return section; }
        ResidencyPolicyDraft draft() { return draft; }
    }
}
