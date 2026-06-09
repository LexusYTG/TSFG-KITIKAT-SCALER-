package com.TuringSoftware.FrameGenerator.ui;
import com.TuringSoftware.FrameGenerator.*;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.TuringSoftware.FrameGenerator.AppConstants.*;
import com.TuringSoftware.FrameGenerator.utils.PermissionHelper;

/**
 * Controla el estado visual de la UI: pills de modo, status card,
 * tiles de config, botón start/stop.
 * No tiene lógica de negocio ni accede al servicio directamente.
 */
public class UiController {

    private final MainActivity act;

    public UiController(MainActivity act) {
        this.act = act;
    }

    // ── Setup de click listeners ──────────────────────────────────────────────

    public void setupClickListeners() {
        act.getPillPerf().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(MODE_PERFORMANCE); }
        });
        act.getPillSifg().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act.dialogManager.showSIFgVersionDialog(); }
        });
        act.getPillAsifg().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(MODE_ASIFG); }
        });
        act.getPillGfal().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(MODE_GFAL); }
        });

        act.getPillCpu().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int m = act.getCurrentMode();
                if (AppConstants.isGpuMode(m)) setMode(AppConstants.toCpuMode(m));
            }
        });
        act.getPillGpu().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int m = act.getCurrentMode();
                if (!AppConstants.isGpuMode(m)) setMode(AppConstants.toGpuMode(m));
            }
        });

        act.getBtnStartStop().setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (act.isCapturing()) {
                    act.stopCapture();
                } else {
                    PermissionHelper.requestCapture(act);
                }
            }
        });

        // ── Tiles universales ─────────────────────────────────────────────────
        act.findViewById(R.id.tile_calidad).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act.dialogManager.showCalidadDialog(); }
        });
        act.findViewById(R.id.tile_accesibilidad).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act.dialogManager.showAccesibilidadDialog(); }
        });
        act.findViewById(R.id.tile_fps).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act.dialogManager.showTargetFpsDialog(); }
        });

        // ── Tiles SIFG ────────────────────────────────────────────────────────
        View tileSifgFrames = act.findViewById(R.id.tile_sifg_frames);
        if (tileSifgFrames != null) {
            tileSifgFrames.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showFrameGenerationDialog(); }
            });
        }
        View tileSifgRegion = act.findViewById(R.id.tile_sifg_region);
        if (tileSifgRegion != null) {
            tileSifgRegion.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showSifgRegionDialog(); }
            });
        }

        // ── Tiles ASIFG ───────────────────────────────────────────────────────
        View tileAsifgNeurons = act.findViewById(R.id.tile_asifg_neurons);
        if (tileAsifgNeurons != null) {
            tileAsifgNeurons.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showAsifgNeuronsDialog(); }
            });
        }
        View tileAsifgThreshold = act.findViewById(R.id.tile_asifg_threshold);
        if (tileAsifgThreshold != null) {
            tileAsifgThreshold.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showAsifgThresholdDialog(); }
            });
        }

        // ── Tiles GFAL ────────────────────────────────────────────────────────
        View tileGfalLargo = act.findViewById(R.id.tile_gfal_lines_largo);
        if (tileGfalLargo != null) {
            tileGfalLargo.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showGfalLargoDialog(); }
            });
        }
        View tileGfalAncho = act.findViewById(R.id.tile_gfal_lines_ancho);
        if (tileGfalAncho != null) {
            tileGfalAncho.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showGfalAnchoDialog(); }
            });
        }
        View tileGfalGrid = act.findViewById(R.id.tile_gfal_grid);
        if (tileGfalGrid != null) {
            tileGfalGrid.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showGfalGridDialog(); }
            });
        }
        View tileGfalRadius = act.findViewById(R.id.tile_gfal_radius);
        if (tileGfalRadius != null) {
            tileGfalRadius.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { act.dialogManager.showGfalRadiusDialog(); }
            });
        }
    }

    // ── Modo ─────────────────────────────────────────────────────────────────

    public void setMode(int mode) {
        act.setCurrentMode(mode);

        int accentColor;
        String statusLabel;
        String sifgInfo = "";

        switch (mode) {
            case MODE_SIFG1:
                accentColor = color(R.color.c_green);
                statusLabel = "Modo SIFg v" + (act.sifgVersion + 1) + " — listo";
                sifgInfo    = "Interpolación predictiva de " + act.framesToGenerate + " frames";
                break;
            case MODE_GPU:
                accentColor = color(R.color.c_green);
                statusLabel = "Modo SIFg GPU — listo";
                sifgInfo    = "Aceleración RenderScript";
                break;
            case MODE_ASIFG:
                accentColor = color(R.color.c_violet);
                statusLabel = "Modo ASIFg ★ — listo";
                sifgInfo    = "Red neuronal adaptativa · " + act.asifgNeurons + " neuronas";
                break;
            case MODE_ASIFG_GPU:
                accentColor = color(R.color.c_violet);
                statusLabel = "Modo ASIFg GPU ★ — listo";
                sifgInfo    = "Red neuronal + RenderScript";
                break;
            case MODE_GFAL:
                accentColor = color(R.color.c_green);
                statusLabel = "Modo GFaL v1 — listo";
                sifgInfo    = "Análisis de líneas · " + act.gfalLinesLargo + "L×" + act.gfalLinesAncho + "A " + act.gfalGridW + "×" + act.gfalGridH;
                break;
            default: // MODE_PERFORMANCE
                accentColor = color(R.color.c_perf);
                statusLabel = "Modo Rendimiento — listo";
                break;
        }

        updateStatusCard(statusLabel, sifgInfo, accentColor);
        updateModePills(mode);
        updateGpuToggle(mode, accentColor);
        updateModeGrid(mode);   // ← muestra el grid correcto
    }

    // ── Grid dinámico por modo ────────────────────────────────────────────────

    private void updateModeGrid(int mode) {
        View gridSifg  = act.findViewById(R.id.grid_sifg);
        View gridAsifg = act.findViewById(R.id.grid_asifg);
        View gridGfal  = act.findViewById(R.id.grid_gfal);

        boolean isSifg  = mode == MODE_SIFG1 || mode == MODE_GPU;
        boolean isAsifg = mode == MODE_ASIFG  || mode == MODE_ASIFG_GPU;
        boolean isGfal  = mode == MODE_GFAL;

        if (gridSifg  != null) gridSifg.setVisibility (isSifg  ? View.VISIBLE : View.GONE);
        if (gridAsifg != null) gridAsifg.setVisibility(isAsifg ? View.VISIBLE : View.GONE);
        if (gridGfal  != null) gridGfal.setVisibility (isGfal  ? View.VISIBLE : View.GONE);
    }

    // ── Status card ───────────────────────────────────────────────────────────

    private void updateStatusCard(String status, String info, int accentColor) {
        TextView tvStatus = act.getTvStatus();
        TextView tvInfo   = act.getTvSifgInfo();
        if (tvStatus != null) tvStatus.setText(status);
        if (tvInfo   != null) {
            tvInfo.setText(info);
            tvInfo.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
        }
        View card = act.findViewById(R.id.card_status);
        if (card != null && card.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) card.getBackground()).setStroke(dp(1), accentColor);
        }
        View accent = act.findViewById(R.id.status_accent);
        if (accent != null && accent.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) accent.getBackground()).setColor(accentColor);
        }
    }

    // ── Pills de modo ─────────────────────────────────────────────────────────

    private void updateModePills(int mode) {
        boolean isSifg  = mode == MODE_SIFG1 || mode == MODE_GPU;
        boolean isAsifg = mode == MODE_ASIFG  || mode == MODE_ASIFG_GPU;
        boolean isPerf  = mode == MODE_PERFORMANCE;
        boolean isGfal  = mode == MODE_GFAL;

        applyPill(act.getPillPerf(),  isPerf,  color(R.color.c_perf),
                  color(R.color.c_perf_dim),   color(R.color.c_txt_pri));
        applyPill(act.getPillSifg(),  isSifg,  color(R.color.c_green),
                  color(R.color.c_green_dim),  color(R.color.c_txt_pri));
        applyPill(act.getPillAsifg(), isAsifg, color(R.color.c_violet),
                  color(R.color.c_violet_dim), color(R.color.c_txt_pri));
        applyPill(act.getPillGfal(),  isGfal,  color(R.color.c_green),
                  color(R.color.c_green_dim),  color(R.color.c_txt_pri));
    }

    private void applyPill(TextView pill, boolean active,
                           int borderColor, int fillColor, int textActive) {
        if (pill == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        if (active) {
            gd.setColor(fillColor);
            gd.setStroke(dp(1), borderColor);
            pill.setTextColor(textActive);
        } else {
            gd.setColor(0x00000000);
            gd.setStroke(dp(1), color(R.color.c_border));
            pill.setTextColor(color(R.color.c_txt_sec));
        }
        pill.setBackground(gd);
    }

    // ── Toggle CPU/GPU ────────────────────────────────────────────────────────

    private void updateGpuToggle(int mode, int accentColor) {
        LinearLayout row = act.getGpuToggleRow();
        if (row == null) return;
        boolean showToggle = AppConstants.modeSupportsGpuToggle(mode);
        row.setVisibility(showToggle ? View.VISIBLE : View.GONE);

        if (!showToggle) return;
        boolean isGpu = AppConstants.isGpuMode(mode);

        TextView cpu = act.getPillCpu();
        GradientDrawable gdCpu = new GradientDrawable();
        gdCpu.setCornerRadius(dp(10));
        if (!isGpu) { gdCpu.setColor(color(R.color.c_perf_dim)); gdCpu.setStroke(dp(1), color(R.color.c_perf)); }
        else        { gdCpu.setColor(0x00000000); gdCpu.setStroke(dp(1), color(R.color.c_border)); }
        cpu.setBackground(gdCpu);
        cpu.setTextColor(isGpu ? color(R.color.c_txt_sec) : color(R.color.c_txt_pri));

        TextView gpu = act.getPillGpu();
        GradientDrawable gdGpu = new GradientDrawable();
        gdGpu.setCornerRadius(dp(10));
        if (isGpu) { gdGpu.setColor(color(R.color.c_gpu_dim)); gdGpu.setStroke(dp(1), color(R.color.c_gpu)); }
        else       { gdGpu.setColor(0x00000000); gdGpu.setStroke(dp(1), color(R.color.c_border)); }
        gpu.setBackground(gdGpu);
        gpu.setTextColor(isGpu ? color(R.color.c_txt_pri) : color(R.color.c_txt_sec));
    }

    // ── Resolución ───────────────────────────────────────────────────────────

    public void updateResolutionText() {
        TextView src   = act.getTvSrcRes();
        TextView dst   = act.getTvDstRes();
        TextView scale = act.getTvScale();
        TextView resVal= act.getTvResVal();
        if (src   != null) src.setText(act.getSourceWidth() + "×" + act.getSourceHeight());
        if (dst   != null) dst.setText(act.getTargetWidth() + "×" + act.getTargetHeight());
        String scaleStr = (int)(act.resolutionScale * 100) + "%";
        if (scale  != null) scale.setText(scaleStr);
        if (resVal != null) resVal.setText(scaleStr);
    }

    // ── Start / Stop ─────────────────────────────────────────────────────────

    public void onCaptureStarted() {
        Button btn = act.getBtnStartStop();
        if (btn != null) {
            btn.setText(R.string.btn_stop);
            btn.setBackgroundResource(R.drawable.bg_btn_stop);
        }
        TextView tvStatus = act.getTvStatus();
        if (tvStatus != null) tvStatus.setText(R.string.status_capturing);
    }

    public void onCaptureStopped() {
        Button btn = act.getBtnStartStop();
        if (btn != null) {
            btn.setText(R.string.btn_start);
            btn.setBackgroundResource(R.drawable.bg_btn_start);
        }
        setMode(act.getCurrentMode());
    }

    // ── Tiles de config ───────────────────────────────────────────────────────

    public void refreshConfigTiles() {
        TextView tvCalidad = act.findViewById(R.id.tv_calidad_val);
        if (tvCalidad != null) {
            tvCalidad.setText((int)(act.resolutionScale * 100) + "% · " + colorQualityLabel());
        }

        TextView tvAcc = act.findViewById(R.id.tv_accesibilidad_val);
        if (tvAcc != null) {
            int f = act.fisheyeCorrection;
            String fishStr = f == 0 ? "Fish:OFF" : "Fish:" + (f > 0 ? "+" : "") + f;
            String touchStr = act.touchForwardEnabled ? "Touch:ON" : "Touch:OFF";
            String capStr   = act.captureMode == CAPTURE_MODE_SINGLE_APP ? "Cap:APP" : "Cap:FULL";
            tvAcc.setText(fishStr + " · " + touchStr + " · " + capStr);
        }

        if (act.getTvFpsTargetVal() != null) act.getTvFpsTargetVal().setText(act.targetFps + " fps");

        TextView tvSifgFrames = act.findViewById(R.id.tv_sifg_frames_val);
        if (tvSifgFrames != null) {
            String gen = act.framesToGenerate > 0
                ? act.framesToGenerate + "× (×" + (act.framesToGenerate + 1) + " FPS)"
                : "—";
            tvSifgFrames.setText(gen);
        }
        TextView tvSifgRegion = act.findViewById(R.id.tv_sifg_region_val);
        if (tvSifgRegion != null) {
            tvSifgRegion.setText(act.sifgRegionW + "×" + act.sifgRegionH);
        }

        TextView tvNeurons = act.findViewById(R.id.tv_asifg_neurons_val);
        if (tvNeurons != null) tvNeurons.setText(String.valueOf(act.asifgNeurons));
        TextView tvThresh = act.findViewById(R.id.tv_asifg_threshold_val);
        if (tvThresh != null) tvThresh.setText(act.asifgThreshold + "%");

        TextView tvLargo = act.findViewById(R.id.tv_gfal_lines_largo_val);
        if (tvLargo != null) tvLargo.setText(String.valueOf(act.gfalLinesLargo));
        TextView tvAncho = act.findViewById(R.id.tv_gfal_lines_ancho_val);
        if (tvAncho != null) tvAncho.setText(String.valueOf(act.gfalLinesAncho));
        TextView tvGrid = act.findViewById(R.id.tv_gfal_grid_val);
        if (tvGrid != null) tvGrid.setText(act.gfalGridW + "×" + act.gfalGridH);
        TextView tvRadius = act.findViewById(R.id.tv_gfal_radius_val);
        if (tvRadius != null) tvRadius.setText(act.gfalSearchRadius + " px");
    }

    public void loadStats() {
        refreshConfigTiles();
        updateResolutionText();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String colorQualityLabel() {
        switch (act.colorQuality) {
            case 0:  return "RGB_565";
            case 1:  return "ARGB_8888";
            default: return "YUV";
        }
    }

    private int color(int resId) {
        return act.getResources().getColor(resId);
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
