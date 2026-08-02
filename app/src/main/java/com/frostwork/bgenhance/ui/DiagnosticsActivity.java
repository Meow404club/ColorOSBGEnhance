package com.frostwork.bgenhance.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsExportCodec;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;

/** Material diagnostics screen backed exclusively by the bounded SQLite store. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private static final String STATE_SELECTED_TAB = "selected_tab";
    private static final long REFRESH_SETTLE_DELAY_MS = 1_200L;

    private ResidencyDiagnosticsBackend backend;
    private DiagnosticsViewModel viewModel;
    private DiagnosticsRowsAdapter adapter;
    private RecyclerView list;
    private TabLayout tabs;
    private TextView resultCount;
    private View loadingState;
    private TextView loadingText;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptySummary;
    private View errorState;
    private MaterialSwitch verboseSwitch;
    private DiagnosticsViewModel.State currentState;
    private boolean bindingVerbose;
    private boolean clearInFlight;
    private boolean exportInFlight;
    private boolean destroyed;

    private final ModuleApplication.StateListener moduleStateListener = () ->
            runOnUiThread(() -> {
                if (!destroyed && viewModel != null && !clearInFlight) viewModel.refresh();
            });
    private final Runnable settledRefresh = () -> {
        if (!destroyed && viewModel != null && !clearInFlight) viewModel.refresh();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        UiUtils.applyWindowStyle(this);
        backend = ModuleApplication.initializeDiagnostics(this);
        bindViews();
        bindToolbar();
        bindTabs(savedInstanceState == null
                ? 0 : savedInstanceState.getInt(STATE_SELECTED_TAB, 0));
        bindVerboseLogging();

        viewModel = DiagnosticsViewModel.create(this, backend, this::runOnUiThread);
        viewModel.setListener(this::render);
        bindListPaging();
        viewModel.load(viewForTab(tabs.getSelectedTabPosition()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addStateListener(moduleStateListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null && !clearInFlight) viewModel.refresh();
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeStateListener(moduleStateListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        list.removeCallbacks(settledRefresh);
        if (viewModel != null) viewModel.close();
        if (adapter != null) adapter.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_TAB, Math.max(0, tabs.getSelectedTabPosition()));
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        list = findViewById(R.id.diagnostics_list);
        tabs = findViewById(R.id.diagnostics_tabs);
        resultCount = findViewById(R.id.diagnostics_result_count);
        loadingState = findViewById(R.id.diagnostics_loading_state);
        loadingText = findViewById(R.id.diagnostics_loading_text);
        emptyState = findViewById(R.id.diagnostics_empty_state);
        emptyTitle = findViewById(R.id.diagnostics_empty_title);
        emptySummary = findViewById(R.id.diagnostics_empty_summary);
        errorState = findViewById(R.id.diagnostics_error_state);
        verboseSwitch = findViewById(R.id.diagnostics_verbose_switch);

        adapter = new DiagnosticsRowsAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        findViewById(R.id.diagnostics_retry).setOnClickListener(view -> viewModel.refresh());
    }

    private void bindToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        toolbar.inflateMenu(R.menu.menu_diagnostics);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh_diagnostics) {
                refreshDiagnostics();
                return true;
            }
            if (item.getItemId() == R.id.action_export_diagnostics) {
                exportDiagnostics();
                return true;
            }
            if (item.getItemId() == R.id.action_clear_diagnostics) {
                confirmClear();
                return true;
            }
            return false;
        });
    }

    private void bindTabs(int selectedIndex) {
        tabs.addTab(tabs.newTab().setText(R.string.diagnostics_tab_applications));
        tabs.addTab(tabs.newTab().setText(R.string.diagnostics_tab_behaviors));
        tabs.addTab(tabs.newTab().setText(R.string.diagnostics_tab_hooks));
        int index = Math.max(0, Math.min(2, selectedIndex));
        TabLayout.Tab selected = tabs.getTabAt(index);
        if (selected != null) selected.select();
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (viewModel != null && !clearInFlight) {
                    viewModel.load(viewForTab(tab.getPosition()));
                }
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void bindVerboseLogging() {
        bindingVerbose = true;
        verboseSwitch.setChecked(ModuleSettings.getLocalBoolean(
                this, ModuleSettings.KEY_VERBOSE_LOG));
        bindingVerbose = false;
        verboseSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingVerbose) return;
            if (!ModuleSettings.setBoolean(this, ModuleSettings.KEY_VERBOSE_LOG, checked)) {
                bindingVerbose = true;
                verboseSwitch.setChecked(!checked);
                bindingVerbose = false;
                Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.diagnostics_verbose_row).setOnClickListener(
                view -> verboseSwitch.toggle());
    }

    private void bindListPaging() {
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && currentState != null && currentState.hasMore
                        && !recyclerView.canScrollVertically(1)) {
                    viewModel.loadNextPage();
                }
            }
        });
    }

    private void render(DiagnosticsViewModel.State state) {
        if (destroyed || clearInFlight) return;
        currentState = state;
        boolean loading = state.status == DiagnosticsViewModel.Status.IDLE
                || state.status == DiagnosticsViewModel.Status.LOADING;
        boolean content = state.status == DiagnosticsViewModel.Status.CONTENT;
        boolean empty = state.status == DiagnosticsViewModel.Status.EMPTY;
        boolean error = state.status == DiagnosticsViewModel.Status.ERROR;
        loadingText.setText(R.string.diagnostics_loading);
        loadingState.setVisibility(loading ? View.VISIBLE : View.GONE);
        list.setVisibility(content ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        errorState.setVisibility(error ? View.VISIBLE : View.GONE);
        resultCount.setVisibility(content || empty ? View.VISIBLE : View.INVISIBLE);

        if (content) {
            adapter.submitList(state.items);
            resultCount.setText(resultCountText(state.view, state.totalItemCount));
        } else if (empty) {
            adapter.submitList(java.util.Collections.emptyList());
            resultCount.setText(resultCountText(state.view, 0));
            renderEmpty(state.view);
        } else if (!loading) {
            adapter.submitList(java.util.Collections.emptyList());
        }
    }

    private void renderEmpty(DiagnosticsViewModel.View view) {
        if (view == DiagnosticsViewModel.View.APPLICATION) {
            emptyTitle.setText(R.string.diagnostics_apps_empty_title);
            emptySummary.setText(R.string.diagnostics_apps_empty_summary);
        } else {
            emptyTitle.setText(R.string.diagnostics_empty_title);
            emptySummary.setText(R.string.diagnostics_empty_summary);
        }
    }

    private String resultCountText(DiagnosticsViewModel.View view, int count) {
        if (view == DiagnosticsViewModel.View.APPLICATION) {
            return getString(R.string.diagnostics_application_count, count);
        }
        if (view == DiagnosticsViewModel.View.BEHAVIOR) {
            return getString(R.string.diagnostics_behavior_count, count);
        }
        return getString(R.string.diagnostics_hook_count, count);
    }

    private void refreshDiagnostics() {
        if (clearInFlight) return;
        ModuleSettings.notifyConfigChanged(this);
        viewModel.refresh();
        Toast.makeText(this, R.string.diagnostics_refresh_sent, Toast.LENGTH_SHORT).show();
        list.removeCallbacks(settledRefresh);
        list.postDelayed(settledRefresh, REFRESH_SETTLE_DELAY_MS);
    }

    private void exportDiagnostics() {
        if (exportInFlight) return;
        exportInFlight = true;
        Toast.makeText(this, R.string.diagnostics_export_preparing, Toast.LENGTH_SHORT).show();
        backend.exportAsync(
                        DiagnosticsQuery.ExportOptions.redactedDefaults(),
                        FeatureIds.all())
                .thenApply(snapshot -> {
                    if (snapshot == null || !snapshot.errorCode.isEmpty()) return null;
                    return DiagnosticsExportCodec.encode(snapshot);
                })
                .whenComplete((encoded, error) -> runOnUiThread(() -> {
                    exportInFlight = false;
                    if (destroyed) return;
                    if (error != null || encoded == null || !encoded.successful()) {
                        Toast.makeText(this, R.string.diagnostics_export_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    ClipboardManager clipboard = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null) {
                        Toast.makeText(this, R.string.diagnostics_export_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.app_name), encoded.json));
                    Toast.makeText(this, R.string.diagnostics_export_copied,
                            Toast.LENGTH_SHORT).show();
                }));
    }

    private void confirmClear() {
        if (clearInFlight) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_stats_title)
                .setMessage(R.string.reset_stats_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset, (dialog, which) -> clearDiagnostics())
                .show();
    }

    private void clearDiagnostics() {
        clearInFlight = true;
        currentState = null;
        adapter.submitList(java.util.Collections.emptyList());
        list.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        errorState.setVisibility(View.GONE);
        resultCount.setVisibility(View.INVISIBLE);
        loadingText.setText(R.string.diagnostics_clearing);
        loadingState.setVisibility(View.VISIBLE);

        backend.clearAsync(DiagnosticsQuery.ClearScope.USER_ACTIVITY)
                .whenComplete((result, error) -> runOnUiThread(() -> {
                    if (destroyed) return;
                    clearInFlight = false;
                    if (error == null && result != null && result.success
                            && result.epoch >= 0L) {
                        ModuleApplication.publishDiagnosticsEpoch(result.epoch);
                        Toast.makeText(this, R.string.stats_reset_done,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.diagnostics_clear_failed,
                                Toast.LENGTH_LONG).show();
                    }
                    viewModel.refresh();
                }));
    }

    private static DiagnosticsViewModel.View viewForTab(int position) {
        if (position == 1) return DiagnosticsViewModel.View.BEHAVIOR;
        if (position == 2) return DiagnosticsViewModel.View.HOOK_TARGET;
        return DiagnosticsViewModel.View.APPLICATION;
    }
}
