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
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.ConcatAdapter;
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
    private static final String STATE_QUERY = "query";

    private ResidencyAppsViewModel viewModel;
    private ResidencyAppsAdapter adapter;
    private ResidencyManagedHeaderAdapter headerAdapter;
    private ResidencyManagedStateAdapter stateAdapter;
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
    private String query = "";
    private boolean unifiedScroll;
    private ResidencyAppsViewModel.State currentState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            modeFilterIndex = clamp(savedInstanceState.getInt(STATE_MODE_FILTER, 0),
                    ResidencyAppFilter.ModeFilter.values().length);
            statusFilterIndex = clamp(savedInstanceState.getInt(STATE_STATUS_FILTER, 0), 3);
            query = savedInstanceState.getString(STATE_QUERY, "");
        }
        setContentView(R.layout.activity_residency_apps);
        UiUtils.applyWindowStyle(this);
        bindViews();
        viewModel = new ResidencyAppsViewModel(
                getApplicationContext(), ResidencyAppCatalog.Source.MANAGED);
        viewModel.setListener(this::render);
        bindInteractions();
        viewModel.setQuery(query);
        viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.values()[modeFilterIndex]);
        viewModel.setStatusFilter(statusFilter(statusFilterIndex));
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
        if (unifiedScroll && headerAdapter != null) {
            query = headerAdapter.query();
            modeFilterIndex = headerAdapter.modeFilterIndex();
            statusFilterIndex = headerAdapter.statusFilterIndex();
        } else if (search != null && search.getText() != null) {
            query = search.getText().toString();
        }
        outState.putString(STATE_QUERY, query);
        outState.putInt(STATE_MODE_FILTER, modeFilterIndex);
        outState.putInt(STATE_STATUS_FILTER, statusFilterIndex);
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        list = findViewById(R.id.residency_app_list);
        listBottomPadding = list.getPaddingBottom();
        unifiedScroll = findViewById(R.id.residency_list_surface) == null;
        adapter = new ResidencyAppsAdapter(this, this::openPolicy, unifiedScroll);
        list.setLayoutManager(new LinearLayoutManager(this));
        if (unifiedScroll) {
            bindUnifiedList();
            addFab = findViewById(R.id.residency_add_fab);
            return;
        }

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
        list.setAdapter(adapter);
    }

    private void bindUnifiedList() {
        headerAdapter = new ResidencyManagedHeaderAdapter(
                query,
                modeFilterIndex,
                statusFilterIndex,
                new ResidencyManagedHeaderAdapter.Listener() {
                    @Override
                    public void onQueryChanged(String replacement) {
                        query = replacement;
                        viewModel.setQuery(replacement);
                    }

                    @Override
                    public void onModeFilterChanged(int position) {
                        modeFilterIndex = position;
                        viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.values()[position]);
                    }

                    @Override
                    public void onStatusFilterChanged(int position) {
                        statusFilterIndex = position;
                        viewModel.setStatusFilter(statusFilter(position));
                    }
                });
        stateAdapter = new ResidencyManagedStateAdapter(
                new ResidencyManagedStateAdapter.Listener() {
                    @Override
                    public void onRetry() {
                        viewModel.load();
                    }

                    @Override
                    public void onEmptyAction(boolean activeFilters) {
                        if (activeFilters) clearFilters();
                        else openPicker();
                    }
                });
        list.setAdapter(new ConcatAdapter(headerAdapter, adapter, stateAdapter));
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
        if (!unifiedScroll) bindSplitLayoutFilters();

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && currentState != null && currentState.hasMore()
                        && !recyclerView.canScrollVertically(1)) {
                    viewModel.loadNextPage();
                }
            }
        });
        NestedScrollView pageScroll = findViewById(R.id.residency_page_scroll);
        if (pageScroll != null && !list.isNestedScrollingEnabled()) {
            pageScroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                    (scrollView, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                        if (scrollY > oldScrollY && currentState != null
                                && currentState.hasMore()
                                && !scrollView.canScrollVertically(1)) {
                            viewModel.loadNextPage();
                        }
                    });
        }
    }

    private void bindSplitLayoutFilters() {
        findViewById(R.id.residency_retry).setOnClickListener(view -> viewModel.load());
        findViewById(R.id.residency_empty_action).setOnClickListener(view -> {
            if (hasActiveFilters()) clearFilters();
            else openPicker();
        });

        search.setText(query);
        search.setSelection(search.length());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence text, int start, int count, int after) {}

            @Override public void onTextChanged(
                    CharSequence text, int start, int before, int count) {
                query = text == null ? "" : text.toString();
                viewModel.setQuery(query);
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
            viewModel.setStatusFilter(statusFilter(position));
        });
    }

    private void render(ResidencyAppsViewModel.State state) {
        currentState = state;
        boolean loading = state.status() == ResidencyAppsViewModel.Status.LOADING;
        boolean content = state.status() == ResidencyAppsViewModel.Status.CONTENT;
        boolean empty = state.status() == ResidencyAppsViewModel.Status.EMPTY;
        boolean error = state.status() == ResidencyAppsViewModel.Status.ERROR;
        if (unifiedScroll) {
            boolean activeFilters = hasActiveFilters();
            headerAdapter.setPresentation(
                    content || empty && activeFilters,
                    content ? getString(R.string.residency_count, state.filteredCount()) : null);
            stateAdapter.setState(state.status(), activeFilters);
            adapter.submitList(content
                    ? state.items() : java.util.Collections.emptyList());
            addFab.setVisibility(content ? View.VISIBLE : View.GONE);
            return;
        }
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
        if (unifiedScroll) return;
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
        if (unifiedScroll) return;
        if (!list.isNestedScrollingEnabled()) {
            applyWholePageListSurfaceMode(content);
            return;
        }
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

    private void applyWholePageListSurfaceMode(boolean content) {
        ViewGroup.LayoutParams surfaceParams = listSurface.getLayoutParams();
        if (surfaceParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linearParams = (LinearLayout.LayoutParams) surfaceParams;
            linearParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            linearParams.weight = 0f;
            listSurface.setLayoutParams(linearParams);
        }
        View surfaceContent = listSurface.getChildAt(0);
        if (surfaceContent != null) {
            ViewGroup.LayoutParams contentParams = surfaceContent.getLayoutParams();
            contentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            surfaceContent.setLayoutParams(contentParams);
        }
        ViewGroup.LayoutParams listParams = list.getLayoutParams();
        listParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        list.setLayoutParams(listParams);
        list.setNestedScrollingEnabled(false);
        list.setPadding(
                list.getPaddingLeft(),
                list.getPaddingTop(),
                list.getPaddingRight(),
                listBottomPadding);
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
        if (unifiedScroll) return headerAdapter != null && headerAdapter.hasActiveFilters();
        return !query.isEmpty()
                || modeFilterIndex != 0
                || statusFilterIndex != 0;
    }

    private void clearFilters() {
        if (unifiedScroll) {
            headerAdapter.resetFilters();
            return;
        }
        search.setText("");
        query = "";
        modeFilterIndex = 0;
        statusFilterIndex = 0;
        String[] modes = getResources().getStringArray(R.array.residency_mode_filters);
        String[] statuses = getResources().getStringArray(R.array.residency_status_filters);
        modeFilterView.setText(modes[0], false);
        statusFilterView.setText(statuses[0], false);
        viewModel.setModeFilter(ResidencyAppFilter.ModeFilter.ALL);
        viewModel.setStatusFilter(ResidencyAppFilter.StatusFilter.ALL);
    }

    private static ResidencyAppFilter.StatusFilter statusFilter(int position) {
        if (position == 1) return ResidencyAppFilter.StatusFilter.ENABLED;
        if (position == 2) return ResidencyAppFilter.StatusFilter.DISABLED;
        return ResidencyAppFilter.StatusFilter.ALL;
    }

    private static int clamp(int value, int size) {
        return value < 0 || value >= size ? 0 : value;
    }

    private void openPicker() {
        startActivity(new Intent(this, ResidencyAppPickerActivity.class));
    }

    private void openPolicy(ResidencyAppItem item) {
        Intent intent = new Intent(this, ResidencyPolicyActivity.class);
        intent.putExtra(ResidencyPolicyActivity.EXTRA_PACKAGE_NAME, item.packageName());
        intent.putExtra(ResidencyPolicyActivity.EXTRA_USER_ID, item.userId());
        startActivity(intent);
    }
}
