package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
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
    public void optionsAreCenteredAndOnlyCurrentValueIsActivated() {
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
        assertTrue(selected.isActivated());
        assertFalse(other.isActivated());
        assertEquals(ResidencyDropdowns.popupHeightPx(context, items.length),
                field.getDropDownHeight());
    }

    @Test
    public void popupHeightShowsWholeRowsAndStaysBoundedOnShortScreens() {
        ContextThemeWrapper context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme);
        Configuration configuration = context.getResources().getConfiguration();
        int previousHeight = configuration.screenHeightDp;
        try {
            configuration.screenHeightDp = 360;
            int rowHeight = context.getResources().getDimensionPixelSize(
                    R.dimen.field_min_height);
            int padding = context.getResources().getDimensionPixelSize(
                    R.dimen.dropdown_popup_padding) * 2;

            int popupHeight = ResidencyDropdowns.popupHeightPx(context, 6);

            assertEquals(0, (popupHeight - padding) % rowHeight);
            assertTrue(popupHeight >= rowHeight * 2 + padding);
            assertTrue(popupHeight < rowHeight * 6 + padding);
        } finally {
            configuration.screenHeightDp = previousHeight;
        }
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
