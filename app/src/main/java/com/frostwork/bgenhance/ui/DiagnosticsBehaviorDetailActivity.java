package com.frostwork.bgenhance.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.ModuleApplication;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.ResidencyDiagnosticsBackend;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsBehavior;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsBehaviorDetailViewModel;
import com.google.android.material.appbar.MaterialToolbar;

/** Material drill-down for the concrete records belonging to one behavior family. */
public final class DiagnosticsBehaviorDetailActivity extends AppCompatActivity {
    private static final String EXTRA_BEHAVIOR =
            "com.frostwork.bgenhance.extra.DIAGNOSTICS_BEHAVIOR";

    private DiagnosticsBehavior behavior;
    private DiagnosticsBehaviorDetailViewModel viewModel;
    private DiagnosticsBehaviorDetailRowsAdapter adapter;
    private RecyclerView list;
    private TextView summary;
    private TextView historyNotice;
    private View loadingState;
    private View errorState;
    private boolean destroyed;

    private final ModuleApplication.StateListener moduleStateListener = () ->
            runOnUiThread(() -> {
                if (!destroyed && viewModel != null) viewModel.refresh();
            });

    public static Intent createIntent(Context context, DiagnosticsBehavior behavior) {
        return new Intent(context, DiagnosticsBehaviorDetailActivity.class)
                .putExtra(EXTRA_BEHAVIOR, behavior.wireId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        behavior = readBehavior(getIntent());
        if (behavior == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_diagnostics_behavior_detail);
        UiUtils.applyWindowStyle(this);
        bindViews();

        ResidencyDiagnosticsBackend backend = ModuleApplication.initializeDiagnostics(this);
        viewModel = DiagnosticsBehaviorDetailViewModel.create(this, backend, behavior);
        viewModel.setListener(this::render);
        viewModel.load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.addStateListener(moduleStateListener);
    }

    @Override
    protected void onStop() {
        ModuleApplication.removeStateListener(moduleStateListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (viewModel != null) viewModel.close();
        super.onDestroy();
    }

    private void bindViews() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());

        TextView pageTitle = findViewById(R.id.diagnostics_detail_title);
        pageTitle.setText(behaviorTitle(behavior));
        toolbar.setTitle(pageTitle.getVisibility() == View.VISIBLE
                ? "" : getString(behaviorTitle(behavior)));
        summary = findViewById(R.id.diagnostics_detail_summary);
        historyNotice = findViewById(R.id.diagnostics_detail_history_notice);
        loadingState = findViewById(R.id.diagnostics_detail_loading_state);
        errorState = findViewById(R.id.diagnostics_detail_error_state);
        list = findViewById(R.id.diagnostics_detail_list);
        adapter = new DiagnosticsBehaviorDetailRowsAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        findViewById(R.id.diagnostics_detail_retry).setOnClickListener(
                view -> viewModel.refresh());
    }

    private void render(DiagnosticsBehaviorDetailViewModel.State state) {
        if (destroyed) return;
        boolean loading = state.status == DiagnosticsBehaviorDetailViewModel.Status.IDLE
                || state.status == DiagnosticsBehaviorDetailViewModel.Status.LOADING;
        boolean content = state.status == DiagnosticsBehaviorDetailViewModel.Status.CONTENT;
        boolean error = state.status == DiagnosticsBehaviorDetailViewModel.Status.ERROR;
        loadingState.setVisibility(loading ? View.VISIBLE : View.GONE);
        list.setVisibility(content ? View.VISIBLE : View.GONE);
        errorState.setVisibility(error ? View.VISIBLE : View.GONE);
        summary.setVisibility(content ? View.VISIBLE : View.INVISIBLE);
        historyNotice.setVisibility(
                content && state.historyTruncated ? View.VISIBLE : View.GONE);
        if (content) {
            adapter.submitList(state.items);
            summary.setText(getString(
                    R.string.diagnostics_detail_summary,
                    state.items.size(),
                    state.totals.observed,
                    state.totals.blocked,
                    state.totals.failOpen));
        } else if (!loading) {
            adapter.submitList(java.util.Collections.emptyList());
        }
    }

    private static DiagnosticsBehavior readBehavior(Intent intent) {
        String wireId = intent == null ? null : intent.getStringExtra(EXTRA_BEHAVIOR);
        if (wireId == null || wireId.isEmpty()) return null;
        for (DiagnosticsBehavior value : DiagnosticsBehavior.all()) {
            if (value.wireId.equals(wireId)) return value;
        }
        return null;
    }

    private static int behaviorTitle(DiagnosticsBehavior behavior) {
        switch (behavior) {
            case KILL: return R.string.diagnostics_behavior_kill;
            case FREEZE: return R.string.diagnostics_behavior_freeze;
            case WAKE: return R.string.diagnostics_behavior_wake;
            case RESOURCE: return R.string.diagnostics_behavior_resource;
            case REFREEZE: return R.string.diagnostics_behavior_refreeze;
            case RECOVERY: return R.string.diagnostics_behavior_recovery;
            case UNCLASSIFIED: return R.string.diagnostics_behavior_unclassified;
            default: throw new AssertionError(behavior);
        }
    }
}
