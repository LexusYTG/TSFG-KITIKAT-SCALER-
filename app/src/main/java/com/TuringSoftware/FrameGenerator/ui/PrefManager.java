package com.TuringSoftware.FrameGenerator.ui;
import com.TuringSoftware.FrameGenerator.*;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;

public class PrefManager {

    private final MainActivity act;
    private final SharedPreferences prefs;

    public PrefManager(MainActivity act, SharedPreferences prefs) {
        this.act   = act;
        this.prefs = prefs;
    }

    public void restorePreferences(MainActivity a) {
        a.resolutionScale      = prefs.getFloat  (PREF_RESOLUTION_SCALE,    DEFAULT_RESOLUTION_SCALE);
        a.framesToGenerate     = prefs.getInt    (PREF_FRAMES_TO_GENERATE,  DEFAULT_FRAMES_TO_GENERATE);
        a.colorQuality         = prefs.getInt    (PREF_COLOR_QUALITY,       DEFAULT_COLOR_QUALITY);
        a.fisheyeCorrection    = prefs.getInt    (PREF_FISHEYE_CORRECTION,  DEFAULT_FISHEYE_CORRECTION);
        a.sifgVersion          = prefs.getInt    (PREF_SIFG_VERSION,        DEFAULT_SIFG_VERSION);
        a.touchForwardEnabled  = prefs.getBoolean(PREF_TOUCH_FORWARD,       DEFAULT_TOUCH_FORWARD);
        a.targetFps            = prefs.getInt    (PREF_TARGET_FPS,          DEFAULT_TARGET_FPS);
        a.captureMode          = prefs.getInt    (PREF_CAPTURE_MODE,        CAPTURE_MODE_FULLSCREEN);
        a.gfalLinesLargo       = prefs.getInt    (PREF_GFAL_LINES_LARGO,    DEFAULT_GFAL_LINES_LARGO);
        a.gfalLinesAncho       = prefs.getInt    (PREF_GFAL_LINES_ANCHO,    DEFAULT_GFAL_LINES_ANCHO);
        a.gfalGridW            = prefs.getInt    (PREF_GFAL_GRID_W,         DEFAULT_GFAL_GRID_W);
        a.gfalGridH            = prefs.getInt    (PREF_GFAL_GRID_H,         DEFAULT_GFAL_GRID_H);
        a.gfalSearchRadius     = prefs.getInt    (PREF_GFAL_SEARCH_RADIUS,  DEFAULT_GFAL_SEARCH_RADIUS);
        a.gfalNetEnabled       = prefs.getBoolean(PREF_GFAL_NET_ENABLED,    DEFAULT_GFAL_NET_ENABLED);
        a.gfalNetAlpha         = prefs.getInt    (PREF_GFAL_NET_ALPHA,      DEFAULT_GFAL_NET_ALPHA);
        a.sifgRegionW          = prefs.getInt    (PREF_SIFG_REGION_W,       DEFAULT_SIFG_REGION_W);
        a.sifgRegionH          = prefs.getInt    (PREF_SIFG_REGION_H,       DEFAULT_SIFG_REGION_H);
        a.asifgNeurons         = prefs.getInt    (PREF_ASIFG_NEURONS,       DEFAULT_ASIFG_NEURONS);
        a.asifgThreshold       = prefs.getInt    (PREF_ASIFG_THRESHOLD,     DEFAULT_ASIFG_THRESHOLD);
    }

    public void save() {
        prefs.edit()
            .putFloat  (PREF_RESOLUTION_SCALE,    act.resolutionScale)
            .putInt    (PREF_FRAMES_TO_GENERATE,  act.framesToGenerate)
            .putInt    (PREF_COLOR_QUALITY,       act.colorQuality)
            .putInt    (PREF_FISHEYE_CORRECTION,  act.fisheyeCorrection)
            .putInt    (PREF_SIFG_VERSION,        act.sifgVersion)
            .putBoolean(PREF_TOUCH_FORWARD,       act.touchForwardEnabled)
            .putInt    (PREF_TARGET_FPS,           act.targetFps)
            .putInt    (PREF_CAPTURE_MODE,        act.captureMode)
            .putInt    (PREF_GFAL_LINES_LARGO,    act.gfalLinesLargo)
            .putInt    (PREF_GFAL_LINES_ANCHO,    act.gfalLinesAncho)
            .putInt    (PREF_GFAL_GRID_W,         act.gfalGridW)
            .putInt    (PREF_GFAL_GRID_H,         act.gfalGridH)
            .putInt    (PREF_GFAL_SEARCH_RADIUS,  act.gfalSearchRadius)
            .putBoolean(PREF_GFAL_NET_ENABLED,    act.gfalNetEnabled)
            .putInt    (PREF_GFAL_NET_ALPHA,      act.gfalNetAlpha)
            .putInt    (PREF_SIFG_REGION_W,       act.sifgRegionW)
            .putInt    (PREF_SIFG_REGION_H,       act.sifgRegionH)
            .putInt    (PREF_ASIFG_NEURONS,       act.asifgNeurons)
            .putInt    (PREF_ASIFG_THRESHOLD,     act.asifgThreshold)
            .apply();
    }

