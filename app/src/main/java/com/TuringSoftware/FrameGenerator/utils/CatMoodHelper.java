package com.TuringSoftware.FrameGenerator.utils;
import com.TuringSoftware.FrameGenerator.AppConstants;

public final class CatMoodHelper {
    private CatMoodHelper() {}

    public static String face(int effPct) {
        if      (effPct >= 95) return "( ●ω● )";
        else if (effPct >= 80) return "( ´ω` )";
        else if (effPct >= 60) return "( •ω• )";
        else if (effPct >= 40) return "( ≥ω≤ )";
        else                   return "( ×ω× )";
    }
}
