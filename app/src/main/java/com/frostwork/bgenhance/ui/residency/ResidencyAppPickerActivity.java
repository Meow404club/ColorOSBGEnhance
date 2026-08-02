package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

/** Lets users add ordinary applications and optionally inspect configurable system apps. */
public final class ResidencyAppPickerActivity extends AppCompatActivity {
    private static final String STATE_AVAILABILITY_FILTER = "availability_filter";

    private final ActivityResultLauncher<Intent> policyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setResult(RESULT_OK);
                    finish();
                }
            });
    private ResidencyAppsViewModel viewModel;
    private ResidencyAppsAdapter adapter;
    private RecyclerView list;
    private TextInputEditText search;
    private MaterialSwitch showSystem;
    private MaterialAutoCompleteTextView availabilityFilterView;
    private TextView resultCount;
    private View loadingState;
    private View emptyState;
    private View errorState;
    private int availabilityFilterIndex;
    private ResidencyAppsViewModel.State currentState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            availabilityFilterIndex = savedInstanceState.getInt(
                    STATE_AVAILABILITY_FILTER, 0);
        }
        setContentView(R.layout.activity_residency_app_picker);
        UiUtils.applyWindowStyle(this);
        bindViews();
        viewModel = new ResidencyAppsViewModel(
                getApplicationContext(), ResidencyAppCatalog.Source.PICKER);
        viewModel.setListener(this::render);
        bindInteractions();
        viewModel.setStatusFilter(availabilityFilterIndex == 1
                ? ResidencyAppFilter.StatusFilter.CONFIGURABLE
                : availabilityFilterIndex == 2
                        ? ResidencyAppFilter.StatusFilter.RESTRICTED
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
        outState.putInt(STATE_AVAILABILITY_FILTER, availabilityFilterIndex);
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        list = findViewById(R.id.residency_app_list);
        search = findViewById(R.id.residency_search);
        showSystem = findViewById(R.id.residency_show_system);
        availabilityFilterView = findViewById(R.id.residency_availability_filter);
        resultCount = findViewById(R.id.residency_result_count);
        loadingState = findViewById(R.id.residency_loading_state);
        emptyState = findViewById(R.id.residency_empty_state);
        errorState = findViewById(R.id.residency_error_state);
        adapter = new ResidencyAppsAdapter(this, true, this::onAppSelected);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    private void bindInteractions() {
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(view -> finish());
        findViewById(R.id.residency_retry).setOnClickListener(view -> viewModel.load());
        findViewById(R.id.residency_empty_action).setOnClickListener(view -> clearFilters());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (viewModel != null) viewModel.setQuery(text == null ? "" : text.toString());
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        showSystem.setOnCheckedChangeListener((button, checked) -> {
            if (viewModel != null) viewModel.setShowSystemApps(checked);
        });

        String[] availability = getResources().getStringArray(
                R.array.residency_availability_filters);
        availabilityFilterView.setSimpleItems(availability);
        availabilityFilterView.setText(availability[availabilityFilterIndex], false);
        availabilityFilterView.setOnItemClickListener((parent, view, position, id) -> {
            availabilityFilterIndex = position;
            ResidencyAppFilter.StatusFilter filter = position == 1
                    ? ResidencyAppFilter.StatusFilter.CONFIGURABLE
                    : position == 2
                            ? ResidencyAppFilter.StatusFilter.RESTRICTED
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
    }

    private void clearFilters() {
        search.setText("");
        showSystem.setChecked(false);
        availabilityFilterIndex = 0;
        String[] availability = getResources().getStringArray(
                R.array.residency_availability_filters);
        availabilityFilterView.setText(availability[0], false);
        viewModel.setStatusFilter(ResidencyAppFilter.StatusFilter.ALL);
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
