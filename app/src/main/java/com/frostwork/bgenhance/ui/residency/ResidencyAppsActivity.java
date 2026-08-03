package com.frostwork.bgenhance.ui.residency;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.ui.UiUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
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
    private View filters;
    private View addFab;
    private MaterialCardView listSurface;
    private TextView resultCount;
    private View loadingState;
    private View emptyState;
    private View errorState;
    private int listBottomPadding;
    private int listSurfaceGeneration;
    private boolean listContentMode;
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
        listBottomPadding = list.getPaddingBottom();
        search = findViewById(R.id.residency_search);
        modeFilterView = findViewById(R.id.residency_mode_filter);
        statusFilterView = findViewById(R.id.residency_status_filter);
        float fontScale = getResources().getConfiguration().fontScale;
        ResidencyFilterRows.stackForLargeText(
                findViewById(android.R.id.content),
                fontScale,
                R.id.residency_mode_filter_row,
                R.id.residency_mode_filter_label,
                R.id.residency_mode_filter);
        ResidencyFilterRows.stackForLargeText(
                findViewById(android.R.id.content),
                fontScale,
                R.id.residency_status_filter_row,
                R.id.residency_status_filter_label,
                R.id.residency_status_filter);
        filters = findViewById(R.id.residency_filters);
        addFab = findViewById(R.id.residency_add_fab);
        listSurface = findViewById(R.id.residency_list_surface);
        resultCount = findViewById(R.id.residency_result_count);
        loadingState = findViewById(R.id.residency_loading_state);
        emptyState = findViewById(R.id.residency_empty_state);
        errorState = findViewById(R.id.residency_error_state);
        adapter = new ResidencyAppsAdapter(this, this::openPolicy);
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
        addFab.setOnClickListener(view -> openPicker());
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
        ResidencyDropdowns.setItems(modeFilterView, modes);
        modeFilterView.setText(modes[modeFilterIndex], false);
        modeFilterView.setOnItemClickListener((parent, view, position, id) -> {
            modeFilterIndex = position;
            viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.values()[position]);
        });

        String[] statuses = getResources().getStringArray(R.array.residency_status_filters);
        ResidencyDropdowns.setItems(statusFilterView, statuses);
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
        setListSurfaceMode(content);
        listSurface.setCardBackgroundColor(getColor(
                content ? R.color.card_bg : R.color.page_bg));
        loadingState.setVisibility(loading ? View.VISIBLE : View.GONE);
        list.setVisibility(content ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        errorState.setVisibility(error ? View.VISIBLE : View.GONE);
        boolean showFilters = content || empty && hasActiveFilters();
        filters.setVisibility(showFilters ? View.VISIBLE : View.GONE);
        addFab.setVisibility(content ? View.VISIBLE : View.GONE);
        resultCount.setVisibility(content ? View.VISIBLE : View.GONE);
        if (content) {
            adapter.submitList(state.items());
            resultCount.setText(getString(R.string.residency_count, state.filteredCount()));
        } else if (!loading) {
            adapter.submitList(java.util.Collections.emptyList());
        }
        if (empty) renderEmptyState();
    }

    /** Keeps short lists grouped, then bounds the surface when its content actually overflows. */
    void setListSurfaceMode(boolean content) {
        int generation = ++listSurfaceGeneration;
        listContentMode = content;
        boolean bounded = content && shouldStartBoundedList(
                getResources().getConfiguration().fontScale);
        applyListSurfaceMode(content, bounded);
        if (!content || bounded) return;

        list.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View view,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom) {
                if (generation != listSurfaceGeneration || !listContentMode) {
                    list.removeOnLayoutChangeListener(this);
                    return;
                }
                if (!listHasVisibleItems()) return;
                list.removeOnLayoutChangeListener(this);
                if (listContentOverflows()) {
                    applyListSurfaceMode(true, true);
                }
            }
        });
        list.requestLayout();
    }

    static boolean shouldStartBoundedList(float fontScale) {
        return fontScale > 1f;
    }

    private boolean listContentOverflows() {
        RecyclerView.Adapter<?> currentAdapter = list.getAdapter();
        if (currentAdapter == null || currentAdapter.getItemCount() == 0) return false;
        LinearLayoutManager layoutManager = (LinearLayoutManager) list.getLayoutManager();
        if (layoutManager == null) return true;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        View firstView = layoutManager.findViewByPosition(first);
        View lastView = layoutManager.findViewByPosition(last);
        int edgeTolerance = Math.max(1, (int) Math.ceil(
                2f * getResources().getDisplayMetrics().density));
        return first != 0
                || last != currentAdapter.getItemCount() - 1
                || firstView == null
                || lastView == null
                || layoutManager.getDecoratedTop(firstView) < -edgeTolerance
                || layoutManager.getDecoratedBottom(lastView)
                        > list.getHeight() + edgeTolerance;
    }

    private boolean listHasVisibleItems() {
        LinearLayoutManager layoutManager = (LinearLayoutManager) list.getLayoutManager();
        return layoutManager != null
                && layoutManager.findFirstVisibleItemPosition() != RecyclerView.NO_POSITION;
    }

    void applyListSurfaceMode(boolean content, boolean bounded) {
        ViewGroup.LayoutParams surfaceParams = listSurface.getLayoutParams();
        if (surfaceParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linearParams = (LinearLayout.LayoutParams) surfaceParams;
            boolean compact = content && !bounded;
            linearParams.height = compact ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            linearParams.weight = compact ? 0f : 1f;
            listSurface.setLayoutParams(linearParams);
        }

        View surfaceContent = listSurface.getChildAt(0);
        if (surfaceContent != null) {
            ViewGroup.LayoutParams contentParams = surfaceContent.getLayoutParams();
            contentParams.height = content && !bounded
                    ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            surfaceContent.setLayoutParams(contentParams);
        }

        ViewGroup.LayoutParams listParams = list.getLayoutParams();
        listParams.height = content && !bounded
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ViewGroup.LayoutParams.MATCH_PARENT;
        list.setLayoutParams(listParams);
        list.setPadding(
                list.getPaddingLeft(),
                list.getPaddingTop(),
                list.getPaddingRight(),
                content && !bounded
                        ? getResources().getDimensionPixelSize(R.dimen.card_spacing)
                        : listBottomPadding);
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
