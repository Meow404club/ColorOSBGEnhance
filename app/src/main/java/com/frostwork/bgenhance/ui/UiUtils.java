package com.frostwork.bgenhance.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import com.frostwork.bgenhance.R;

public final class UiUtils {
    private UiUtils() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void applyWindowStyle(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        int bg = activity.getColor(R.color.page_bg);
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                controller.setSystemBarsAppearance(appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else if (!dark) {
            window.getDecorView().setSystemUiVisibility(
                    window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }
}
