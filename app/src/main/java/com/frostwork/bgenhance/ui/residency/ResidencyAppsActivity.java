package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

/** Lists applications with an existing residency rule. */
public final class ResidencyAppsActivity extends AppCompatActivity {
    private static final String STATE_MODE_FILTER = "mode_filter";
    private static final String STATE_STATUS_FILTER = "status_filter";

    private ResidencyAppsViewModel viewModel;
    private ResidencyAppsAdapter adapter;
    private RecyclerView list;
    private TextInputEditText search;
    private MaterialAutoCompleteTextView modeFilterView;
    private MaterialAutoCompleteTextView statusFilterView;
    private TextView resultCount;
    private View loadingState;
    private View emptyState;
    private View errorState;
    private int modeFilterIndex;
    private int statusFilterIndex;
    private ResidencyAppsViewModel.State currentState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            modeFilterIndex = savedInstanceState.getInt(STATE_MODE_FILTER, 0);
            statusFilterIndex = savedInstanceState.getInt(STATE_STATUS_FILTER, 0);
        }
        setContentView(R.layout.activity_residency_apps);
        UiUtils.applyWindowStyle(this);
        bindViews();
        viewModel = new ResidencyAppsViewModel(
                getApplicationContext(), ResidencyAppCatalog.Source.MANAGED);
        viewModel.setListener(this::render);
        bindInteractions();
        viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.values()[modeFilterIndex]);
        viewModel.setStatusFilter(statusFilterIndex == 1
                ? ResidencyAppFilter.StatusFilter.ENABLED
                : statusFilterIndex == 2
                        ? ResidencyAppFilter.StatusFilter.DISABLED
                        : ResidencyAppFilter.StatusFilter.ALL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    @Override
    protected void onDestroy() {
        if (viewModel != null) viewModel.close();
        if (adapter != null) adapter.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_MODE_FILTER, modeFilterIndex);
        outState.putInt(STATE_STATUS_FILTER, statusFilterIndex);
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        list = findViewById(R.id.residency_app_list);
        search = findViewById(R.id.residency_search);
        modeFilterView = findViewById(R.id.residency_mode_filter);
        statusFilterView = findViewById(R.id.residency_status_filter);
        resultCount = findViewById(R.id.residency_result_count);
        loadingState = findViewById(R.id.residency_loading_state);
        emptyState = findViewById(R.id.residency_empty_state);
        errorState = findViewById(R.id.residency_error_state);
        adapter = new ResidencyAppsAdapter(this, false, this::openPolicy);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    private void bindInteractions() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        toolbar.inflateMenu(R.menu.menu_residency_apps);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.action_import_residency) return false;
            startActivity(new Intent(this, ResidencyImportActivity.class));
            return true;
        });
        findViewById(R.id.residency_add_fab).setOnClickListener(view -> openPicker());
        findViewById(R.id.residency_retry).setOnClickListener(view -> viewModel.load());
        findViewById(R.id.residency_empty_action).setOnClickListener(view -> {
            if (hasActiveFilters()) clearFilters();
            else openPicker();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (viewModel != null) viewModel.setQuery(text == null ? "" : text.toString());
            }
            @Override public void afterTextChanged(Editable editable) {}
        });

        String[] modes = getResources().getStringArray(R.array.residency_mode_filters);
        modeFilterView.setSimpleItems(modes);
        modeFilterView.setText(modes[modeFilterIndex], false);
        modeFilterView.setOnItemClickListener((parent, view, position, id) -> {
            modeFilterIndex = position;
            viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.values()[position]);
        });

        String[] statuses = getResources().getStringArray(R.array.residency_status_filters);
        statusFilterView.setSimpleItems(statuses);
        statusFilterView.setText(statuses[statusFilterIndex], false);
        statusFilterView.setOnItemClickListener((parent, view, position, id) -> {
            statusFilterIndex = position;
            ResidencyAppFilter.StatusFilter filter = position == 1
                    ? ResidencyAppFilter.StatusFilter.ENABLED
                    : position == 2
                            ? ResidencyAppFilter.StatusFilter.DISABLED
                            : ResidencyAppFilter.StatusFilter.ALL;
            viewModel.setStatusFilter(filter);
        });

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
        boolean loading = state.status() == ResidencyAppsViewModel.Status.LOADING;
        boolean content = state.status() == ResidencyAppsViewModel.Status.CONTENT;
        boolean empty = state.status() == ResidencyAppsViewModel.Status.EMPTY;
        boolean error = state.status() == ResidencyAppsViewModel.Status.ERROR;
        loadingState.setVisibility(loading ? View.VISIBLE : View.GONE);
        list.setVisibility(content ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        errorState.setVisibility(error ? View.VISIBLE : View.GONE);
        resultCount.setVisibility(content ? View.VISIBLE : View.INVISIBLE);
        if (content) {
            adapter.submitList(state.items());
            resultCount.setText(getString(R.string.residency_count, state.filteredCount()));
        } else if (!loading) {
            adapter.submitList(java.util.Collections.emptyList());
        }
        if (empty) renderEmptyState();
    }

    private void renderEmptyState() {
        TextView title = findViewById(R.id.residency_empty_title);
        TextView summary = findViewById(R.id.residency_empty_summary);
        TextView action = findViewById(R.id.residency_empty_action);
        if (hasActiveFilters()) {
            title.setText(R.string.residency_filter_empty_title);
            summary.setText(R.string.residency_filter_empty_summary);
            action.setText(R.string.residency_clear_filters);
        } else {
            title.setText(R.string.residency_empty_title);
            summary.setText(R.string.residency_empty_summary);
            action.setText(R.string.residency_add_app);
        }
    }

    private boolean hasActiveFilters() {
        return search.getText() != null && search.getText().length() > 0
                || modeFilterIndex != 0
                || statusFilterIndex != 0;
    }

    private void clearFilters() {
        search.setText("");
        modeFilterIndex = 0;
        statusFilterIndex = 0;
        String[] modes = getResources().getStringArray(R.array.residency_mode_filters);
        String[] statuses = getResources().getStringArray(R.array.residency_status_filters);
        modeFilterView.setText(modes[0], false);
        statusFilterView.setText(statuses[0], false);
        viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.ALL);
        viewModel.setStatusFilter(ResidencyAppFilter.StatusFilter.ALL);
    }

    private void openPicker() {
        startActivity(new Intent(this, ResidencyAppPickerActivity.class));
    }

    private void openPolicy(ResidencyAppItem item) {
        Intent intent = new Intent(this, ResidencyPolicyActivity.class);
        intent.putExtra(ResidencyPolicyActivity.EXTRA_PACKAGE_NAME, item.packageName());
        startActivity(intent);
    }
}
