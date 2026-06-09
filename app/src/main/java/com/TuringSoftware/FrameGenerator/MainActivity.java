package com.TuringSoftware.FrameGenerator;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.TuringSoftware.FrameGenerator.ui.DialogManager;
import com.TuringSoftware.FrameGenerator.ui.PrefManager;
import com.TuringSoftware.FrameGenerator.ui.UiController;
import com.TuringSoftware.FrameGenerator.utils.BatteryHelper;
import com.TuringSoftware.FrameGenerator.utils.CatMoodHelper;
import java.util.concurrent.atomic.AtomicInteger;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;

/**
 * Activity principal de KITIKAT Scaler.
 *
 * Responsabilidades:
 *   - Inflar el XML y cablear vistas.
 *   - Gestionar permisos y ciclo de vida.
 *   - Delegar lógica de UI a UiController.
 *   - Vincular/desvincular ScreenCaptureService.
 *
 * Java 7 — sin lambdas.
 */
public class MainActivity extends Activity {

    // ── Vistas principales ───────────────────────────────────────────────────
    private Button   btnStartStop;
    private TextView tvSrcRes, tvDstRes, tvScale;
    private TextView pillPerf, pillSifg, pillAsifg, pillGfal;
    private TextView pillCpu, pillGpu;
    private LinearLayout gpuToggleRow;
    private TextView tvStatus, tvSifgInfo, tvGenerationInfo;
    private TextView tvCatMood;

    private TextView tvGenVal, tvColorVal, tvResVal;
    private TextView tvFishVal, tvTouchVal, tvCapModeVal, tvFpsTargetVal;

    private TextView tvFpsVal, tvFramesVal, tvDroppedVal, tvEfficiencyVal, tvSessionVal;

    // ── Controladores delegados ───────────────────────────────────────────────
    public UiController   uiController;
    public DialogManager  dialogManager;
    public PrefManager    prefManager;

    // ── Estado de la app ─────────────────────────────────────────────────────
    public volatile boolean       isCapturing  = false;
    private int  targetWidth, targetHeight;
    private int  sourceWidth, sourceHeight;
    private int  screenDensityDpi;
    public int  topInset, bottomInset;
    private AtomicInteger currentMode = new AtomicInteger(MODE_PERFORMANCE);

    public float  resolutionScale   = DEFAULT_RESOLUTION_SCALE;
    public int    framesToGenerate  = DEFAULT_FRAMES_TO_GENERATE;
    public int    colorQuality      = DEFAULT_COLOR_QUALITY;
    public int    fisheyeCorrection = DEFAULT_FISHEYE_CORRECTION;
    public int    sifgVersion       = DEFAULT_SIFG_VERSION;
    public boolean touchForwardEnabled = DEFAULT_TOUCH_FORWARD;
    public int    targetFps         = DEFAULT_TARGET_FPS;
    public int    captureMode       = CAPTURE_MODE_FULLSCREEN;

    public int    gfalLinesLargo    = DEFAULT_GFAL_LINES_LARGO;
    public int    gfalLinesAncho    = DEFAULT_GFAL_LINES_ANCHO;
    public int    gfalGridW         = DEFAULT_GFAL_GRID_W;
    public int    gfalGridH         = DEFAULT_GFAL_GRID_H;
    public int    gfalSearchRadius  = DEFAULT_GFAL_SEARCH_RADIUS;
    public boolean gfalNetEnabled   = DEFAULT_GFAL_NET_ENABLED;
    public int    gfalNetAlpha      = DEFAULT_GFAL_NET_ALPHA;
    public int    sifgRegionW       = DEFAULT_SIFG_REGION_W;
    public int    sifgRegionH       = DEFAULT_SIFG_REGION_H;
    public int    asifgNeurons      = DEFAULT_ASIFG_NEURONS;
    public int    asifgThreshold    = DEFAULT_ASIFG_THRESHOLD;

    // ── Servicio ──────────────────────────────────────────────────────────────
    public ScreenCaptureService captureService;
    private boolean      serviceBound = false;
    Intent               resultData;
    int                  resultCode;
    public SharedPreferences    prefs;
    private BatteryHelper batteryHelper;

