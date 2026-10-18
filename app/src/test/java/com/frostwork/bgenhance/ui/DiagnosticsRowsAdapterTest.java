package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.frostwork.bgenhance.DiagnosticsResult;
import com.frostwork.bgenhance.R;
import com.frostwork.bgenhance.diagnostics.DiagnosticsQuery;
import com.frostwork.bgenhance.diagnostics.RecentBehaviorStats;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsDataSource;
import com.frostwork.bgenhance.ui.diagnostics.DiagnosticsUiMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class DiagnosticsRowsAdapterTest {
    private static final String PACKAGE = "com.example.reader";

    @Test
    public void namedOtherUserIdentityShowsApplicationWithoutManagementAction() {
        Context context = themedContext();
        int uid = 110_321;
        DiagnosticsDataSource.UidResolutionPage identities =
                DiagnosticsDataSource.UidResolutionPage.success(Map.of(
                        uid,
                        DiagnosticsDataSource.UidResolution.single(
                                uid,
                                new DiagnosticsDataSource.ResolvedApplication(
                                        PACKAGE,
                                        "Reader Clone",
                                        false,
                                        false,
                                        false))));
        DiagnosticsUiMapper.ApplicationRow row = new DiagnosticsUiMapper()
                .mapApplications(
                        identities,
                        Collections.singletonList(aggregate(PACKAGE, uid, 5L)))
                .get(0);
        AtomicReference<String> clicked = new AtomicReference<>();
        DiagnosticsRowsAdapter adapter =
                new DiagnosticsRowsAdapter(context,
                        (packageName, userId) -> clicked.set(packageName), null);
        try {
            DiagnosticsRowsAdapter.RowViewHolder holder = holder(adapter, context);
            holder.bind(row);

            assertEquals("Reader Clone", text(holder, R.id.diagnostics_row_title));
            String subtitle = text(holder, R.id.diagnostics_row_subtitle);
            assertTrue(subtitle.contains(String.valueOf(uid)));
            assertTrue(subtitle.contains(PACKAGE));
            assertEquals(2, ((TextView) holder.itemView.findViewById(
                    R.id.diagnostics_row_subtitle)).getMaxLines());
            assertEquals(View.GONE, holder.itemView.findViewById(
                    R.id.diagnostics_row_chevron).getVisibility());
            assertFalse(holder.itemView.isClickable());
            assertEquals(null, clicked.get());
        } finally {
            adapter.close();
        }
    }

    @Test
    public void sharedUidKeepsUniquePackageCountAndDisambiguatesDuplicateLabels() {
        Context context = themedContext();
        int uid = 10_777;
        List<DiagnosticsDataSource.ResolvedApplication> applications = Arrays.asList(
                application("com.example.one", "Tools"),
                application("com.example.two", "Tools"),
                application("com.example.three", "Camera"),
                application("com.example.four", "Notes"));
        DiagnosticsUiMapper.ApplicationRow row = new DiagnosticsUiMapper()
                .mapApplications(
                        DiagnosticsDataSource.UidResolutionPage.success(Map.of(
                                uid,
                                DiagnosticsDataSource.UidResolution.shared(
                                        uid, applications))),
                        Collections.singletonList(aggregate("", uid, 4L)))
                .get(0);
        DiagnosticsRowsAdapter adapter = new DiagnosticsRowsAdapter(context);
        try {
            DiagnosticsRowsAdapter.RowViewHolder holder = holder(adapter, context);
            holder.bind(row);

            assertEquals(context.getString(
                    R.string.diagnostics_application_shared_uid),
                    text(holder, R.id.diagnostics_row_title));
            String subtitle = text(holder, R.id.diagnostics_row_subtitle);
            assertTrue(subtitle.contains("com.example.one"));
            assertTrue(subtitle.contains("com.example.two"));
            assertTrue(subtitle.contains(context.getString(
                    R.string.diagnostics_uid_applications_more, "", 4)));
        } finally {
            adapter.close();
        }
    }

    @Test
    public void manageableApplicationExposesOneClearPolicyAction() {
        Context context = themedContext();
        DiagnosticsUiMapper.ApplicationRow row = new DiagnosticsUiMapper()
                .mapApplications(
                        Collections.singletonList(
                                new DiagnosticsDataSource.ManagedApplication(
                                        PACKAGE, "Reader", true)),
                        Collections.singletonList(aggregate(PACKAGE, 10_321, 2L)))
                .get(0);
        AtomicReference<String> clicked = new AtomicReference<>();
        DiagnosticsRowsAdapter adapter =
                new DiagnosticsRowsAdapter(context,
                        (packageName, userId) -> clicked.set(packageName), null);
        try {
            DiagnosticsRowsAdapter.RowViewHolder holder = holder(adapter, context);
            holder.bind(row);

            assertEquals(View.VISIBLE, holder.itemView.findViewById(
                    R.id.diagnostics_row_chevron).getVisibility());
            assertTrue(holder.itemView.isClickable());
            assertTrue(holder.itemView.performClick());
            assertEquals(PACKAGE, clicked.get());
            assertEquals(1, occurrences(
                    holder.itemView.getContentDescription().toString(),
                    context.getString(R.string.diagnostics_manage_application)));
        } finally {
            adapter.close();
        }
    }

    @Test
    public void cloneApplicationActionKeepsItsUserId() {
        Context context = themedContext();
        int cloneUid = 99_910_321;
        DiagnosticsUiMapper.ApplicationRow row = new DiagnosticsUiMapper()
                .mapApplications(
                        Collections.singletonList(
                                new DiagnosticsDataSource.ManagedApplication(
                                        PACKAGE, 999, "Reader Clone", true)),
                        Collections.singletonList(aggregate(PACKAGE, cloneUid, 2L)))
                .get(0);
        AtomicReference<String> clicked = new AtomicReference<>();
        DiagnosticsRowsAdapter adapter = new DiagnosticsRowsAdapter(context,
                (packageName, userId) -> clicked.set(packageName + ":" + userId), null);
        try {
            DiagnosticsRowsAdapter.RowViewHolder holder = holder(adapter, context);
            holder.bind(row);

            assertTrue(text(holder, R.id.diagnostics_row_subtitle).contains(
                    context.getString(R.string.residency_user_id_label, 999)));
            assertTrue(holder.itemView.performClick());
            assertEquals(PACKAGE + ":999", clicked.get());
        } finally {
            adapter.close();
        }
    }

    @Test
    public void legacyNamedApplicationShowsUnknownUserWithoutPolicyAction() {
        Context context = themedContext();
        DiagnosticsUiMapper.ApplicationRow row = new DiagnosticsUiMapper()
                .mapApplications(Collections.singletonList(aggregate(PACKAGE, -1, 2L)))
                .get(0);
        DiagnosticsRowsAdapter adapter = new DiagnosticsRowsAdapter(context,
                (packageName, userId) -> { throw new AssertionError("legacy policy action"); },
                null);
        try {
            DiagnosticsRowsAdapter.RowViewHolder holder = holder(adapter, context);
            holder.bind(row);

            assertTrue(text(holder, R.id.diagnostics_row_subtitle).contains(
                    context.getString(R.string.diagnostics_user_unknown)));
            assertFalse(holder.itemView.isClickable());
            assertEquals(View.GONE, holder.itemView.findViewById(
                    R.id.diagnostics_row_chevron).getVisibility());
        } finally {
            adapter.close();
        }
    }

    @Test
    public void behaviorRowAnnouncesDetailActionExactlyOnce() {
        Context context = themedContext();
        DiagnosticsUiMapper.UiRow row = new DiagnosticsUiMapper()
                .mapBehaviors(RecentBehaviorStats.fromAggregateRows(
                        Collections.emptyList()))
                .get(0);
        DiagnosticsRowsAdapter adapter =
                new DiagnosticsRowsAdapter(context, behavior -> {});
        try {
            DiagnosticsRowsAdapter.RowViewHolder holder = holder(adapter, context);
            holder.bind(row);
            String action = context.getString(R.string.diagnostics_behavior_detail_action);

            assertEquals(1, occurrences(
                    holder.itemView.getContentDescription().toString(), action));
            assertFalse(text(holder, R.id.diagnostics_row_detail).contains(action));
        } finally {
            adapter.close();
        }
    }

    @Test
    public void runtimeTransitionStatusesExplainTheRequiredUserAction() {
        assertEquals(R.string.diagnostics_status_entry_loaded_detail,
                DiagnosticsRowsAdapter.RowViewHolder.hookStatusDetailResource(
                        DiagnosticsUiMapper.HookStatus.ENTRY_LOADED));
        assertEquals(R.string.diagnostics_status_bootstrap_failed_detail,
                DiagnosticsRowsAdapter.RowViewHolder.hookStatusDetailResource(
                        DiagnosticsUiMapper.HookStatus.BOOTSTRAP_FAILED));
        assertEquals(R.string.diagnostics_status_version_mismatch_detail,
                DiagnosticsRowsAdapter.RowViewHolder.hookStatusDetailResource(
                        DiagnosticsUiMapper.HookStatus.VERSION_MISMATCH));
        assertEquals(0,
                DiagnosticsRowsAdapter.RowViewHolder.hookStatusDetailResource(
                        DiagnosticsUiMapper.HookStatus.READY));
    }

    private static DiagnosticsDataSource.ResolvedApplication application(
            String packageName, String displayName) {
        return new DiagnosticsDataSource.ResolvedApplication(
                packageName, displayName, false);
    }

    private static DiagnosticsRowsAdapter.RowViewHolder holder(
            DiagnosticsRowsAdapter adapter, Context context) {
        return adapter.onCreateViewHolder(new FrameLayout(context), 0);
    }

    private static String text(
            DiagnosticsRowsAdapter.RowViewHolder holder, int viewId) {
        return ((TextView) holder.itemView.findViewById(viewId)).getText().toString();
    }

    private static int occurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private static DiagnosticsQuery.AggregateRow aggregate(
            String packageName, int uid, long count) {
        return new DiagnosticsQuery.AggregateRow(
                packageName,
                uid,
                "feature",
                "hook",
                "WAKE",
                "source",
                "decision",
                DiagnosticsResult.PASSED,
                count,
                count,
                0L,
                0L,
                count,
                count,
                count,
                1L,
                count);
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.AppTheme);
    }
}
