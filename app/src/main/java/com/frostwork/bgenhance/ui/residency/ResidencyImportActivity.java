package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.residency.legacy.LegacyHansImportException;
import com.frostwork.bgenhance.residency.legacy.LegacyHansImportResult;
import com.frostwork.bgenhance.residency.legacy.LegacyHansPolicyImporter;
import com.frostwork.bgenhance.residency.model.AppResidencyPolicy;
import com.frostwork.bgenhance.residency.model.ResidencyPolicyDocument;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Selects a legacy policy file, previews differences and atomically imports selected rules. */
public final class ResidencyImportActivity extends AppCompatActivity {
    private static final String STATE_URI = "import_uri";
    private static final String STATE_SELECTION = "import_selection";
    private static final String STATE_KILL_PROTECTION = "import_kill_protection";
    private static final String STATE_SELECTION_CUSTOMIZED = "import_selection_customized";

    private final ActivityResultLauncher<String[]> fileLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFileSelected);
    private final AtomicInteger requestGeneration = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ResidencyImport");
        thread.setDaemon(true);
        return thread;
    });

    private ResidencyImportAdapter adapter;
    private View pageContent;
    private View initialState;
    private View previewState;
    private View progressState;
    private TextView progressText;
    private TextView initialSummary;
    private View pageTitle;
    private View pageSubtitle;
    private TextView summary;
    private TextView details;
    private TextView selectionCount;
    private MaterialButton selectAll;
    private MaterialButton apply;
    private MaterialSwitch killProtection;
    private Uri currentUri;
    private LegacyHansImportResult legacyResult;
    private ResidencyLegacyImportPlan importPlan;
    private ResidencyPolicyDocument previewDocument;
    private Set<String> previewImportablePackages = Collections.emptySet();
    private Map<String, String> previewLabels = Collections.emptyMap();
    private ArrayList<String> restoredSelection;
    private boolean selectionCustomized;
    private boolean suppressSelectionCallback;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_residency_import);
        UiUtils.applyWindowStyle(this);
        bindViews();
        bindInteractions();
        if (savedInstanceState != null) {
            killProtection.setChecked(savedInstanceState.getBoolean(
                    STATE_KILL_PROTECTION, false));
            restoredSelection = savedInstanceState.getStringArrayList(STATE_SELECTION);
            selectionCustomized = savedInstanceState.getBoolean(
                    STATE_SELECTION_CUSTOMIZED, false);
            String uri = savedInstanceState.getString(STATE_URI);
            if (uri != null && !uri.isEmpty()) {
                currentUri = Uri.parse(uri);
                loadFile(currentUri);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (currentUri != null) outState.putString(STATE_URI, currentUri.toString());
        if (adapter != null && legacyResult != null) {
            outState.putStringArrayList(
                    STATE_SELECTION, new ArrayList<>(adapter.selectedPackages()));
        }
        outState.putBoolean(STATE_KILL_PROTECTION, killProtection.isChecked());
        outState.putBoolean(STATE_SELECTION_CUSTOMIZED, selectionCustomized);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        requestGeneration.incrementAndGet();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        pageContent = findViewById(R.id.residency_import_content);
        initialState = findViewById(R.id.residency_import_initial);
        previewState = findViewById(R.id.residency_import_preview);
        progressState = findViewById(R.id.residency_import_progress);
        progressText = findViewById(R.id.residency_import_progress_text);
        initialSummary = findViewById(R.id.residency_import_initial_summary);
        pageTitle = findViewById(R.id.residency_import_page_title);
        pageSubtitle = findViewById(R.id.residency_import_page_subtitle);
        summary = findViewById(R.id.residency_import_summary);
        details = findViewById(R.id.residency_import_details);
        selectionCount = findViewById(R.id.residency_import_selection_count);
        selectAll = findViewById(R.id.residency_import_select_all);
        apply = findViewById(R.id.residency_import_apply);
        killProtection = findViewById(R.id.residency_import_kill);
        RecyclerView list = findViewById(R.id.residency_import_list);
        adapter = new ResidencyImportAdapter(this::onSelectionChanged);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    private void bindInteractions() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        findViewById(R.id.residency_import_choose).setOnClickListener(view -> chooseFile());
        findViewById(R.id.residency_import_reselect).setOnClickListener(view -> chooseFile());
        selectAll.setOnClickListener(view -> adapter.selectAll(!adapter.allEligibleSelected()));
        apply.setOnClickListener(view -> applyImport());
        killProtection.setOnCheckedChangeListener((button, checked) -> {
            if (legacyResult == null || busy) return;
            Set<String> previousSelection = new HashSet<>(adapter.selectedPackages());
            rebuildPreview(previousSelection, selectionCustomized);
        });
    }

    private void chooseFile() {
        if (busy) return;
        fileLauncher.launch(new String[] {
                "application/json", "text/json", "text/plain", "application/octet-stream"
        });
    }

    private void onFileSelected(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The temporary OpenDocument grant remains valid for this Activity instance.
        }
        restoredSelection = null;
        selectionCustomized = false;
        currentUri = uri;
        loadFile(uri);
    }

    private void loadFile(Uri uri) {
        legacyResult = null;
        importPlan = null;
        previewDocument = null;
        previewImportablePackages = Collections.emptySet();
        previewLabels = Collections.emptyMap();
        showPageHeading(true);
        initialSummary.setText(R.string.residency_import_initial_summary);
        initialState.setVisibility(View.VISIBLE);
        previewState.setVisibility(View.GONE);
        adapter.submit(Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
        int generation = requestGeneration.incrementAndGet();
        showProgress(R.string.residency_import_reading);
        executor.execute(() -> {
            LoadResult result = readAndPlan(uri);
            runOnUiThread(() -> {
                if (generation != requestGeneration.get() || isFinishing()) return;
                hideProgress();
                if (result.error != null) {
                    showLoadError(result.error);
                    return;
                }
                renderPreview(result);
            });
        });
    }

    private LoadResult readAndPlan(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return LoadResult.error(LoadError.OPEN);
            String json = ResidencyLegacyImportReader.read(input);
            LegacyHansImportResult legacy = LegacyHansPolicyImporter.importJson(json);
            ResidencyPolicyDocument current = ResidencyRuleStore.read(getApplicationContext());
            Set<String> importablePackages = new HashSet<>();
            Map<String, String> labels = new HashMap<>();
            for (AppResidencyPolicy policy : legacy.policies()) {
                String packageName = policy.packageName();
                ResidencyAppSafety.Result safety = ResidencyAppSafety.inspect(
                        getApplicationContext(), packageName);
                if (!safety.isRestricted()) importablePackages.add(packageName);
                labels.put(packageName, loadLabel(packageName));
            }
            return LoadResult.success(
                    legacy,
                    current,
                    importablePackages,
                    labels);
        } catch (ResidencyLegacyImportReader.ReadException exception) {
            return LoadResult.error(exception.failure()
                    == ResidencyLegacyImportReader.Failure.TOO_LARGE
                    ? LoadError.TOO_LARGE : LoadError.INVALID_UTF8);
        } catch (LegacyHansImportException exception) {
            return LoadResult.error(LoadError.INVALID_DOCUMENT);
        } catch (IOException exception) {
            return LoadResult.error(LoadError.OPEN);
        } catch (RuntimeException exception) {
            return LoadResult.error(LoadError.CURRENT_SETTINGS);
        }
    }

    @SuppressWarnings("deprecation")
    private String loadLabel(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = info.loadLabel(packageManager);
            return label == null || label.length() == 0 ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            return packageName;
        }
    }

    private void renderPreview(LoadResult result) {
        legacyResult = result.legacy;
        previewDocument = result.current;
        previewImportablePackages = result.importablePackages;
        previewLabels = result.labels;
        boolean restoringSelection = restoredSelection != null;
        Set<String> selection = restoringSelection
                ? new HashSet<>(restoredSelection) : Collections.emptySet();
        restoredSelection = null;
        initialState.setVisibility(View.GONE);
        previewState.setVisibility(View.VISIBLE);
        showPageHeading(getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE);
        rebuildPreview(selection, restoringSelection);
    }

    private void rebuildPreview(Set<String> previousSelection, boolean preserveSelection) {
        importPlan = new ResidencyLegacyImportPlan(
                previewDocument,
                legacyResult,
                previewImportablePackages,
                killProtection.isChecked());
        Set<String> selection = preserveSelection
                ? previousSelection : importPlan.defaultSelectedPackages();
        suppressSelectionCallback = true;
        adapter.submit(importPlan.entries(), previewLabels, selection);
        suppressSelectionCallback = false;
        summary.setText(getString(
                R.string.residency_import_summary,
                importPlan.newCount(),
                importPlan.updateCount(),
                importPlan.unchangedCount(),
                importPlan.skippedCount()));
        details.setText(importPlan.entries().isEmpty()
                ? R.string.residency_import_empty : R.string.residency_import_details);
        renderSelection();
    }

    private void onSelectionChanged() {
        if (!suppressSelectionCallback) selectionCustomized = true;
        renderSelection();
    }

    private void renderSelection() {
        if (selectionCount == null || adapter == null) return;
        int eligible = adapter.eligibleCount();
        int selected = adapter.selectedPackages().size();
        selectionCount.setText(getString(
                R.string.residency_import_selection_count, selected, eligible));
        selectAll.setEnabled(eligible > 0);
        selectAll.setText(adapter.allEligibleSelected()
                ? R.string.residency_import_clear_all : R.string.residency_import_select_all);
        apply.setEnabled(legacyResult != null && selected > 0);
    }

    private void applyImport() {
        if (busy || legacyResult == null || importPlan == null
                || adapter.selectedPackages().isEmpty()) {
            return;
        }
        Set<String> selection = new HashSet<>(adapter.selectedPackages());
        boolean enableKillProtection = killProtection.isChecked();
        LegacyHansImportResult source = legacyResult;
        int generation = requestGeneration.incrementAndGet();
        showProgress(R.string.residency_import_saving);
        executor.execute(() -> {
            ResidencyRuleStore.ImportResult result = null;
            ResidencyRuleStore.Failure failure = null;
            try {
                result = ResidencyRuleStore.importLegacy(
                        getApplicationContext(), source, selection, enableKillProtection);
            } catch (ResidencyRuleStore.StoreException exception) {
                failure = exception.failure();
            } catch (RuntimeException exception) {
                failure = ResidencyRuleStore.Failure.SAVE_FAILED;
            }
            ResidencyRuleStore.ImportResult finalResult = result;
            ResidencyRuleStore.Failure finalFailure = failure;
            runOnUiThread(() -> {
                if (generation != requestGeneration.get() || isFinishing()) return;
                hideProgress();
                if (finalFailure != null) {
                    showStoreError(finalFailure);
                    return;
                }
                int message = finalResult.changedCount() == 0
                        ? R.string.residency_import_no_changes : R.string.residency_import_success;
                Toast.makeText(
                        this,
                        finalResult.changedCount() == 0
                                ? getString(message)
                                : getString(message, finalResult.changedCount()),
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private void showProgress(int message) {
        busy = true;
        progressText.setText(message);
        pageContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        progressState.setVisibility(View.VISIBLE);
        progressState.announceForAccessibility(getString(message));
    }

    private void hideProgress() {
        busy = false;
        progressState.setVisibility(View.GONE);
        pageContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private void showLoadError(LoadError error) {
        int message;
        switch (error) {
            case TOO_LARGE: message = R.string.residency_import_error_large; break;
            case INVALID_UTF8: message = R.string.residency_import_error_encoding; break;
            case INVALID_DOCUMENT: message = R.string.residency_import_error_invalid; break;
            case CURRENT_SETTINGS: message = R.string.residency_error_save; break;
            case OPEN:
            default: message = R.string.residency_import_error_open; break;
        }
        legacyResult = null;
        importPlan = null;
        previewDocument = null;
        previewImportablePackages = Collections.emptySet();
        previewLabels = Collections.emptyMap();
        previewState.setVisibility(View.GONE);
        initialState.setVisibility(View.VISIBLE);
        showPageHeading(true);
        initialSummary.setText(message);
        apply.setEnabled(false);
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private void showPageHeading(boolean visible) {
        pageTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        pageSubtitle.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showStoreError(ResidencyRuleStore.Failure failure) {
        int message;
        switch (failure) {
            case PACKAGE_UNAVAILABLE: message = R.string.residency_error_package_unavailable; break;
            case PACKAGE_RESTRICTED: message = R.string.residency_error_package_restricted; break;
            case RULE_LIMIT: message = R.string.residency_error_rule_limit; break;
            case INVALID_POLICY: message = R.string.residency_import_error_selection; break;
            case VERIFY_FAILED: message = R.string.residency_error_verify; break;
            case SAVE_FAILED:
            default: message = R.string.residency_error_save; break;
        }
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private enum LoadError {
        OPEN,
        TOO_LARGE,
        INVALID_UTF8,
        INVALID_DOCUMENT,
        CURRENT_SETTINGS
    }

    private static final class LoadResult {
        final LegacyHansImportResult legacy;
        final ResidencyPolicyDocument current;
        final Set<String> importablePackages;
        final Map<String, String> labels;
        final LoadError error;

        private LoadResult(
                LegacyHansImportResult legacy,
                ResidencyPolicyDocument current,
                Set<String> importablePackages,
                Map<String, String> labels,
                LoadError error) {
            this.legacy = legacy;
            this.current = current;
            this.importablePackages = importablePackages;
            this.labels = labels;
            this.error = error;
        }

        static LoadResult success(
                LegacyHansImportResult legacy,
                ResidencyPolicyDocument current,
                Set<String> importablePackages,
                Map<String, String> labels) {
            return new LoadResult(legacy, current, importablePackages, labels, null);
        }

        static LoadResult error(LoadError error) {
            return new LoadResult(
                    null,
                    null,
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    error);
        }
    }
}
