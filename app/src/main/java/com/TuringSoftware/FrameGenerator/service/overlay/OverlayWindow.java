package com.TuringSoftware.FrameGenerator.service.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.TuringSoftware.FrameGenerator.MainActivity;
import static com.TuringSoftware.FrameGenerator.AppConstants.TAG;

/**
 * Gestiona la ventana overlay (SurfaceView flotante sobre las demás apps).
 *
 * <p>Extraído de ScreenCaptureService para separar la responsabilidad de
 * WindowManager de la lógica de captura y procesamiento.
 *
 * <p>Callbacks sobre el estado del Surface se notifican a través de
 * {@link SurfaceStateListener}.
 */
public class OverlayWindow {

    /** Notifica cambios de validez del Surface al caller. */
    public interface SurfaceStateListener {
        void onSurfaceValid(SurfaceHolder holder);
        void onSurfaceInvalid();
    }

    private final Context context;
    private final int targetWidth, targetHeight;
    private final boolean touchForwardEnabled;

    private WindowManager windowManager;
    private FrameLayout   overlayContainer;
    private SurfaceView   overlayView;

    private SurfaceStateListener listener;

    public OverlayWindow(Context ctx, int tgtW, int tgtH, boolean touchForward) {
        this.context             = ctx;
        this.targetWidth         = tgtW;
        this.targetHeight        = tgtH;
        this.touchForwardEnabled = touchForward;
    }

    public void setListener(SurfaceStateListener l) { this.listener = l; }

    public SurfaceView getSurfaceView() { return overlayView; }

    // ── Creación ──────────────────────────────────────────────────────────────

    public void create() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int overlayType = (Build.VERSION.SDK_INT >= 26)
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            targetWidth, targetHeight, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE);
        params.alpha = 1.0f;
        params.gravity = Gravity.TOP | Gravity.LEFT;

        overlayContainer = new FrameLayout(context);
        overlayContainer.setBackgroundColor(Color.BLACK);

        overlayView = new SurfaceView(context);
        overlayView.setZOrderOnTop(true);
        overlayView.setZOrderMediaOverlay(false);
        overlayView.getHolder().setFormat(PixelFormat.OPAQUE);

        overlayContainer.addView(overlayView,
            new FrameLayout.LayoutParams(targetWidth, targetHeight));

        overlayView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h)  { notifyValid(h); drawBlack(h); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) { notifyValid(h); drawBlack(h); }
            @Override public void surfaceDestroyed(SurfaceHolder h) { if (listener != null) listener.onSurfaceInvalid(); }
        });

        overlayContainer.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (touchForwardEnabled && MainActivity.TouchForwardService.isRunning()) {
                    MainActivity.TouchForwardService.dispatchTouch(event);
                    return true;
                }
                return false;
            }
        });

        try { windowManager.addView(overlayContainer, params); }
        catch (Exception e) { Log.e(TAG, "Error adding overlay window", e); }
    }

    // ── Redimensionado en giro ─────────────────────────────────────────────────

    public void resize(int newTgtW, int newTgtH) {
        if (windowManager == null || overlayContainer == null) return;
        try {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) overlayContainer.getLayoutParams();
            if (p == null) return;
            p.width = newTgtW; p.height = newTgtH;
            windowManager.updateViewLayout(overlayContainer, p);
            if (overlayView != null) {
                overlayView.setLayoutParams(new FrameLayout.LayoutParams(newTgtW, newTgtH));
            }
        } catch (Exception e) { Log.e(TAG, "Error resizing overlay", e); }
    }

    // ── Eliminación ───────────────────────────────────────────────────────────

    public void remove(android.os.Handler mainHandler) {
        if (windowManager == null || overlayContainer == null) return;
        final WindowManager wm = windowManager;
        final FrameLayout   oc = overlayContainer;
        windowManager = null; overlayContainer = null; overlayView = null;
        Runnable r = new Runnable() { @Override public void run() {
            try { wm.removeView(oc); } catch (Exception e) { Log.e(TAG, "Error removing overlay", e); }
        }};
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void notifyValid(SurfaceHolder h) { if (listener != null) listener.onSurfaceValid(h); }

    private void drawBlack(SurfaceHolder holder) {
        Canvas c = null;
        try {
            c = holder.lockCanvas(null);
            if (c != null) c.drawColor(Color.BLACK);
        } catch (Exception e) { Log.e(TAG, "Error drawing black", e); }
        finally {
            if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) {}
        }
    }
}
