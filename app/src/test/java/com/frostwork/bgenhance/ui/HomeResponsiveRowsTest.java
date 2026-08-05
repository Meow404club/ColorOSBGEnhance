package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.frostwork.bgenhance.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class HomeResponsiveRowsTest {
    @Test
    public void normalFontKeepsResidencyStatusInTheHorizontalRow() {
        View row = inflateRow();
        TextView status = row.findViewById(R.id.status_residency);
        LinearLayout text = row.findViewById(R.id.residency_home_text_container);

        HomeResponsiveRows.adaptResidencyRow(row, 1f);

        assertNotSame(text, status.getParent());
        assertSame(row, status.getParent());
    }

    @Test
    public void largeFontStacksResidencyStatusBelowItsSummary() {
        View row = inflateRow();
        TextView status = row.findViewById(R.id.status_residency);
        LinearLayout text = row.findViewById(R.id.residency_home_text_container);

        HomeResponsiveRows.adaptResidencyRow(row, 2f);
        HomeResponsiveRows.adaptResidencyRow(row, 2f);

        assertSame(text, status.getParent());
        assertEquals(text.getChildCount() - 1, text.indexOfChild(status));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) status.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertTrue(params.topMargin > 0);
        assertEquals(View.TEXT_ALIGNMENT_VIEW_START, status.getTextAlignment());
    }

    private static View inflateRow() {
        Application application = RuntimeEnvironment.getApplication();
        LinearLayout row = new LinearLayout(application);
        LinearLayout text = new LinearLayout(application);
        text.setId(R.id.residency_home_text_container);
        row.addView(text);
        TextView status = new TextView(application);
        status.setId(R.id.status_residency);
        row.addView(status);
        return row;
    }
}
