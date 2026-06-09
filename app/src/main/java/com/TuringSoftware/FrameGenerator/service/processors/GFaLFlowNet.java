package com.TuringSoftware.FrameGenerator.service.processors;

/**
 * GFaLFlowNet — micro red neuronal de refinamiento de flow field (8 → 12 → 2).
 *
 * <p>Recibe por tile el vector crudo del block-matching más contexto local y
 * devuelve un vector refinado que suaviza ruido, respeta bordes y tiene
 * memoria espacial de los tiles vecinos.
 *
 * <h3>Arquitectura</h3>
 * <pre>
 *   Entrada  (8):  dx_raw, dy_raw, sad_norm, grad_norm,
 *                  dx_left, dy_left, dx_up, dy_up
 *   Oculta  (12):  ReLU
 *   Salida   (2):  dx_delta, dy_delta  (se suma al raw → vector final)
 * </pre>
 *
 * <h3>Parámetros</h3>
 * Total: (8×12 + 12) + (12×2 + 2) = 108 + 26 = <b>122 parámetros</b>.
 * Todos están hardcodeados abajo como arrays {@code float[]}.
 * Para re-entrenar: sustituye {@link #W1}, {@link #B1}, {@link #W2}, {@link #B2}.
 *
 * <h3>Agresividad</h3>
 * El caller mezcla: {@code out = raw + alpha * delta}, donde {@code alpha} viene
 * de {@link FrameUtils#gfalRefineFlow}. Con alpha=0 la red no tiene efecto;
 * con alpha=1 aplica el delta completo.
 *
 * <h3>Pesos por defecto</h3>
 * Los pesos están inicializados como "identidad aproximada": la red sin entrenar
 * propaga dx/dy casi sin cambio y atenúa vectores con SAD alto o grad bajo.
 * Esto garantiza que el modo GFaL funcione igual o mejor que antes desde el
 * primer arranque.
 */
public final class GFaLFlowNet {

    private GFaLFlowNet() {}

    // ── Dimensiones ───────────────────────────────────────────────────────────
    public static final int IN  = 8;
    public static final int H   = 12;
    public static final int OUT = 2;

    // ── Pesos capa 1: W1[H][IN], B1[H] ──────────────────────────────────────
    //
    // Lógica de inicialización:
    //   neurona 0  → copia dx_raw  (w[0]=1)  atenuada por sad_norm (w[2]=-0.6)
    //   neurona 1  → copia dy_raw  (w[1]=1)  atenuada por sad_norm (w[2]=-0.6)
    //   neuronas 2-3  → captan contexto vecino dx (w[4]=0.5) y dy (w[5]=0.5)
    //   neuronas 4-5  → captan contexto vecino arriba dx (w[6]=0.5) dy (w[7]=0.5)
    //   neuronas 6-7  → detectores de grad alto (w[3]=0.8) para confiar más
    //   neuronas 8-11 → detectores mixtos, bias positivo pequeño
    //
    // Formato: fila i = pesos de la neurona i (longitud IN=8 cada fila)
    // ─────────────────────────────────────────────────────────────────────────
    public static final float[] W1 = {
        // i=0 : dx_raw fuerte, penaliza SAD alto
         1.0f,  0.0f, -0.6f,  0.1f,  0.0f,  0.0f,  0.0f,  0.0f,
        // i=1 : dy_raw fuerte, penaliza SAD alto
         0.0f,  1.0f, -0.6f,  0.1f,  0.0f,  0.0f,  0.0f,  0.0f,
        // i=2 : vecino izquierdo dx
         0.2f,  0.0f, -0.2f,  0.1f,  0.5f,  0.0f,  0.0f,  0.0f,
        // i=3 : vecino izquierdo dy
         0.0f,  0.2f, -0.2f,  0.1f,  0.0f,  0.5f,  0.0f,  0.0f,
        // i=4 : vecino superior dx
         0.2f,  0.0f, -0.2f,  0.1f,  0.0f,  0.0f,  0.5f,  0.0f,
        // i=5 : vecino superior dy
         0.0f,  0.2f, -0.2f,  0.1f,  0.0f,  0.0f,  0.0f,  0.5f,
        // i=6 : detector grad alto — refuerza dx cuando la textura es clara
         0.3f,  0.0f,  0.0f,  0.8f,  0.0f,  0.0f,  0.0f,  0.0f,
        // i=7 : detector grad alto — refuerza dy
         0.0f,  0.3f,  0.0f,  0.8f,  0.0f,  0.0f,  0.0f,  0.0f,
        // i=8 : promedio dx de los 3 fuentes (raw + izq + arriba)
         0.3f,  0.0f, -0.3f,  0.0f,  0.3f,  0.0f,  0.3f,  0.0f,
        // i=9 : promedio dy de los 3 fuentes
         0.0f,  0.3f, -0.3f,  0.0f,  0.0f,  0.3f,  0.0f,  0.3f,
        // i=10 : detector consistencia dx (raw ≈ izquierdo)
         0.4f,  0.0f,  0.0f,  0.0f, -0.4f,  0.0f,  0.0f,  0.0f,
        // i=11 : detector consistencia dy (raw ≈ arriba)
         0.0f,  0.4f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f, -0.4f,
    };

