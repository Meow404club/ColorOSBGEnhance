package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.TextView;
import com.frostwork.bgenhance.R;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyDropdownAdapterTest {
    @Test
    public void optionsExposeCurrentValueAsSelectedAndClearRecycledState() {
        ContextThemeWrapper context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme);
        MaterialAutoCompleteTextView field = new MaterialAutoCompleteTextView(context);
        String[] items = {"全部应用", "可配置", "不可配置"};
        field.setText(items[0], false);
        ResidencyDropdowns.setItems(field, items);

        ListAdapter adapter = field.getAdapter();
        FrameLayout parent = new FrameLayout(context);
        TextView selected = (TextView) adapter.getView(0, null, parent);
        TextView other = (TextView) adapter.getView(1, null, parent);

        assertEquals(Gravity.CENTER, selected.getGravity());
        assertTrue(selected.isSelected());
        assertTrue(selected.isActivated());
        AccessibilityNodeInfo selectedNode = selected.createAccessibilityNodeInfo();
        assertTrue(selectedNode.isSelected());
        assertFalse(other.isSelected());
        assertFalse(other.isActivated());

        TextView rebound = (TextView) adapter.getView(1, selected, parent);
        assertSame(selected, rebound);
        assertFalse(rebound.isSelected());
        assertFalse(rebound.isActivated());
        assertEquals(ResidencyDropdowns.popupHeightPx(context, items.length),
                field.getDropDownHeight());
    }

    @Test
    public void availableHeightUsesTheLargerSideOfTheAnchor() {
        Rect visibleFrame = new Rect(0, 100, 1000, 500);

        int availableHeight = ResidencyDropdowns.maxAvailableHeightPx(
                visibleFrame, 350, 406, 4);

        assertEquals(254, availableHeight);
    }

    @Test
    public void popupHeightContainsOnlyWholeRowsWithinShortWindowSpace() {
        int rowHeight = 56;
        int padding = 12;
        int oneRowShortOfTwo = rowHeight * 2 + padding - 1;

        int popupHeight = ResidencyDropdowns.popupHeightPx(
                6, rowHeight, padding, oneRowShortOfTwo);

        assertEquals(rowHeight + padding, popupHeight);
        assertEquals(0, (popupHeight - padding) % rowHeight);
        assertTrue(popupHeight <= oneRowShortOfTwo);
        assertEquals(0, ResidencyDropdowns.popupHeightPx(
                6, rowHeight, padding, rowHeight + padding - 1));
    }

    @Test
    public void selectedTextNeverFiltersNonEditableOptions() throws Exception {
        ContextThemeWrapper context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme);
        MaterialAutoCompleteTextView field = new MaterialAutoCompleteTextView(context);
        String[] items = {"全部模式", "系统管理", "均衡", "低功耗", "活跃", "自定义"};
        ResidencyDropdowns.setItems(field, items);
        AtomicInteger resultCount = new AtomicInteger(-1);

        ((ResidencyDropdownAdapter) field.getAdapter()).getFilter().filter(
                "全部模式", resultCount::set);
        for (int attempt = 0; attempt < 100 && resultCount.get() < 0; attempt++) {
            Thread.sleep(10);
            ShadowLooper.idleMainLooper();
        }

        assertEquals(items.length, resultCount.get());
        assertEquals(items.length, field.getAdapter().getCount());
    }
}
