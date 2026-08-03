package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.frostwork.bgenhance.R;
import com.google.android.material.card.MaterialCardView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class ResidencyDynamicListTest {
    @Test
    public void ordinaryFontShortListsStayCompact() {
        ActivityController<ResidencyAppsActivity> appsController =
                Robolectric.buildActivity(ResidencyAppsActivity.class).create();
        ActivityController<ResidencyAppPickerActivity> pickerController =
                Robolectric.buildActivity(ResidencyAppPickerActivity.class).create();
        try {
            assertShortListStaysCompact(appsController.get());
            assertShortListStaysCompact(pickerController.get());
        } finally {
            pickerController.destroy();
            appsController.destroy();
        }
    }

    @Test
    public void largeFontStartsBoundedAndBoundedPaddingPreservesScreenInsets() {
        assertFalse(ResidencyAppsActivity.shouldStartBoundedList(1f));
        assertFalse(ResidencyAppPickerActivity.shouldStartBoundedList(1f));
        assertTrue(ResidencyAppsActivity.shouldStartBoundedList(2f));
        assertTrue(ResidencyAppPickerActivity.shouldStartBoundedList(2f));

        ActivityController<ResidencyAppsActivity> appsController =
                Robolectric.buildActivity(ResidencyAppsActivity.class).create();
        ActivityController<ResidencyAppPickerActivity> pickerController =
                Robolectric.buildActivity(ResidencyAppPickerActivity.class).create();
        try {
            ResidencyAppsActivity apps = appsController.get();
            RecyclerView appsList = apps.findViewById(R.id.residency_app_list);
            int appsNormalPadding = appsList.getPaddingBottom();
            apps.applyListSurfaceMode(true, false);
            assertEquals(dp(apps, 16), appsList.getPaddingBottom());
            setFontScale(apps, 2f);
            apps.setListSurfaceMode(true);
            setFontScale(apps, 1f);
            assertBounded(apps, appsNormalPadding);

            ResidencyAppPickerActivity picker = pickerController.get();
            RecyclerView pickerList = picker.findViewById(R.id.residency_app_list);
            int pickerNormalPadding = pickerList.getPaddingBottom();
            picker.applyListSurfaceMode(true, false);
            assertEquals(pickerNormalPadding, pickerList.getPaddingBottom());
            setFontScale(picker, 2f);
            picker.setListSurfaceMode(true);
            setFontScale(picker, 1f);
            assertBounded(picker, pickerNormalPadding);
        } finally {
            pickerController.destroy();
            appsController.destroy();
        }
    }

    @Test
    public void ordinaryFontLongListsPromoteToBoundedAndRemainScrollable() {
        ActivityController<ResidencyAppsActivity> appsController =
                Robolectric.buildActivity(ResidencyAppsActivity.class).create();
        ActivityController<ResidencyAppPickerActivity> pickerController =
                Robolectric.buildActivity(ResidencyAppPickerActivity.class).create();
        try {
            ResidencyAppsActivity apps = appsController.get();
            apps.findViewById(R.id.residency_filters).setVisibility(View.VISIBLE);
            assertLongListBecomesBounded(apps);

            ResidencyAppPickerActivity picker = pickerController.get();
            assertLongListBecomesBounded(picker);
        } finally {
            pickerController.destroy();
            appsController.destroy();
        }
    }

    @Test
    public void oldAndNewBoundaryRowsRebindWhenListGrowsAndShrinks() {
        ResidencyAppsAdapter adapter = new ResidencyAppsAdapter(
                org.robolectric.RuntimeEnvironment.getApplication(),
                item -> {});
        RecordingObserver observer = new RecordingObserver();
        adapter.registerAdapterDataObserver(observer);
        try {
            adapter.submitList(items(40));
            observer.clear();
            adapter.submitList(items(80));
            assertTrue(observer.changedPositions.contains(39));

            adapter.submitList(items(3));
            observer.clear();
            adapter.submitList(items(1));
            assertTrue(observer.changedPositions.contains(0));
        } finally {
            adapter.unregisterAdapterDataObserver(observer);
            adapter.close();
        }
    }

    private static void assertShortListStaysCompact(Activity activity) {
        assertEquals(1f, activity.getResources().getConfiguration().fontScale, 0f);
        RecyclerView list = activity.findViewById(R.id.residency_app_list);
        list.setAdapter(new FixedHeightAdapter(1));
        setContentMode(activity);
        measure(activity, 360, 640);
        measure(activity, 360, 640);

        LinearLayout.LayoutParams surfaceParams = surfaceParams(activity);
        assertEquals(activity.getClass().getSimpleName(),
                ViewGroup.LayoutParams.WRAP_CONTENT, surfaceParams.height);
        assertEquals(0f, surfaceParams.weight, 0f);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, list.getLayoutParams().height);
    }

    private static void assertLongListBecomesBounded(Activity activity) {
        RecyclerView list = activity.findViewById(R.id.residency_app_list);
        int normalBottomPadding = list.getPaddingBottom();
        list.setAdapter(new FixedHeightAdapter(40));
        setContentMode(activity);
        measure(activity, 360, 640);
        measure(activity, 360, 640);

        assertBounded(activity, normalBottomPadding);
        assertTrue(list.canScrollVertically(1));
    }

    private static void setContentMode(Activity activity) {
        if (activity instanceof ResidencyAppsActivity) {
            ((ResidencyAppsActivity) activity).setListSurfaceMode(true);
        } else {
            ((ResidencyAppPickerActivity) activity).setListSurfaceMode(true);
        }
    }

    private static void assertBounded(Activity activity, int expectedBottomPadding) {
        RecyclerView list = activity.findViewById(R.id.residency_app_list);
        LinearLayout.LayoutParams surfaceParams = surfaceParams(activity);
        assertEquals(0, surfaceParams.height);
        assertEquals(1f, surfaceParams.weight, 0f);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, list.getLayoutParams().height);
        assertEquals(expectedBottomPadding, list.getPaddingBottom());
    }

    private static LinearLayout.LayoutParams surfaceParams(Activity activity) {
        MaterialCardView surface = activity.findViewById(R.id.residency_list_surface);
        return (LinearLayout.LayoutParams) surface.getLayoutParams();
    }

    private static List<ResidencyAppItem> items(int count) {
        List<ResidencyAppItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            items.add(new ResidencyAppItem(
                    "Application " + index,
                    "test.application." + index,
                    true,
                    false,
                    true,
                    ResidencyAppSafety.Result.allowed(),
                    null));
        }
        return items;
    }

    private static void measure(Activity activity, int widthDp, int heightDp) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        int width = dp(activity, widthDp);
        int height = dp(activity, heightDp);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private static void setFontScale(Activity activity, float fontScale) {
        Configuration configuration = new Configuration(
                activity.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        activity.getResources().updateConfiguration(
                configuration, activity.getResources().getDisplayMetrics());
    }

    private static final class RecordingObserver extends RecyclerView.AdapterDataObserver {
        private final Set<Integer> changedPositions = new HashSet<>();

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            for (int position = positionStart; position < positionStart + itemCount; position++) {
                changedPositions.add(position);
            }
        }

        void clear() {
            changedPositions.clear();
        }
    }

    private static final class FixedHeightAdapter
            extends RecyclerView.Adapter<FixedHeightAdapter.Holder> {
        private final int count;

        FixedHeightAdapter(int count) {
            this.count = count;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View item = new View(parent.getContext());
            item.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(92 * parent.getResources().getDisplayMetrics().density)));
            return new Holder(item);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {}

        @Override
        public int getItemCount() {
            return count;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
