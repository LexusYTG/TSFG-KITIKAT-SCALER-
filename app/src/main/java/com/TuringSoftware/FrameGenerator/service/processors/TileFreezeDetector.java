package com.TuringSoftware.FrameGenerator.service.processors;

import java.util.Arrays;

/**
 * TileFreezeDetector — congela tiles cuyo contenido no cambia en los últimos N frames.
 *
 * <p>Para cada tile del grid GFaL mantiene un historial circular de SAD mínimos.
 * Si los últimos {@link #FREEZE_WINDOW} frames tienen SAD ≤ {@link #FREEZE_SAD_THRESHOLD}
 * el tile se marca como "congelado" y {@link FrameUtils#gfalExtractFlow} puede saltárselo,
 * reutilizando el vector de flow anterior en lugar de recalcularlo.
 *
 * <h3>Ventaja de rendimiento</h3>
 * En escenas con fondos estáticos (texto, UI inmóvil, menús) la mayoría de tiles
 * resultan congelados → ahorro proporcional de block-matching.
 *
 * <h3>Integración</h3>
 * <pre>
 *   // Antes de gfalExtractFlow:
 *   freezeDetector.nextFrame();
 *
 *   // Dentro del loop de tiles en gfalExtractFlow (o wrapper):
 *   if (freezeDetector.isFrozen(tileIdx)) {
 *       // reutilizar flowDx/flowDy anteriores, saltar block-matching
 *       continue;
 *   }
 *   // ... block-matching normal ...
 *   freezeDetector.recordSad(tileIdx, bestSAD);
 * </pre>
 */
public final class TileFreezeDetector {

    // ── Configuración ─────────────────────────────────────────────────────────

    /** Número de frames consecutivos sin movimiento para congelar un tile. */
    public static final int FREEZE_WINDOW = 4;

    /**
     * SAD máximo (sobre parche 3×3 mono) por debajo del cual un tile se considera
     * "sin movimiento". 9 píxeles × 255 max = 2295; usamos ~2% → ~46.
     * Ajusta si quieres más/menos agresividad.
     */
    public static final int FREEZE_SAD_THRESHOLD = 40;

    // ── Estado interno ────────────────────────────────────────────────────────

    private final int tileCount;

    /**
     * Historial circular de SAD por tile.
     * sadHistory[t][f] = SAD del tile t en el frame f del historial.
     */
    private final int[][] sadHistory;

    /** Puntero de escritura actual en el historial circular. */
    private int frameHead = 0;

    /** Frames ya registrados (satura en FREEZE_WINDOW). */
    private int framesFilled = 0;

    /** Cache de estado frozen calculado al inicio de cada frame. */
    private final boolean[] frozenCache;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TileFreezeDetector(int tileCount) {
        this.tileCount  = tileCount;
        this.sadHistory = new int[tileCount][FREEZE_WINDOW];
        this.frozenCache = new boolean[tileCount];
        reset();
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /** Reinicia el historial completo (p. ej. al cambiar de resolución). */
    public void reset() {
        for (int t = 0; t < tileCount; t++) Arrays.fill(sadHistory[t], Integer.MAX_VALUE);
        Arrays.fill(frozenCache, false);
        frameHead   = 0;
        framesFilled = 0;
    }

    /**
     * Debe llamarse al inicio de cada frame, antes de procesar tiles.
     * Avanza el puntero del historial y actualiza el cache de tiles congelados.
     */
    public void nextFrame() {
        frameHead = (frameHead + 1) % FREEZE_WINDOW;
        if (framesFilled < FREEZE_WINDOW) framesFilled++;

        // Invalidar SAD del slot que vamos a sobreescribir
        for (int t = 0; t < tileCount; t++) {
            sadHistory[t][frameHead] = Integer.MAX_VALUE;
        }

        // Recalcular frozenCache sólo si tenemos historial completo
        if (framesFilled >= FREEZE_WINDOW) {
            for (int t = 0; t < tileCount; t++) {
                boolean frozen = true;
                for (int f = 0; f < FREEZE_WINDOW; f++) {
                    if (sadHistory[t][f] > FREEZE_SAD_THRESHOLD) {
                        frozen = false;
                        break;
                    }
                }
                frozenCache[t] = frozen;
            }
        } else {
            Arrays.fill(frozenCache, false);
        }
    }

    /**
     * Registra el SAD mínimo obtenido en block-matching para el tile {@code tileIdx}
     * en el frame actual. Llamar después de calcular bestSAD.
     */
    public void recordSad(int tileIdx, int bestSAD) {
        sadHistory[tileIdx][frameHead] = bestSAD;
    }

    /**
     * Devuelve {@code true} si el tile debe congelarse este frame.
     * Si es {@code true}, el caller puede reutilizar el flow anterior y saltar
     * el block-matching.
     */
    public boolean isFrozen(int tileIdx) {
        return frozenCache[tileIdx];
    }

    /**
     * Descongela un tile inmediatamente (útil cuando se detecta un cambio abrupto
     * a nivel de frame completo, p. ej. cambio de escena).
     */
    public void thaw(int tileIdx) {
        Arrays.fill(sadHistory[tileIdx], Integer.MAX_VALUE);
        frozenCache[tileIdx] = false;
    }

    /** Descongela todos los tiles (útil tras un corte de escena detectado). */
    public void thawAll() {
        reset();
    }

    // ── Estadísticas (debug / UI) ─────────────────────────────────────────────

    /** Devuelve cuántos tiles están actualmente congelados. */
    public int frozenCount() {
        int n = 0;
        for (boolean f : frozenCache) if (f) n++;
        return n;
    }
}
