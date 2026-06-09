package com.TuringSoftware.FrameGenerator.ui;
import com.TuringSoftware.FrameGenerator.*;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;
import com.TuringSoftware.FrameGenerator.utils.PermissionHelper;

/**
 * Todos los diálogos de configuración del usuario.
 * No construye UI permanente — solo AlertDialogs.
 */
public class DialogManager {

    private final MainActivity act;

    public DialogManager(MainActivity act) {
        this.act = act;
    }

    // ── SIFg version ─────────────────────────────────────────────────────────

    public void showSIFgVersionDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Selecciona versión SIFg")
            .setItems(
                new String[]{"v1.0 (Directo)", "v1.1 (Interpolado rápido)"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        act.sifgVersion = which;
                        act.prefs.edit().putInt(PREF_SIFG_VERSION, act.sifgVersion).apply();
                        act.uiController.setMode(MODE_SIFG1);
                    }
                })
            .show();
    }

    // ── Fisheye ───────────────────────────────────────────────────────────────

    public void showFisheyeDialog() {
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final SeekBar  seekBar    = new SeekBar(act);
        final TextView valueLabel = new TextView(act);
        seekBar.setMax(200);
        seekBar.setProgress(act.fisheyeCorrection + 100);
        valueLabel.setText(fisheyeLabel(act.fisheyeCorrection));
        valueLabel.setGravity(Gravity.CENTER);
        valueLabel.setPadding(0, 16, 0, 0);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                valueLabel.setText(fisheyeLabel(p - 100));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb)  {}
        });

        layout.addView(seekBar);
        layout.addView(valueLabel);

        new AlertDialog.Builder(act)
            .setTitle("Corrección Efecto Ojo de Pez")
            .setView(layout)
            .setPositiveButton(R.string.dialog_apply, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.fisheyeCorrection = seekBar.getProgress() - 100;
                    act.prefs.edit().putInt(PREF_FISHEYE_CORRECTION, act.fisheyeCorrection).apply();
                    TextView tv = act.getTvFishVal();
                    if (tv != null) {
                        int f = act.fisheyeCorrection;
                        tv.setText(f == 0 ? "OFF" : (f > 0 ? "+" : "") + f);
                    }
                    if (act.captureService != null && act.isCapturing()) {
                        act.captureService.setFisheyeCorrection(act.fisheyeCorrection);
                    }
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private static String fisheyeLabel(int v) {
        return "Factor: " + (v > 0 ? "+" : "") + v;
    }

    // ── Resolución ────────────────────────────────────────────────────────────

    public void showResolutionDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Resolución de captura")
            .setItems(
                new String[]{"25%  (Máx velocidad)", "50%  (Balance)", "75%  (Más calidad)"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        float[] scales = {0.25f, 0.5f, 0.75f};
                        act.resolutionScale = scales[which];
                        act.prefs.edit().putFloat(PREF_RESOLUTION_SCALE, act.resolutionScale).apply();
                        DisplayMetrics metrics = act.getRealMetrics();
                        act.prefManager.adjustResolutions(act, metrics);
                        act.uiController.updateResolutionText();
                        if (act.captureService != null && act.isCapturing()) {
                            act.captureService.updateResolution(
                                act.getSourceWidth(), act.getSourceHeight(),
                                act.getTargetWidth(), act.getTargetHeight());
                            act.captureService.setResolutionScale(act.resolutionScale);
                        }
                    }
                })
            .show();
    }

    // ── Frame generation ──────────────────────────────────────────────────────

    public void showFrameGenerationDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Frames a generar entre frames reales")
            .setItems(
                new String[]{"1  (2× FPS)", "2  (3× FPS)", "3  (4× FPS)", "4  (5× FPS)"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int[] values = {1, 2, 3, 4};
                        act.framesToGenerate = values[which];
                        act.prefs.edit().putInt(PREF_FRAMES_TO_GENERATE, act.framesToGenerate).apply();
                        TextView tv = act.getTvGenVal();
                        if (tv != null) tv.setText(act.framesToGenerate + "× (×" + (act.framesToGenerate + 1) + " FPS)");
                        if (act.captureService != null && act.isCapturing()) {
                            act.captureService.setFramesToGenerate(act.framesToGenerate);
                        }
                    }
                })
            .show();
    }

    // ── Color quality ─────────────────────────────────────────────────────────

    public void showColorQualityDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Calidad de color")
            .setItems(
                new String[]{"RGB_565  (rápido)", "ARGB_8888  (completo)", "YUV  (mínimo)"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        act.colorQuality = which;
                        act.prefs.edit().putInt(PREF_COLOR_QUALITY, act.colorQuality).apply();
                        TextView tv = act.getTvColorVal();
                        if (tv != null) tv.setText(colorLabel(which));
                        if (act.captureService != null && act.isCapturing()) {
                            act.captureService.setColorQuality(act.colorQuality);
                        }
                    }
                })
            .show();
    }

    private static String colorLabel(int q) {
        switch (q) {
            case 1:  return "ARGB_8888";
            case 2:  return "YUV";
            default: return "RGB_565";
        }
    }

    // ── Touch forward ─────────────────────────────────────────────────────────

    public void showTouchForwardDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Reenvío de toques")
            .setItems(
                new String[]{"Desactivado (recomendado)", "Activado (requiere accesibilidad)"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        act.touchForwardEnabled = which == 1;
                        act.prefs.edit().putBoolean(PREF_TOUCH_FORWARD, act.touchForwardEnabled).apply();
                        TextView tv = act.getTvTouchVal();
                        if (tv != null) tv.setText(act.touchForwardEnabled ? "ON" : "OFF");
                        if (act.touchForwardEnabled && !PermissionHelper.isAccessibilityEnabled(act)) {
                            PermissionHelper.requestAccessibility(act);
                        }
                    }
                })
            .show();
    }

    // ── Capture mode ──────────────────────────────────────────────────────────

    public void showCaptureModeDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Modo de captura")
            .setItems(
                new String[]{"Pantalla completa", "App específica (API 34+)"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        act.captureMode = which;
                        act.prefs.edit().putInt(PREF_CAPTURE_MODE, act.captureMode).apply();
                        TextView tv = act.getTvCapModeVal();
                        if (tv != null) tv.setText(which == CAPTURE_MODE_SINGLE_APP ? "APP" : "FULL");
                    }
                })
            .show();
    }

    // ── Target FPS ────────────────────────────────────────────────────────────

    public void showTargetFpsDialog() {
        final String[] labels = new String[FPS_OPTIONS.length];
        for (int i = 0; i < FPS_OPTIONS.length; i++) labels[i] = FPS_OPTIONS[i] + " fps";
        new AlertDialog.Builder(act)
            .setTitle("FPS objetivo")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.targetFps = FPS_OPTIONS[which];
                    act.prefs.edit().putInt(PREF_TARGET_FPS, act.targetFps).apply();
                    TextView tv = act.getTvFpsTargetVal();
                    if (tv != null) tv.setText(act.targetFps + " fps");
                    if (act.captureService != null) {
                        act.captureService.setTargetFps(act.targetFps);
                    }
                }
            })
            .show();
    }


    public void showCalidadDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Calidad")
            .setItems(
                new String[]{"Resolución de captura", "Calidad de color"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) showResolutionDialog();
                        else            showColorQualityDialog();
                    }
                })
            .show();
    }


    public void showAccesibilidadDialog() {
        new AlertDialog.Builder(act)
            .setTitle("Accesibilidad")
            .setItems(
                new String[]{"Corrección ojo de pez", "Reenvío de toques", "Modo de captura"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: showFisheyeDialog();     break;
                            case 1: showTouchForwardDialog(); break;
                            case 2: showCaptureModeDialog();  break;
                        }
                    }
                })
            .show();
    }


    public void showSifgRegionDialog() {
        final String[] labels = new String[SIFG_REGION_OPTIONS.length];
        for (int i = 0; i < SIFG_REGION_OPTIONS.length; i++) {
            labels[i] = SIFG_REGION_OPTIONS[i] + "×" + SIFG_REGION_OPTIONS[i] + " bloques";
        }
        new AlertDialog.Builder(act)
            .setTitle("Región de análisis SIFg")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.sifgRegionW = SIFG_REGION_OPTIONS[which];
                    act.sifgRegionH = SIFG_REGION_OPTIONS[which];
                    act.prefs.edit()
                        .putInt(PREF_SIFG_REGION_W, act.sifgRegionW)
                        .putInt(PREF_SIFG_REGION_H, act.sifgRegionH)
                        .apply();
                    TextView tv = act.findViewById(R.id.tv_sifg_region_val);
                    if (tv != null) tv.setText(act.sifgRegionW + "×" + act.sifgRegionH);
                }
            })
            .show();
    }


    public void showAsifgNeuronsDialog() {
        final String[] labels = new String[ASIFG_NEURON_OPTIONS.length];
        for (int i = 0; i < ASIFG_NEURON_OPTIONS.length; i++) {
            labels[i] = ASIFG_NEURON_OPTIONS[i] + " neuronas";
        }
        new AlertDialog.Builder(act)
            .setTitle("Neuronas de la red ASIFg")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.asifgNeurons = ASIFG_NEURON_OPTIONS[which];
                    act.prefs.edit().putInt(PREF_ASIFG_NEURONS, act.asifgNeurons).apply();
                    TextView tv = act.findViewById(R.id.tv_asifg_neurons_val);
                    if (tv != null) tv.setText(String.valueOf(act.asifgNeurons));
                    if (act.getCurrentMode() == MODE_ASIFG || act.getCurrentMode() == MODE_ASIFG_GPU) {
                        act.uiController.setMode(act.getCurrentMode());
                    }
                }
            })
            .show();
    }

    public void showAsifgThresholdDialog() {
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final SeekBar  seekBar    = new SeekBar(act);
        final TextView valueLabel = new TextView(act);
        seekBar.setMax(100);
        seekBar.setProgress(act.asifgThreshold);
        valueLabel.setText("Umbral: " + act.asifgThreshold + "%");
        valueLabel.setGravity(Gravity.CENTER);
        valueLabel.setPadding(0, 16, 0, 0);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                valueLabel.setText("Umbral: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb)  {}
        });

        layout.addView(seekBar);
        layout.addView(valueLabel);

        new AlertDialog.Builder(act)
            .setTitle("Umbral de activación ASIFg")
            .setView(layout)
            .setPositiveButton(R.string.dialog_apply, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.asifgThreshold = seekBar.getProgress();
                    act.prefs.edit().putInt(PREF_ASIFG_THRESHOLD, act.asifgThreshold).apply();
                    TextView tv = act.findViewById(R.id.tv_asifg_threshold_val);
                    if (tv != null) tv.setText(act.asifgThreshold + "%");
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }


    public void showGfalLargoDialog() {
        final String[] labels = new String[GFAL_LINES_OPTIONS.length];
        for (int i = 0; i < GFAL_LINES_OPTIONS.length; i++) {
            labels[i] = GFAL_LINES_OPTIONS[i] + " líneas  (eje largo)";
        }
        new AlertDialog.Builder(act)
            .setTitle("Líneas GFaL — Largo")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.gfalLinesLargo = GFAL_LINES_OPTIONS[which];
                    act.prefs.edit().putInt(PREF_GFAL_LINES_LARGO, act.gfalLinesLargo).apply();
                    TextView tv = act.findViewById(R.id.tv_gfal_lines_largo_val);
                    if (tv != null) tv.setText(String.valueOf(act.gfalLinesLargo));
                    if (act.getCurrentMode() == MODE_GFAL) {
                        act.uiController.setMode(MODE_GFAL);
                    }
                    if (act.captureService != null && act.isCapturing()) {
                        act.captureService.setGfalLines(act.gfalLinesLargo, act.gfalLinesAncho);
                    }
                }
            })
            .show();
    }

    public void showGfalAnchoDialog() {
        final String[] labels = new String[GFAL_LINES_OPTIONS.length];
        for (int i = 0; i < GFAL_LINES_OPTIONS.length; i++) {
            labels[i] = GFAL_LINES_OPTIONS[i] + " líneas  (eje ancho)";
        }
        new AlertDialog.Builder(act)
            .setTitle("Líneas GFaL — Ancho")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.gfalLinesAncho = GFAL_LINES_OPTIONS[which];
                    act.prefs.edit().putInt(PREF_GFAL_LINES_ANCHO, act.gfalLinesAncho).apply();
                    TextView tv = act.findViewById(R.id.tv_gfal_lines_ancho_val);
                    if (tv != null) tv.setText(String.valueOf(act.gfalLinesAncho));
                    if (act.getCurrentMode() == MODE_GFAL) {
                        act.uiController.setMode(MODE_GFAL);
                    }
                    if (act.captureService != null && act.isCapturing()) {
                        act.captureService.setGfalLines(act.gfalLinesLargo, act.gfalLinesAncho);
                    }
                }
            })
            .show();
    }

    public void showGfalGridDialog() {
        final String[] labels = new String[GFAL_GRID_OPTIONS.length];
        for (int i = 0; i < GFAL_GRID_OPTIONS.length; i++) {
            labels[i] = GFAL_GRID_OPTIONS[i] + "×" + GFAL_GRID_OPTIONS[i] + " celdas";
        }
        new AlertDialog.Builder(act)
            .setTitle("Grilla de análisis GFaL")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.gfalGridW = GFAL_GRID_OPTIONS[which];
                    act.gfalGridH = GFAL_GRID_OPTIONS[which];
                    act.prefs.edit()
                        .putInt(PREF_GFAL_GRID_W, act.gfalGridW)
                        .putInt(PREF_GFAL_GRID_H, act.gfalGridH)
                        .apply();
                    TextView tv = act.findViewById(R.id.tv_gfal_grid_val);
                    if (tv != null) tv.setText(act.gfalGridW + "×" + act.gfalGridH);
                    if (act.getCurrentMode() == MODE_GFAL) {
                        act.uiController.setMode(MODE_GFAL);
                    }
                    if (act.captureService != null && act.isCapturing()) {
                        act.captureService.setGfalGrid(act.gfalGridW, act.gfalGridH);
                    }
                }
            })
            .show();
    }

    public void showGfalRadiusDialog() {
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final SeekBar  seekBar    = new SeekBar(act);
        final TextView valueLabel = new TextView(act);
        seekBar.setMax(60);
        seekBar.setProgress(act.gfalSearchRadius - 4);
        valueLabel.setText("Radio: " + act.gfalSearchRadius + " px");
        valueLabel.setGravity(Gravity.CENTER);
        valueLabel.setPadding(0, 16, 0, 0);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                valueLabel.setText("Radio: " + (p + 4) + " px");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb)  {}
        });

        layout.addView(seekBar);
        layout.addView(valueLabel);

        new AlertDialog.Builder(act)
            .setTitle("Radio de búsqueda GFaL")
            .setView(layout)
            .setPositiveButton(R.string.dialog_apply, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    act.gfalSearchRadius = seekBar.getProgress() + 4;
                    act.prefs.edit().putInt(PREF_GFAL_SEARCH_RADIUS, act.gfalSearchRadius).apply();
                    TextView tv = act.findViewById(R.id.tv_gfal_radius_val);
                    if (tv != null) tv.setText(act.gfalSearchRadius + " px");
                    if (act.captureService != null && act.isCapturing()) {
                        act.captureService.setGfalSearchRadius(act.gfalSearchRadius);
                    }
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }
}