    public void detectNotchInsets(final MainActivity a) {
        int statusFb = 0, navFb = 0;
        try {
            int rid = a.getResources().getIdentifier("status_bar_height","dimen","android");
            if (rid > 0) statusFb = a.getResources().getDimensionPixelSize(rid);
            int nid = a.getResources().getIdentifier("navigation_bar_height","dimen","android");
            if (nid > 0) navFb = a.getResources().getDimensionPixelSize(nid);
        } catch (Exception ignored) {}

        a.topInset    = prefs.getInt(PREF_INSET_STATUS_BAR, statusFb);
        a.bottomInset = prefs.getInt(PREF_INSET_NAV_BAR,    navFb);
        a.sideInset   = prefs.getInt(PREF_INSET_SIDE,       0);

        if (Build.VERSION.SDK_INT >= 28) {
            a.getWindow().getDecorView().setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        android.view.DisplayCutout cutout = insets.getDisplayCutout();
                        int top   = insets.getSystemWindowInsetTop();
                        int nav   = insets.getSystemWindowInsetBottom();
                        int left  = insets.getSystemWindowInsetLeft();
                        int right = insets.getSystemWindowInsetRight();
                        int side  = Math.max(left, right);
                        if (cutout != null) top = Math.max(top, cutout.getSafeInsetTop());
                        if (top > 0 || nav > 0 || side > 0) {
                            a.topInset    = top;
                            a.bottomInset = nav;
                            a.sideInset   = side;
                            prefs.edit()
                                .putInt(PREF_INSET_STATUS_BAR, top)
                                .putInt(PREF_INSET_NAV_BAR,    nav)
                                .putInt(PREF_INSET_SIDE,       side)
                                .apply();
                        }
                        return insets;
                    }
                });
        } else {
            prefs.edit()
                .putInt(PREF_INSET_STATUS_BAR, a.topInset)
                .putInt(PREF_INSET_NAV_BAR,    a.bottomInset)
                .apply();
        }
    }

    public void adjustResolutions(MainActivity a, DisplayMetrics metrics) {
        boolean landscape = metrics.widthPixels > metrics.heightPixels;
        String key = resCacheKey(landscape, a.resolutionScale);
        String cached = prefs.getString(key, null);
        if (cached != null) {
            try {
                String[] p = cached.split(":");
                a.setTargetWidth (Integer.parseInt(p[0]));
                a.setTargetHeight(Integer.parseInt(p[1]));
                a.setSourceWidth (Integer.parseInt(p[2]));
                a.setSourceHeight(Integer.parseInt(p[3]));
                return;
            } catch (Exception e) {
                Log.w(TAG, "Cache resolución corrupto, recalculando");
            }
        }
        computeAndCacheAll(a, metrics);
    }

    private void computeAndCacheAll(MainActivity a, DisplayMetrics metrics) {
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int longSide  = Math.max(metrics.widthPixels, metrics.heightPixels);
        int botInset  = a.bottomInset;
        int sideInset = a.sideInset;

        float[] scales        = {0.25f, 0.5f, 0.75f};
        String[] portraitKeys  = {PREF_RES_CACHE_P25, PREF_RES_CACHE_P50, PREF_RES_CACHE_P75};
        String[] landscapeKeys = {PREF_RES_CACHE_L25, PREF_RES_CACHE_L50, PREF_RES_CACHE_L75};
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < 3; i++) {
            int tWp = (shortSide/8)*8;
            int tHp = Math.max(8, ((longSide - botInset)/8)*8);
            int sWp = Math.max(8, ((int)(tWp*scales[i]))/8*8);
            int sHp = Math.max(8, ((int)(tHp*scales[i]))/8*8);
            ed.putString(portraitKeys[i], tWp+":"+tHp+":"+sWp+":"+sHp);
            int tWl = Math.max(8, ((longSide - sideInset)/8)*8);
            int tHl = (shortSide/8)*8;
            int sWl = Math.max(8, ((int)(tWl*scales[i]))/8*8);
            int sHl = Math.max(8, ((int)(tHl*scales[i]))/8*8);
            ed.putString(landscapeKeys[i], tWl+":"+tHl+":"+sWl+":"+sHl);
        }
        ed.apply();

        boolean landscape = metrics.widthPixels > metrics.heightPixels;
        int tW = landscape
            ? Math.max(8, ((longSide  - sideInset)/8)*8)
            : (shortSide/8)*8;
        int tH = landscape
            ? (shortSide/8)*8
            : Math.max(8, ((longSide  - botInset)/8)*8);
        a.setTargetWidth(tW);
        a.setTargetHeight(tH);
        a.setSourceWidth (Math.max(8, ((int)(tW * a.resolutionScale))/8*8));
        a.setSourceHeight(Math.max(8, ((int)(tH * a.resolutionScale))/8*8));
    }

    public void invalidateResolutionCache() {
        prefs.edit()
            .remove(PREF_RES_CACHE_P25).remove(PREF_RES_CACHE_P50).remove(PREF_RES_CACHE_P75)
            .remove(PREF_RES_CACHE_L25).remove(PREF_RES_CACHE_L50).remove(PREF_RES_CACHE_L75)
            .apply();
    }

    private static String resCacheKey(boolean landscape, float scale) {
        if      (scale <= 0.26f) return landscape ? PREF_RES_CACHE_L25 : PREF_RES_CACHE_P25;
        else if (scale <= 0.51f) return landscape ? PREF_RES_CACHE_L50 : PREF_RES_CACHE_P50;
        else                     return landscape ? PREF_RES_CACHE_L75 : PREF_RES_CACHE_P75;
    }
}

