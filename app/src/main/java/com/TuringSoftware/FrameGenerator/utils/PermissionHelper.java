package com.TuringSoftware.FrameGenerator.utils;
import com.TuringSoftware.FrameGenerator.AppConstants;

import android.app.Activity;
import com.TuringSoftware.FrameGenerator.MainActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;
import com.TuringSoftware.FrameGenerator.R;

public final class PermissionHelper {

    private PermissionHelper() {}

    public static void requestCapture(MainActivity act) {
        if (!hasOverlayPermission(act)) {
            requestOverlay(act);
            return;
        }
        if (act.touchForwardEnabled && !isAccessibilityEnabled(act)) {
            requestAccessibility(act);
            return;
        }
        act.getMpManager();
        Intent intent = act.getMpManager().createScreenCaptureIntent();
        act.startActivityForResult(intent, REQUEST_CODE_CAPTURE);
    }

    public static boolean hasOverlayPermission(Activity act) {
        if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(act);
        return true;
    }

    public static void requestOverlay(Activity act) {
        if (Build.VERSION.SDK_INT >= 23) {
            Toast.makeText(act, R.string.toast_need_overlay, Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + act.getPackageName()));
            act.startActivityForResult(i, REQUEST_CODE_OVERLAY);
        }
    }

    public static boolean isAccessibilityEnabled(Activity act) {
        String service = act.getPackageName() + "/"
            + MainActivity.TouchForwardService.class.getName();
        try {
            int enabled = Settings.Secure.getInt(
                act.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled == 0) return false;
            String services = Settings.Secure.getString(
                act.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestAccessibility(Activity act) {
        Toast.makeText(act, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        act.startActivityForResult(i, REQUEST_CODE_ACCESSIBILITY);
    }
}
