package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.snackbar.Snackbar;

/** Lets users add ordinary applications and optionally inspect system applications. */
public final class ResidencyAppPickerActivity extends AppCompatActivity {
    private static final String STATE_QUERY = "query";
    private static final String STATE_SHOW_SYSTEM = "show_system";

    private final ActivityResultLauncher<Intent> policyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setResult(RESULT_OK);
                    finish();
                }
            });
    private ResidencyAppsViewModel viewModel;
    private ResidencyAppsAdapter appsAdapter;
    private ResidencyAppPickerHeaderAdapter headerAdapter;
    private ResidencyAppPickerStateAdapter stateAdapter;
    private RecyclerView list;
    private ResidencyAppsViewModel.State currentState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String query = savedInstanceState == null
                ? "" : savedInstanceState.getString(STATE_QUERY, "");
        boolean showSystem = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_SHOW_SYSTEM);
        setContentView(R.layout.activity_residency_app_picker);
        UiUtils.applyWindowStyle(this);

        viewModel = new ResidencyAppsViewModel(
                getApplicationContext(), ResidencyAppCatalog.Source.PICKER);
        bindViews(query, showSystem);
        bindInteractions();
        viewModel.setListener(this::render);
        viewModel.setQuery(query);
        viewModel.setShowSystemApps(showSystem);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    @Override
    protected void onDestroy() {
        if (viewModel != null) viewModel.close();
        if (appsAdapter != null) appsAdapter.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (headerAdapter != null) {
            outState.putString(STATE_QUERY, headerAdapter.query());
            outState.putBoolean(STATE_SHOW_SYSTEM, headerAdapter.showSystemApps());
        }
        super.onSaveInstanceState(outState);
    }

    private void bindViews(String query, boolean showSystem) {
        list = findViewById(R.id.residency_app_list);
        headerAdapter = new ResidencyAppPickerHeaderAdapter(
                query, showSystem, new ResidencyAppPickerHeaderAdapter.Listener() {
                    @Override
                    public void onQueryChanged(String replacement) {
                        viewModel.setQuery(replacement);
                    }

                    @Override
                    public void onShowSystemAppsChanged(boolean replacement) {
                        viewModel.setShowSystemApps(replacement);
                    }
                });
        appsAdapter = new ResidencyAppsAdapter(this, this::onAppSelected, true);
        stateAdapter = new ResidencyAppPickerStateAdapter(
                new ResidencyAppPickerStateAdapter.Listener() {
                    @Override
                    public void onRetry() {
                        viewModel.load();
                    }

                    @Override
                    public void onClearFilters() {
                        headerAdapter.resetFilters();
                    }
                });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new ConcatAdapter(headerAdapter, appsAdapter, stateAdapter));
    }

    private void bindInteractions() {
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(view -> finish());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && currentState != null && currentState.hasMore()
                        && !recyclerView.canScrollVertically(1)) {
                    viewModel.loadNextPage();
                }
            }
        });
    }

    private void render(ResidencyAppsViewModel.State state) {
        currentState = state;
        boolean content = state.status() == ResidencyAppsViewModel.Status.CONTENT;
        headerAdapter.setResultCount(content
                ? getString(R.string.residency_count, state.filteredCount()) : null);
        stateAdapter.setStatus(state.status());
        appsAdapter.submitList(content
                ? state.items() : java.util.Collections.emptyList());
    }

    private void onAppSelected(ResidencyAppItem item) {
        if (item.safety().isRestricted()) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    ResidencyUiText.restriction(item.safety().restriction()),
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, ResidencyPolicyActivity.class);
        intent.putExtra(ResidencyPolicyActivity.EXTRA_PACKAGE_NAME, item.packageName());
        policyLauncher.launch(intent);
    }
}
