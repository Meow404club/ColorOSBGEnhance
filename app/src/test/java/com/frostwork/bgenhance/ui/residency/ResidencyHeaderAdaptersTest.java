package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.content.Context;
import android.text.Editable;
import android.text.Spanned;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyHeaderAdaptersTest {
    @Test
    public void managedCountPayloadPreservesSearchCompositionAndSelection() {
        ResidencyManagedHeaderAdapter adapter = new ResidencyManagedHeaderAdapter(
                "中文搜索", 0, 0, new ManagedListener());
        adapter.setPresentation(true, null);
        ResidencyManagedHeaderAdapter.HeaderViewHolder holder =
                adapter.onCreateViewHolder(parent(), 0);
        adapter.onBindViewHolder(holder, 0);

        TextInputEditText search = holder.itemView.findViewById(R.id.residency_search);
        TextView count = holder.itemView.findViewById(R.id.residency_result_count);
        assertEquals(View.GONE, count.getVisibility());
        CompositionState composition = markComposition(search);
        int liveRegion = count.getAccessibilityLiveRegion();
        ChangeObserver observer = new ChangeObserver();
        adapter.registerAdapterDataObserver(observer);
        try {
            adapter.setPresentation(true, "共 2 项");

            assertPayloadChange(observer);
            adapter.onBindViewHolder(
                    holder, 0, Collections.singletonList(observer.payload));

            assertEquals("共 2 项", count.getText().toString());
            assertEquals(View.VISIBLE, count.getVisibility());
            assertCompositionPreserved(search, composition);
            assertEquals(liveRegion, count.getAccessibilityLiveRegion());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, liveRegion);
        } finally {
            adapter.unregisterAdapterDataObserver(observer);
        }
    }

    @Test
    public void managedFilterVisibilityChangeStillRequestsFullBind() {
        ResidencyManagedHeaderAdapter adapter = new ResidencyManagedHeaderAdapter(
                "", 0, 0, new ManagedListener());
        ChangeObserver observer = new ChangeObserver();
        adapter.registerAdapterDataObserver(observer);
        try {
            adapter.setPresentation(true, null);

            assertEquals(1, observer.callCount);
            assertEquals(0, observer.positionStart);
            assertEquals(1, observer.itemCount);
            assertNull(observer.payload);
        } finally {
            adapter.unregisterAdapterDataObserver(observer);
        }
    }

    @Test
    public void pickerCountPayloadPreservesSearchCompositionAndSelection() {
        ResidencyAppPickerHeaderAdapter adapter = new ResidencyAppPickerHeaderAdapter(
                "中文搜索", false, new PickerListener());
        ResidencyAppPickerHeaderAdapter.HeaderViewHolder holder =
                adapter.onCreateViewHolder(parent(), 0);
        adapter.onBindViewHolder(holder, 0);

        TextInputEditText search = holder.itemView.findViewById(R.id.residency_search);
        TextView count = holder.itemView.findViewById(R.id.residency_result_count);
        assertEquals(View.GONE, count.getVisibility());
        CompositionState composition = markComposition(search);
        int liveRegion = count.getAccessibilityLiveRegion();
        ChangeObserver observer = new ChangeObserver();
        adapter.registerAdapterDataObserver(observer);
        try {
            adapter.setResultCount("共 2 项");

            assertPayloadChange(observer);
            adapter.onBindViewHolder(
                    holder, 0, Collections.singletonList(observer.payload));

            assertEquals("共 2 项", count.getText().toString());
            assertEquals(View.VISIBLE, count.getVisibility());
            assertCompositionPreserved(search, composition);
            assertEquals(liveRegion, count.getAccessibilityLiveRegion());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, liveRegion);
        } finally {
            adapter.unregisterAdapterDataObserver(observer);
        }
    }

    private static FrameLayout parent() {
        Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme);
        return new FrameLayout(context);
    }

    private static CompositionState markComposition(TextInputEditText search) {
        Editable editable = search.getText();
        assertNotNull(editable);
        Object marker = new Object();
        editable.setSpan(
                marker, 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
        search.setSelection(2);
        return new CompositionState(editable, marker);
    }

    private static void assertCompositionPreserved(
            TextInputEditText search, CompositionState composition) {
        assertEquals("中文搜索", search.getText().toString());
        assertSame(composition.editable, search.getText());
        assertEquals(2, search.getSelectionStart());
        assertEquals(2, search.getSelectionEnd());
        assertEquals(1, composition.editable.getSpanStart(composition.marker));
        assertEquals(3, composition.editable.getSpanEnd(composition.marker));
        assertEquals(Spanned.SPAN_COMPOSING,
                composition.editable.getSpanFlags(composition.marker) & Spanned.SPAN_COMPOSING);
    }

    private static void assertPayloadChange(ChangeObserver observer) {
        assertEquals(1, observer.callCount);
        assertEquals(0, observer.positionStart);
        assertEquals(1, observer.itemCount);
        assertNotNull(observer.payload);
    }

    private static final class CompositionState {
        private final Editable editable;
        private final Object marker;

        CompositionState(Editable editable, Object marker) {
            this.editable = editable;
            this.marker = marker;
        }
    }

    private static final class ChangeObserver extends RecyclerView.AdapterDataObserver {
        private int callCount;
        private int positionStart = -1;
        private int itemCount;
        @Nullable private Object payload;

        @Override
        public void onItemRangeChanged(
                int positionStart, int itemCount, @Nullable Object payload) {
            callCount++;
            this.positionStart = positionStart;
            this.itemCount = itemCount;
            this.payload = payload;
        }
    }

    private static final class ManagedListener implements ResidencyManagedHeaderAdapter.Listener {
        @Override
        public void onQueryChanged(String query) {}

        @Override
        public void onModeFilterChanged(int position) {}

        @Override
        public void onStatusFilterChanged(int position) {}
    }

    private static final class PickerListener implements ResidencyAppPickerHeaderAdapter.Listener {
        @Override
        public void onQueryChanged(String query) {}

        @Override
        public void onShowSystemAppsChanged(boolean showSystemApps) {}
    }
}
