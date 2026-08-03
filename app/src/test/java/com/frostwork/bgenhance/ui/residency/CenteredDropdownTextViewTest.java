package com.frostwork.bgenhance.ui.residency;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.view.ContextThemeWrapper;
import androidx.appcompat.widget.ListPopupWindow;
import com.frostwork.bgenhance.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public final class CenteredDropdownTextViewTest {
    @Test
    public void correctionMovesAsymmetricContentAreaToGeometricCenter() {
        CenteredDropdownTextView field = new CenteredDropdownTextView(
                new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme));
        field.setPadding(10, 30, 50, 10);
        field.layout(0, 0, 300, 100);

        float contentCenterX = (field.getTotalPaddingLeft()
                + field.getWidth() - field.getTotalPaddingRight()) / 2f;
        float contentCenterY = (field.getTotalPaddingTop()
                + field.getHeight() - field.getTotalPaddingBottom()) / 2f;

        assertEquals(field.getWidth() / 2f,
                contentCenterX + field.horizontalCenterCorrection(), 0.01f);
        assertEquals(field.getHeight() / 2f,
                contentCenterY + field.verticalCenterCorrection(), 0.01f);
    }

    @Test
    public void correctionHandlesEndIconOnEitherSide() {
        CenteredDropdownTextView field = new CenteredDropdownTextView(
                new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme));

        field.setPadding(12, 0, 52, 0);
        assertEquals(20f, field.horizontalCenterCorrection(), 0.01f);

        field.setPadding(52, 0, 12, 0);
        assertEquals(-20f, field.horizontalCenterCorrection(), 0.01f);
    }

    @Test
    public void accessiblePopupMatchesTheOrdinaryPopupGeometryAndSurface() {
        CenteredDropdownTextView field = new CenteredDropdownTextView(
                new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme));
        field.layout(0, 0, 300, 100);
        field.setDropDownHeight(240);
        field.setDropDownHorizontalOffset(0);
        field.setDropDownVerticalOffset(8);

        field.synchronizeAccessiblePopup();

        ListPopupWindow popup = field.accessiblePopup();
        assertEquals(300, popup.getWidth());
        assertEquals(240, popup.getHeight());
        assertEquals(0, popup.getHorizontalOffset());
        assertEquals(8, popup.getVerticalOffset());
        org.junit.Assert.assertNotNull(popup.getBackground());
    }
}
