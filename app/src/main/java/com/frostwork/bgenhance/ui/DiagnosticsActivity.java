package com.frostwork.bgenhance.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.PopupWindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.FeatureIds;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.ModuleSettings;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.AtomicSafDiagnosticsExporter;
import com.frostwork.bgenhance.diagnostics.DiagnosticsExportCodec;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.DiagnosticsReportMetadata;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsViewModel;
import com.frostwork.bgenhance.ui.diagnostics.FullDiagnosticsExportViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Material diagnostics screen backed exclusively by the bounded SQLite store. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private static final String STATE_SELECTED_TAB = "selected_tab";
    private static final long REFRESH_SETTLE_DELAY_MS = 1_200L;
    private final ActivityResultLauncher<String> fullExportDocumentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            AtomicSafDiagnosticsExporter.MIME_TYPE),
                    this::onFullExportDocumentSelected);

    private ResidencyDiagnosticsBackend backend;
    private DiagnosticsViewModel viewModel;
    private FullDiagnosticsExportViewModel fullExportViewModel;
    private DiagnosticsRowsAdapter adapter;
    private RecyclerView list;
    private MaterialButtonToggleGroup tabs;
    private TextView resultCount;
    private TextView historyNotice;
    private View loadingState;
    private TextView loadingText;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptySummary;
    private View errorState;
    private DiagnosticsViewModel.State currentState;
    private PopupWindow optionsPopup;
    private boolean clearInFlight;
    private boolean exportInFlight;
    private boolean started;
    private boolean destroyed;

    private final ModuleApplication.StateListener moduleStateListener = () ->
            runOnUiThread(() -> {
                if (!destroyed && viewModel != null && !clearInFlight) viewModel.refresh();
            });
    private final Runnable settledRefresh = () -> {
        if (!destroyed && viewModel != null && !clearInFlight) viewModel.refresh();
    };
    private final FullDiagnosticsExportViewModel.Listener fullExportListener =
            this::renderFullExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        UiUtils.applyWindowStyle(this);
        backend = ModuleApplication.initializeDiagnostics(this);
        fullExportViewModel = FullDiagnosticsExportViewModel.create(this, this, backend);
        bindViews();
        bindToolbar();
        bindTabs(savedInstanceState == null
                ? 0 : savedInstanceState.getInt(STATE_SELECTED_TAB, 0));

        viewModel = DiagnosticsViewModel.create(this, backend, this::runOnUiThread);
        viewModel.setListener(this::render);
        bindListPaging();
        viewModel.load(viewForTab(selectedTabIndex()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        fullExportViewModel.setListener(fullExportListener);
        ModuleApplication.addStateListener(moduleStateListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null && !clearInFlight) viewModel.refresh();
    }

    @Override
    protected void onStop() {
        started = false;
        ModuleApplication.removeStateListener(moduleStateListener);
        fullExportViewModel.removeListener(fullExportListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        list.removeCallbacks(settledRefresh);
        if (fullExportViewModel != null) {
            fullExportViewModel.removeListener(fullExportListener);
        }
        if (viewModel != null) viewModel.close();
        if (adapter != null) adapter.close();
        if (optionsPopup != null) optionsPopup.dismiss();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_TAB, selectedTabIndex());
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        list = findViewById(R.id.diagnostics_list);
        tabs = findViewById(R.id.diagnostics_tabs);
        resultCount = findViewById(R.id.diagnostics_result_count);
        historyNotice = findViewById(R.id.diagnostics_history_notice);
        loadingState = findViewById(R.id.diagnostics_loading_state);
        loadingText = findViewById(R.id.diagnostics_loading_text);
        emptyState = findViewById(R.id.diagnostics_empty_state);
        emptyTitle = findViewById(R.id.diagnostics_empty_title);
        emptySummary = findViewById(R.id.diagnostics_empty_summary);
        errorState = findViewById(R.id.diagnostics_error_state);
        adapter = new DiagnosticsRowsAdapter(this, behavior -> startActivity(
                DiagnosticsBehaviorDetailActivity.createIntent(this, behavior)));
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
            if (item.getItemId() == R.id.action_save_full_diagnostics) {
                confirmFullExport();
                return true;
            }
            if (item.getItemId() == R.id.action_clear_diagnostics) {
                confirmClear();
                return true;
            }
            if (item.getItemId() == R.id.action_more_diagnostics) {
                View anchor = toolbar.findViewById(R.id.action_more_diagnostics);
                showOptionsPopup(anchor == null ? toolbar : anchor);
                return true;
            }
            return false;
        });
    }

    private void bindTabs(int selectedIndex) {
        int index = Math.max(0, Math.min(2, selectedIndex));
        tabs.check(buttonIdForTab(index));
        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked && viewModel != null && !clearInFlight) {
                viewModel.load(viewForTab(tabIndexForButton(checkedId)));
            }
        });
    }

    private int selectedTabIndex() {
        return tabIndexForButton(tabs.getCheckedButtonId());
    }

    private static int buttonIdForTab(int position) {
        if (position == 1) return R.id.diagnostics_tab_behaviors;
        if (position == 2) return R.id.diagnostics_tab_hooks;
        return R.id.diagnostics_tab_applications;
    }

    private static int tabIndexForButton(int buttonId) {
        if (buttonId == R.id.diagnostics_tab_behaviors) return 1;
        if (buttonId == R.id.diagnostics_tab_hooks) return 2;
        return 0;
    }

    private void showOptionsPopup(View anchor) {
        if (optionsPopup != null) optionsPopup.dismiss();
        FrameLayout popupParent = new FrameLayout(this);
        View content = LayoutInflater.from(this).inflate(
                R.layout.popup_diagnostics_options, popupParent, false);
        MaterialSwitch verbose = content.findViewById(
                R.id.diagnostics_popup_verbose_switch);
        verbose.setContentDescription(getString(
                R.string.setting_switch_accessibility_summary,
                getString(R.string.verbose_title),
                getString(R.string.verbose_summary)));
        verbose.setChecked(ModuleSettings.getLocalBoolean(
                this, ModuleSettings.KEY_VERBOSE_LOG));
        boolean[] bindingVerbose = { false };
        verbose.setOnCheckedChangeListener((button, checked) -> {
            if (bindingVerbose[0]) return;
            if (ModuleSettings.setBoolean(this, ModuleSettings.KEY_VERBOSE_LOG, checked)) {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            bindingVerbose[0] = true;
            button.setChecked(!checked);
            bindingVerbose[0] = false;
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show();
        });
        content.findViewById(R.id.diagnostics_popup_verbose_row).setOnClickListener(
                view -> verbose.toggle());
        content.findViewById(R.id.diagnostics_popup_save).setOnClickListener(view -> {
            dismissOptionsPopup();
            confirmFullExport();
        });
        content.findViewById(R.id.diagnostics_popup_clear).setOnClickListener(view -> {
            dismissOptionsPopup();
            confirmClear();
        });

        int width = Math.min(dp(300), getResources().getDisplayMetrics().widthPixels
                - dp(24));
        width = Math.max(1, width);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Rect visibleFrame = new Rect();
        anchor.getWindowVisibleDisplayFrame(visibleFrame);
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int availableBelow = visibleFrame.bottom
                - (anchorLocation[1] + anchor.getHeight());
        int availableAbove = anchorLocation[1] - visibleFrame.top;
        int availableHeight = Math.max(availableBelow, availableAbove);
        if (availableHeight <= 0) availableHeight = visibleFrame.height();
        if (availableHeight <= 0) {
            availableHeight = getResources().getDisplayMetrics().heightPixels;
        }
        int height = constrainedPopupHeight(
                content.getMeasuredHeight(), availableHeight, dp(12));
        PopupWindow popup = new PopupWindow(
                content, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        popup.setClippingEnabled(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            if (optionsPopup == popup) optionsPopup = null;
        });
        optionsPopup = popup;
        PopupWindowCompat.showAsDropDown(popup, anchor, 0, 0, Gravity.END);
    }

    private void dismissOptionsPopup() {
        if (optionsPopup != null) optionsPopup.dismiss();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static int constrainedPopupHeight(
            int desiredHeight, int visibleHeight, int verticalMargin) {
        int maximumHeight = Math.max(1, visibleHeight - Math.max(0, verticalMargin));
        return Math.max(1, Math.min(Math.max(1, desiredHeight), maximumHeight));
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
        boolean aggregateView = state.view == DiagnosticsViewModel.View.APPLICATION
                || state.view == DiagnosticsViewModel.View.BEHAVIOR;
        historyNotice.setVisibility(
                (content || empty) && aggregateView && state.historyTruncated
                        ? View.VISIBLE : View.GONE);

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
        if (exportInFlight || isFullExportBusy()) return;
        exportInFlight = true;
        DiagnosticsReportMetadata metadata =
                DiagnosticsReportMetadata.capture(this, false);
        Toast.makeText(this, R.string.diagnostics_export_preparing, Toast.LENGTH_SHORT).show();
        backend.exportAsync(
                        DiagnosticsQuery.ExportOptions.redactedDefaults(),
                        FeatureIds.all())
                .thenApply(snapshot -> {
                    if (snapshot == null || !snapshot.errorCode.isEmpty()) return null;
                    return DiagnosticsExportCodec.encode(snapshot, metadata);
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

    private void confirmFullExport() {
        if (exportInFlight || fullExportViewModel == null
                || fullExportViewModel.state().status
                != FullDiagnosticsExportViewModel.Status.IDLE) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diagnostics_full_export_confirm_title)
                .setMessage(R.string.diagnostics_full_export_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.diagnostics_full_export_choose_location,
                        (dialog, which) -> prepareFullExport())
                .show();
    }

    private void prepareFullExport() {
        DiagnosticsReportMetadata metadata = DiagnosticsReportMetadata.capture(this, true);
        if (!fullExportViewModel.prepare(metadata)) return;
        Toast.makeText(this, R.string.diagnostics_full_export_preparing,
                Toast.LENGTH_SHORT).show();
    }

    private void onFullExportDocumentSelected(Uri documentUri) {
        if (fullExportViewModel != null) {
            fullExportViewModel.onDestinationSelected(documentUri);
        }
    }

    private void renderFullExport(FullDiagnosticsExportViewModel.State state) {
        if (destroyed || !started || state == null) return;
        if (state.status == FullDiagnosticsExportViewModel.Status.READY) {
            FullDiagnosticsExportViewModel.DestinationRequest request =
                    fullExportViewModel.takeDestinationRequest();
            if (request == null) return;
            try {
                fullExportDocumentLauncher.launch(request.displayName);
            } catch (Throwable ignored) {
                fullExportViewModel.destinationLaunchFailed(request.operationId);
            }
            return;
        }
        if (state.status != FullDiagnosticsExportViewModel.Status.SUCCESS
                && state.status != FullDiagnosticsExportViewModel.Status.ERROR) return;

        FullDiagnosticsExportViewModel.Completion completion =
                fullExportViewModel.consumeCompletion(state.operationId);
        if (completion == null) return;
        if (completion.successful) {
            Toast.makeText(this, R.string.diagnostics_full_export_saved,
                    Toast.LENGTH_LONG).show();
        } else if (FullDiagnosticsExportViewModel.ERROR_INCOMPLETE_REPORT.equals(
                completion.errorCode)) {
            Toast.makeText(this, R.string.diagnostics_full_export_incomplete,
                    Toast.LENGTH_LONG).show();
        } else if (completion.destinationCreated
                && !completion.incompleteDocumentDeleted) {
            Toast.makeText(this, R.string.diagnostics_full_export_partial_remains,
                    Toast.LENGTH_LONG).show();
        } else if ("destination_name_changed".equals(completion.errorCode)) {
            Toast.makeText(this, R.string.diagnostics_full_export_name_changed,
                    Toast.LENGTH_LONG).show();
        } else if (completion.destinationCreated) {
            Toast.makeText(this, R.string.diagnostics_full_export_failed_cleaned,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.diagnostics_full_export_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isFullExportBusy() {
        return fullExportViewModel != null && fullExportViewModel.state().busy();
    }

    private void confirmClear() {
        if (clearInFlight || isFullExportBusy()) return;
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
