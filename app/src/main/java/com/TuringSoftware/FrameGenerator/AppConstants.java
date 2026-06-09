package com.TuringSoftware.FrameGenerator;

public final class AppConstants {

    private AppConstants() {}

    // ── Modos de procesamiento ────────────────────────────────────────────────
    public static final int MODE_PERFORMANCE = 0;
    public static final int MODE_SIFG1       = 1;
    public static final int MODE_ASIFG       = 2;
    public static final int MODE_GPU         = 3;  // GPU via RenderScript (SIFg v1.1 GPU)
    public static final int MODE_ASIFG_GPU   = 4;  // ASIFg con aceleración GPU (RenderScript)
    public static final int MODE_GFAL        = 5;  // GFaL v1 — extrapolación por análisis de líneas

    public static boolean modeSupportsGpuToggle(int mode) {
        return mode == MODE_SIFG1 || mode == MODE_ASIFG
            || mode == MODE_GPU   || mode == MODE_ASIFG_GPU;
    }

    public static int toGpuMode(int mode) {
        if (mode == MODE_SIFG1)     return MODE_GPU;
        if (mode == MODE_ASIFG)     return MODE_ASIFG_GPU;
        return mode; // ya es GPU o no tiene variante
    }

    public static int toCpuMode(int mode) {
        if (mode == MODE_GPU)       return MODE_SIFG1;
        if (mode == MODE_ASIFG_GPU) return MODE_ASIFG;
        return mode;
    }

    public static boolean isGpuMode(int mode) {
        return mode == MODE_GPU || mode == MODE_ASIFG_GPU;
    }

    // ── SharedPreferences ────────────────────────────────────────────────────
    public static final String PREFS_NAME              = "ScreenScalerStats";
    public static final String PREF_RESOLUTION_SCALE   = "resolution_scale";
    public static final String PREF_FRAMES_TO_GENERATE = "frames_to_generate";
    public static final String PREF_COLOR_QUALITY      = "color_quality";
    public static final String PREF_FISHEYE_CORRECTION = "fisheye_correction";
    public static final String PREF_SIFG_VERSION       = "sifg_version";
    public static final String PREF_TOUCH_FORWARD      = "touch_forward_enabled";
    public static final String PREF_TARGET_FPS          = "target_fps";
    public static final String PREF_AVG_FPS_PERFORMANCE = "avg_fps_performance";
    public static final String PREF_AVG_FPS_SIFG1      = "avg_fps_sifg1";
    public static final String PREF_AVG_FPS_ASIFG      = "avg_fps_asifg";
    public static final String PREF_AVG_FPS_GFAL       = "avg_fps_gfal";
    public static final String PREF_GFAL_SEARCH_RADIUS = "gfal_search_radius";
    public static final int    DEFAULT_GFAL_SEARCH_RADIUS = 16;

    // ── Parámetros exclusivos GFaL ────────────────────────────────────────────
    public static final String PREF_GFAL_LINES_LARGO  = "gfal_lines_largo";
    public static final int    DEFAULT_GFAL_LINES_LARGO = 16;
    public static final String PREF_GFAL_LINES_ANCHO  = "gfal_lines_ancho";
    public static final int    DEFAULT_GFAL_LINES_ANCHO = 7;
    public static final int[]  GFAL_LINES_OPTIONS    = {2, 4, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 64};
    public static final String PREF_GFAL_GRID_W      = "gfal_grid_w";
    public static final int    DEFAULT_GFAL_GRID_W    = 4;
    public static final String PREF_GFAL_GRID_H      = "gfal_grid_h";
    public static final int    DEFAULT_GFAL_GRID_H    = 4;
    public static final int[]  GFAL_GRID_OPTIONS     = {2, 4, 8, 16};

    // ── GFaL — refinamiento neuronal ─────────────────────────────────────────
    /** Activa la micro red GFaLFlowNet sobre el flow field. */
    public static final String PREF_GFAL_NET_ENABLED  = "gfal_net_enabled";
    public static final boolean DEFAULT_GFAL_NET_ENABLED = false;

    /**
     * Agresividad del refinamiento neuronal: mezcla entre vector raw y delta de la red.
     * 0.0 = sin efecto (pass-through), 1.0 = delta completo aplicado.
     * Se almacena como int 0–100 (porcentaje) en SharedPreferences.
     */
    public static final String PREF_GFAL_NET_ALPHA    = "gfal_net_alpha";
    public static final int    DEFAULT_GFAL_NET_ALPHA  = 50;  // 0.50 — moderado
    public static final int[]  GFAL_NET_ALPHA_OPTIONS = {0, 10, 25, 50, 75, 100};

    // ── Parámetros exclusivos SIFg ────────────────────────────────────────────
    public static final String PREF_SIFG_REGION_W    = "sifg_region_w";
    public static final int    DEFAULT_SIFG_REGION_W  = 8;
    public static final String PREF_SIFG_REGION_H    = "sifg_region_h";
    public static final int    DEFAULT_SIFG_REGION_H  = 8;
    public static final int[]  SIFG_REGION_OPTIONS   = {4, 8, 16, 32};

    // ── Parámetros exclusivos ASIFg ───────────────────────────────────────────
    public static final String PREF_ASIFG_NEURONS    = "asifg_neurons";
    public static final int    DEFAULT_ASIFG_NEURONS  = 64;
    public static final String PREF_ASIFG_THRESHOLD  = "asifg_threshold";
    public static final int    DEFAULT_ASIFG_THRESHOLD = 50;
    public static final int[]  ASIFG_NEURON_OPTIONS  = {16, 32, 64, 128, 256};

    public static final String PREF_RES_CACHE_P25  = "res_cache_p_25";   // portrait  25%
    public static final String PREF_RES_CACHE_P50  = "res_cache_p_50";   // portrait  50%
    public static final String PREF_RES_CACHE_P75  = "res_cache_p_75";   // portrait  75%
    public static final String PREF_RES_CACHE_L25  = "res_cache_l_25";   // landscape 25%
    public static final String PREF_RES_CACHE_L50  = "res_cache_l_50";   // landscape 50%
    public static final String PREF_RES_CACHE_L75  = "res_cache_l_75";   // landscape 75%

    public static final String PREF_INSET_STATUS_BAR = "inset_status_bar";  // px
    public static final String PREF_INSET_NAV_BAR    = "inset_nav_bar";     // px

    public static final String PREF_CAPTURE_MODE     = "capture_mode";
    public static final int    CAPTURE_MODE_FULLSCREEN  = 0;
    public static final int    CAPTURE_MODE_SINGLE_APP  = 1;

    // ── Valores por defecto ───────────────────────────────────────────────────
    public static final float DEFAULT_RESOLUTION_SCALE  = 0.5f;
    public static final int   DEFAULT_FRAMES_TO_GENERATE = 1;
    public static final int   DEFAULT_COLOR_QUALITY      = 0;
    public static final int   DEFAULT_FISHEYE_CORRECTION = 0;
    public static final int   DEFAULT_SIFG_VERSION       = 0;
    public static final int   DEFAULT_TARGET_FPS         = 60;
    public static final int[] FPS_OPTIONS = {30, 45, 60, 90, 120};
    public static final boolean DEFAULT_TOUCH_FORWARD    = false;

    // ── Request codes ────────────────────────────────────────────────────────
    public static final int REQUEST_CODE_CAPTURE      = 1001;
    public static final int REQUEST_CODE_ACCESSIBILITY = 1002;
    public static final int REQUEST_CODE_OVERLAY       = 1003;

    // ── Tag de log ───────────────────────────────────────────────────────────
    public static final String TAG = "ScreenScaler";
}


