package com.TuringSoftware.FrameGenerator.utils;
import com.TuringSoftware.FrameGenerator.AppConstants;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.util.Log;

import static com.TuringSoftware.FrameGenerator.AppConstants.TAG;
import com.TuringSoftware.FrameGenerator.MainActivity;

/**
 * BroadcastReceiver de estado de batería.
 * Extrae la inner class BatteryReceiver de ScreenScaler.
 */
public class BatteryHelper extends BroadcastReceiver {

    private final MainActivity act;
    private int lastLevel = -1;

    public BatteryHelper(MainActivity act) {
        this.act = act;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return;
        int pct = (level * 100) / scale;
        if (pct != lastLevel) {
            lastLevel = pct;
            Log.d(TAG, "Batería: " + pct + "%");
            if (pct <= 15 && act.isCapturing()) {
                Log.w(TAG, "Batería crítica durante captura — considera detener");
            }
        }
    }
}