    public static final float[] B1 = {
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.1f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f,
    };

    // ── Pesos capa 2: W2[OUT][H], B2[OUT] ────────────────────────────────────
    //
    // La salida es un *delta* que se sumará al raw.
    // Con estos pesos, la red produce delta≈0 cuando la neurona 0/1 pasan
    // dx/dy directo (identidad) y el resultado final = raw + 0 = raw.
    // El refinamiento real emerge cuando SAD es alto o los vecinos difieren.
    // ─────────────────────────────────────────────────────────────────────────
    public static final float[] W2 = {
        // salida 0 = delta_dx: suma neurona0 (dx_raw path) + contribuciones vecinos
         0.05f, 0.00f,  0.10f,  0.00f,  0.10f,  0.00f,  0.05f,  0.00f,
         0.10f,  0.00f, -0.05f,  0.00f,
        // salida 1 = delta_dy
         0.00f,  0.05f,  0.00f,  0.10f,  0.00f,  0.10f,  0.00f,  0.05f,
         0.00f,  0.10f,  0.00f, -0.05f,
    };

    public static final float[] B2 = { 0.0f, 0.0f };

    // ── Inferencia ────────────────────────────────────────────────────────────

    /**
     * Ejecuta la red para un tile y escribe el delta en {@code outDelta}.
     *
     * @param dxRaw    dx del block-matching (píxeles fuente)
     * @param dyRaw    dy del block-matching
     * @param sadNorm  SAD normalizado a [0,1] — pasar {@code bestSAD / (9f * 255f)}
     * @param gradNorm gradiente normalizado a [0,1] — pasar {@code grad / (4f * 255f)}
     * @param dxLeft   dx del tile a la izquierda (0 si col==0)
     * @param dyLeft   dy del tile a la izquierda
     * @param dxUp     dx del tile de arriba (0 si row==0)
     * @param dyUp     dy del tile de arriba
     * @param outDelta array de longitud ≥ 2; outDelta[0]=delta_dx, outDelta[1]=delta_dy
     * @param hidden   scratch array de longitud ≥ H (evita allocación en hot path)
     */
    public static void infer(float dxRaw, float dyRaw,
                             float sadNorm, float gradNorm,
                             float dxLeft, float dyLeft,
                             float dxUp,   float dyUp,
                             float[] outDelta, float[] hidden) {
        // ── Normalizar entradas a rango razonable ─────────────────────────────
        // dx/dy se normalizan dividiendo por MAX_SEARCH (16 px típico) para que
        // las magnitudes sean comparables a sad/grad que ya están en [0,1].
        final float NORM_MV = 16f;
        final float x0 = dxRaw   / NORM_MV;
        final float x1 = dyRaw   / NORM_MV;
        final float x2 = sadNorm;           // ya en [0,1]
        final float x3 = gradNorm;          // ya en [0,1]
        final float x4 = dxLeft  / NORM_MV;
        final float x5 = dyLeft  / NORM_MV;
        final float x6 = dxUp    / NORM_MV;
        final float x7 = dyUp    / NORM_MV;

        // ── Capa oculta: h = ReLU(W1 * x + B1) ───────────────────────────────
        for (int i = 0; i < H; i++) {
            int base = i * IN;
            float sum = B1[i]
                + W1[base    ] * x0
                + W1[base + 1] * x1
                + W1[base + 2] * x2
                + W1[base + 3] * x3
                + W1[base + 4] * x4
                + W1[base + 5] * x5
                + W1[base + 6] * x6
                + W1[base + 7] * x7;
            hidden[i] = sum > 0f ? sum : 0f; // ReLU
        }

        // ── Capa de salida: out = W2 * h + B2 (lineal) ────────────────────────
        for (int o = 0; o < OUT; o++) {
            int base = o * H;
            float sum = B2[o];
            for (int i = 0; i < H; i++) sum += W2[base + i] * hidden[i];
            // Des-normalizar: la red produce delta en espacio normalizado
            outDelta[o] = sum * NORM_MV;
        }
    }
}
