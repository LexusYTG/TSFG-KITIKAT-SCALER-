package com.TuringSoftware.FrameGenerator.capture;

import com.TuringSoftware.FrameGenerator.AppConstants;

public final class CaptureSettings
 {

    public final int   mode;
    public final int   sifgVersion;
    public final float resolutionScale;
    public final int   framesToGenerate;
    public final int   colorQuality;
    public final int   fisheyeCorrection;

    public CaptureSettings(int mode,
                           int sifgVersion,
                           float resolutionScale,
                           int framesToGenerate,
                           int colorQuality,
                           int fisheyeCorrection) {
        this.mode             = mode;
        this.sifgVersion      = sifgVersion;
        this.resolutionScale  = resolutionScale;
        this.framesToGenerate = (mode == AppConstants.MODE_PERFORMANCE)
			? 0
			: Math.max(1, Math.min(4, framesToGenerate));
        this.colorQuality     = colorQuality;
        this.fisheyeCorrection = fisheyeCorrection;
    }

    public static CaptureSettings defaults() {
        return new CaptureSettings(
			AppConstants.MODE_PERFORMANCE,
			AppConstants.DEFAULT_SIFG_VERSION,
			AppConstants.DEFAULT_RESOLUTION_SCALE,
			0,                                       // Performance: sin generación
			AppConstants.DEFAULT_COLOR_QUALITY,
			AppConstants.DEFAULT_FISHEYE_CORRECTION
        );
    }
}