    private MediaProjectionManager mpManager;

    // ── FPS monitor ───────────────────────────────────────────────────────────
    private final Handler  fpsHandler  = new Handler();
    private final Runnable fpsRunnable = new Runnable() {
        @Override public void run() {
            if (captureService != null && isCapturing) {
                int  fps     = captureService.getFps();
                int  frames  = captureService.getTotalFrames();
                int  dropped = captureService.getDroppedFrames();
                int  effPct  = captureService.getEfficiencyPct();
                long sess    = captureService.getSessionFrames();

                if (tvFpsVal        != null) tvFpsVal.setText(String.valueOf(fps));
                if (tvFramesVal     != null) tvFramesVal.setText(String.valueOf(frames));
                if (tvDroppedVal    != null) tvDroppedVal.setText(String.valueOf(dropped));
                if (tvEfficiencyVal != null) tvEfficiencyVal.setText(effPct + "%");
                if (tvSessionVal    != null) tvSessionVal.setText(String.valueOf(sess));
                if (tvCatMood      != null) tvCatMood.setText(CatMoodHelper.face(effPct));
            }
            fpsHandler.postDelayed(this, 1000);
        }
    };

    // ── ServiceConnection ─────────────────────────────────────────────────────
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            captureService = ((ScreenCaptureService.LocalBinder) service).getService();
            syncServiceSettings();
            serviceBound = true;
            if (resultData != null && resultCode == RESULT_OK) {
                startCaptureInternal();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound   = false;
            captureService = null;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        DisplayMetrics metrics = getRealMetrics();
        screenDensityDpi = metrics.densityDpi;

        prefManager = new PrefManager(this, prefs);
        prefManager.restorePreferences(this);
        prefManager.detectNotchInsets(this);
        prefManager.adjustResolutions(this, metrics);

        setContentView(R.layout.activity_main);
        bindViews();

        uiController  = new UiController(this);
        dialogManager = new DialogManager(this);

        uiController.setupClickListeners();
        uiController.setMode(MODE_PERFORMANCE);
        uiController.loadStats();

        mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startAndBindService();

        batteryHelper = new BatteryHelper(this);
        registerReceiver(batteryHelper, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        fpsHandler.postDelayed(fpsRunnable, 1000);
    }

    @Override
    protected void onDestroy() {
        if (batteryHelper != null) {
            try { unregisterReceiver(batteryHelper); } catch (IllegalArgumentException ignored) {}
        }
        fpsHandler.removeCallbacks(fpsRunnable);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isCapturing) {
            Toast.makeText(this, R.string.toast_stopping, Toast.LENGTH_SHORT).show();
            stopCapture();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics metrics = getRealMetrics();
        prefManager.adjustResolutions(this, metrics);
        uiController.updateResolutionText();
        if (captureService != null && isCapturing) {
            captureService.onOrientationChanged(
                sourceWidth, sourceHeight, targetWidth, targetHeight,
                metrics.densityDpi, topInset, bottomInset);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK) {
            this.resultCode = resultCode;
            this.resultData = data;
            if (serviceBound) startCaptureInternal();
        }
    }


    private void bindViews() {
        btnStartStop = (Button)       findViewById(R.id.btn_start_stop);
        tvSrcRes     = (TextView)     findViewById(R.id.tv_src_res);
        tvDstRes     = (TextView)     findViewById(R.id.tv_dst_res);
        tvScale      = (TextView)     findViewById(R.id.tv_scale);

        pillPerf     = (TextView)     findViewById(R.id.pill_perf);
        pillSifg     = (TextView)     findViewById(R.id.pill_sifg);
        pillAsifg    = (TextView)     findViewById(R.id.pill_asifg);
        pillGfal     = (TextView)     findViewById(R.id.pill_gfal);
        pillCpu      = (TextView)     findViewById(R.id.pill_cpu);
        pillGpu      = (TextView)     findViewById(R.id.pill_gpu);
        gpuToggleRow = (LinearLayout) findViewById(R.id.gpu_toggle_row);

        tvStatus         = (TextView) findViewById(R.id.tv_status);
        tvSifgInfo       = (TextView) findViewById(R.id.tv_sifg_info);
        tvGenerationInfo = (TextView) findViewById(R.id.tv_generation_info);
        tvCatMood        = (TextView) findViewById(R.id.tv_cat_mood);

        tvResVal       = (TextView) findViewById(R.id.tv_calidad_val);
        tvColorVal     = (TextView) findViewById(R.id.tv_calidad_val);   // compartido en popover
        tvFishVal      = (TextView) findViewById(R.id.tv_accesibilidad_val);
        tvTouchVal     = null;      // ahora en submenú accesibilidad
        tvCapModeVal   = null;      // ahora en submenú accesibilidad
        tvFpsTargetVal = (TextView) findViewById(R.id.tv_fps_target_val);

        tvGenVal       = (TextView) findViewById(R.id.tv_sifg_frames_val);


        View cellFps        = findViewById(R.id.cell_fps);
        View cellFrames     = findViewById(R.id.cell_frames);
        View cellDropped    = findViewById(R.id.cell_dropped);
        View cellEfficiency = findViewById(R.id.cell_efficiency);

        if (cellFps != null) {
            ((TextView) cellFps.findViewById(R.id.tv_metric_label)).setText(R.string.metric_fps);
            tvFpsVal = (TextView) cellFps.findViewById(R.id.tv_metric_value);
            tvFpsVal.setTextColor(getResources().getColor(R.color.c_fps));
        }
        if (cellFrames != null) {
            ((TextView) cellFrames.findViewById(R.id.tv_metric_label)).setText(R.string.metric_frames);
            tvFramesVal = (TextView) cellFrames.findViewById(R.id.tv_metric_value);
            tvFramesVal.setTextColor(getResources().getColor(R.color.c_green));
        }
        if (cellDropped != null) {
            ((TextView) cellDropped.findViewById(R.id.tv_metric_label)).setText(R.string.metric_dropped);
            tvDroppedVal = (TextView) cellDropped.findViewById(R.id.tv_metric_value);
            tvDroppedVal.setTextColor(getResources().getColor(R.color.c_drop));
        }
        if (cellEfficiency != null) {
            ((TextView) cellEfficiency.findViewById(R.id.tv_metric_label)).setText(R.string.metric_efficiency);
            tvEfficiencyVal = (TextView) cellEfficiency.findViewById(R.id.tv_metric_value);
            tvEfficiencyVal.setTextColor(getResources().getColor(R.color.c_eff));
        }

        tvSessionVal = (TextView) findViewById(R.id.tv_session_val);
    }


    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else                             startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    void startCaptureInternal() {
        if (captureService == null) return;
        syncServiceSettings();
        captureService.startCapture(resultCode, resultData,
            sourceWidth, sourceHeight, targetWidth, targetHeight,
            screenDensityDpi, topInset, bottomInset, captureMode);
        isCapturing = true;
        uiController.onCaptureStarted();
        fpsHandler.postDelayed(fpsRunnable, 1000);
    }

    public void stopCapture() {
        if (captureService != null) captureService.stopCapture();
        isCapturing = false;
        uiController.onCaptureStopped();
        fpsHandler.removeCallbacks(fpsRunnable);
    }

    private void syncServiceSettings() {
        if (captureService == null) return;
        captureService.setMode(currentMode.get());
        captureService.setFramesToGenerate(framesToGenerate);
        captureService.setColorQuality(colorQuality);
        captureService.setFisheyeCorrection(fisheyeCorrection);
        captureService.setSifgVersion(sifgVersion);
        captureService.setTouchForwardEnabled(touchForwardEnabled);
        captureService.setTargetFps(targetFps);
        captureService.setGfalSearchRadius(gfalSearchRadius);
        captureService.setGfalLines(gfalLinesLargo, gfalLinesAncho);
        captureService.setGfalGrid(gfalGridW, gfalGridH);
        captureService.setGfalNetEnabled(gfalNetEnabled);
        captureService.setGfalNetAlpha(gfalNetAlpha);
    }


    public int  getCurrentMode()          { return currentMode.get(); }
    public void setCurrentMode(int mode)  { currentMode.set(mode); }
    public boolean isCapturing()          { return isCapturing; }
    public int  getSourceWidth()          { return sourceWidth; }
    public int  getSourceHeight()         { return sourceHeight; }
    public int  getTargetWidth()          { return targetWidth; }
    public int  getTargetHeight()         { return targetHeight; }
    public void setSourceWidth(int v)     { sourceWidth  = v; }
    public void setSourceHeight(int v)    { sourceHeight = v; }
    public void setTargetWidth(int v)     { targetWidth  = v; }
    public void setTargetHeight(int v)    { targetHeight = v; }

    public Button       getBtnStartStop()    { return btnStartStop; }
    public TextView     getTvSrcRes()        { return tvSrcRes; }
    public TextView     getTvDstRes()        { return tvDstRes; }
    public TextView     getTvScale()         { return tvScale; }
    public TextView     getPillPerf()        { return pillPerf; }
    public TextView     getPillSifg()        { return pillSifg; }
    public TextView     getPillAsifg()       { return pillAsifg; }
    public TextView     getPillGfal()        { return pillGfal; }
    public TextView     getPillCpu()         { return pillCpu; }
    public TextView     getPillGpu()         { return pillGpu; }
    public LinearLayout getGpuToggleRow()    { return gpuToggleRow; }
    public TextView     getTvStatus()        { return tvStatus; }
    public TextView     getTvSifgInfo()      { return tvSifgInfo; }
    public TextView     getTvGenerationInfo(){ return tvGenerationInfo; }
    public TextView     getTvCatMood()       { return tvCatMood; }
    public TextView     getTvGenVal()        { return tvGenVal; }
    public TextView     getTvColorVal()      { return tvColorVal; }
    public TextView     getTvResVal()        { return tvResVal; }
    public TextView     getTvFishVal()       { return tvFishVal; }
    public TextView     getTvTouchVal()      { return tvTouchVal; }
    public TextView     getTvCapModeVal()    { return tvCapModeVal; }
    public TextView     getTvFpsTargetVal()  { return tvFpsTargetVal; }

    public MediaProjectionManager getMpManager() { return mpManager; }


    public DisplayMetrics getRealMetrics() {
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return m;
    }


    public static class TouchForwardService extends AccessibilityService {

        private static volatile TouchForwardService instance;

        @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
        @Override public void onInterrupt() {}

        @Override
        protected void onServiceConnected() {
            super.onServiceConnected();
            instance = this;
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        }

        @Override
        public void onDestroy() {
            instance = null;
            super.onDestroy();
        }

        public static boolean isRunning() {
            return instance != null;
        }

        public static void dispatchTouch(android.view.MotionEvent event) {
            TouchForwardService svc = instance;
            if (svc != null && Build.VERSION.SDK_INT >= 24) svc.performTouch(event);
        }

        private void performTouch(android.view.MotionEvent event) {
            try {
                android.accessibilityservice.GestureDescription.Builder builder =
                        new android.accessibilityservice.GestureDescription.Builder();
                int pointerCount = event.getPointerCount();
                int action       = event.getActionMasked();

                for (int i = 0; i < pointerCount; i++) {
                    android.graphics.Path path = new android.graphics.Path();
                    path.moveTo(event.getX(i), event.getY(i));

                    android.accessibilityservice.GestureDescription.StrokeDescription stroke;
                    if (action == android.view.MotionEvent.ACTION_UP
                            || action == android.view.MotionEvent.ACTION_POINTER_UP) {
                        stroke = new android.accessibilityservice.GestureDescription
                                .StrokeDescription(path, 0, 1);
                    } else {
                        stroke = new android.accessibilityservice.GestureDescription
                                .StrokeDescription(path, 0, 100, true);
                    }
                    builder.addStroke(stroke);
                }
                dispatchGesture(builder.build(), null, null);
            } catch (Exception e) {
                Log.e(TAG, "Error dispatching touch", e);
            }
        }
    }
}
