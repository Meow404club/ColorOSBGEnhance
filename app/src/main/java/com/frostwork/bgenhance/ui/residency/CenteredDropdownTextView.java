package com.frostwork.bgenhance.ui.residency;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.PopupWindow;
import androidx.appcompat.widget.ListPopupWindow;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import java.lang.reflect.Field;

/** Centers dropdown text against the field, including the exposed-menu end icon. */
public final class CenteredDropdownTextView extends MaterialAutoCompleteTextView {
    private static final Field MODAL_POPUP_FIELD = findField(
            MaterialAutoCompleteTextView.class, "modalListPopup");
    private static final Field POPUP_WINDOW_FIELD = findField(
            ListPopupWindow.class, "mPopup");

    public CenteredDropdownTextView(Context context) {
        super(context);
    }

    public CenteredDropdownTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CenteredDropdownTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int checkpoint = canvas.save();
        canvas.translate(horizontalCenterCorrection(), verticalCenterCorrection());
        super.onDraw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    @Override
    public void showDropDown() {
        synchronizeAccessiblePopup();
        super.showDropDown();
    }

    float horizontalCenterCorrection() {
        return (getTotalPaddingRight() - getTotalPaddingLeft()) / 2f;
    }

    float verticalCenterCorrection() {
        return (getTotalPaddingBottom() - getTotalPaddingTop()) / 2f;
    }

    void synchronizeAccessiblePopup() {
        ListPopupWindow popup = accessiblePopup();
        if (popup == null) return;
        Drawable background = getDropDownBackground();
        if (background != null) {
            Drawable.ConstantState state = background.getConstantState();
            popup.setBackgroundDrawable(state == null
                    ? background
                    : state.newDrawable(getResources()).mutate());
        }
        popup.setHorizontalOffset(getDropDownHorizontalOffset());
        popup.setVerticalOffset(getDropDownVerticalOffset());
        popup.setHeight(getDropDownHeight());
        if (getWidth() > 0) popup.setWidth(getWidth());

        PopupWindow popupWindow = popupWindow(popup);
        if (popupWindow != null) popupWindow.setElevation(getPopupElevation());
    }

    ListPopupWindow accessiblePopup() {
        if (MODAL_POPUP_FIELD == null) return null;
        try {
            return (ListPopupWindow) MODAL_POPUP_FIELD.get(this);
        } catch (IllegalAccessException | ClassCastException ignored) {
            return null;
        }
    }

    private static PopupWindow popupWindow(ListPopupWindow popup) {
        if (POPUP_WINDOW_FIELD == null) return null;
        try {
            return (PopupWindow) POPUP_WINDOW_FIELD.get(popup);
        } catch (IllegalAccessException | ClassCastException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
