/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.ui.activity.base;

import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.manager.App;
import org.lsposed.manager.R;
import org.lsposed.manager.util.Telemetry;
import org.lsposed.manager.util.ThemeUtil;

import rikka.material.app.MaterialActivity;

public class BaseActivity extends MaterialActivity {
    private static Bitmap icon = null;

    /**
     * Watch-class screens are smaller than Android's smallest official size bucket
     * (small = 320 x 426dp), so the platform runs every app in screen compatibility
     * mode and synthesises a {@code normal} configuration for it. That makes the
     * -watch and -small resource qualifiers unreachable no matter how the activity
     * configuration is overridden, so layouts cannot be swapped per screen class.
     * <p>
     * Instead, lower the effective density so the ordinary phone layouts get enough
     * logical room to lay themselves out. At the stock 416dpi this display is only
     * ~159 x 179dp, which is too narrow for the five-tab nav bar and the card
     * paddings; scaling to {@link #WATCH_TARGET_WIDTH_DP} keeps everything legible
     * while letting it fit.
     */
    private static final float WATCH_TARGET_WIDTH_DP = 240f;

    private static volatile Boolean isWatch = null;
    private boolean applyingWatchDensity = false;

    private void applyWatchDensity(Resources res) {
        // getResources() is overridden to call this, and PackageManager lookups can
        // themselves reach back into getResources(), so guard against re-entering
        // rather than relying on the feature result being cached first.
        if (res == null || applyingWatchDensity) return;
        applyingWatchDensity = true;
        try {
            if (isWatch == null) {
                isWatch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
            }
            if (!isWatch) return;
            scaleForWatch(res);
        } finally {
            applyingWatchDensity = false;
        }
    }

    private void scaleForWatch(Resources res) {
        var metrics = res.getDisplayMetrics();
        if (metrics.widthPixels <= 0) return;
        float density = metrics.widthPixels / WATCH_TARGET_WIDTH_DP;
        if (density >= metrics.density) return; // already roomy enough
        // scaledDensity carries the user's font scale, so derive it from the ratio
        // rather than overwriting it, otherwise font size settings stop applying.
        float fontScale = metrics.scaledDensity / metrics.density;
        metrics.density = density;
        metrics.scaledDensity = density * fontScale;
        metrics.densityDpi = Math.round(density * DisplayMetrics.DENSITY_DEFAULT);

        // Move the configuration's dp extents in step with the density. A window is
        // sized from the configuration while its content is measured from the
        // metrics, so leaving the two disagreeing makes dialogs lay out against the
        // unscaled screen and get clipped. Mutating this Resources in place rather
        // than deriving a context: the parasitic manager shares its process with
        // system_server, and handing that process a new configuration context kills
        // the bridge LSPosed injects there.
        var configuration = res.getConfiguration();
        configuration.densityDpi = metrics.densityDpi;
        configuration.screenWidthDp = Math.round(metrics.widthPixels / density);
        configuration.screenHeightDp = Math.round(metrics.heightPixels / density);
        configuration.smallestScreenWidthDp =
                Math.min(configuration.screenWidthDp, configuration.screenHeightDp);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        applyWatchDensity(super.getResources());
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
    }

    @Override
    public Resources getResources() {
        // Re-apply on every fetch: something in the theme/locale path restores the
        // real metrics without a configuration change, which otherwise makes the
        // scaling revert as soon as another fragment is opened.
        var res = super.getResources();
        applyWatchDensity(res);
        return res;
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyWatchDensity(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The framework restores the real metrics on every configuration change.
        applyWatchDensity(super.getResources());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!App.isParasitic) return;
        for (var task : getSystemService(ActivityManager.class).getAppTasks()) {
            task.setExcludeFromRecents(false);
        }
        if (icon == null) {
            var drawable = getApplicationInfo().loadIcon(getPackageManager());
            if (drawable instanceof BitmapDrawable) {
                icon = ((BitmapDrawable) drawable).getBitmap();
            } else if (drawable instanceof AdaptiveIconDrawable) {
                icon = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(icon);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
        }
        setTaskDescription(new ActivityManager.TaskDescription(getTitle().toString(), icon, getColor(R.color.ic_launcher_background)));
    }

    @Override
    protected void onStop() {
        super.onStop();
        Telemetry.trackEvent("BaseActivity stop", null);
    }

    @Override
    public void onApplyUserThemeResource(@NonNull Resources.Theme theme, boolean isDecorView) {
        if (!ThemeUtil.isSystemAccent()) {
            theme.applyStyle(ThemeUtil.getColorThemeStyleRes(), true);
        }
        theme.applyStyle(ThemeUtil.getNightThemeStyleRes(this), true);
        theme.applyStyle(rikka.material.preference.R.style.ThemeOverlay_Rikka_Material3_Preference, true);
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            theme.applyStyle(R.style.ThemeOverlay_Watch, true);
        }
    }

    @Override
    public String computeUserThemeKey() {
        return ThemeUtil.getColorTheme() + ThemeUtil.getNightTheme(this);
    }

    @Override
    public void onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars();
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }
}
