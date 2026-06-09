package com.TuringSoftware.FrameGenerator.service.pipeline;

import android.content.Context;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.util.Log;

public final class RenderScriptPipeline {

    private final Context ctx;
    private RenderScript          rs;
    private ScriptIntrinsicResize rsResize;
    private Allocation            allocSrc;
    private Allocation            allocTarget;
    private byte[]                targetPingA;
    private byte[]                targetPingB;
    private boolean               pingSlotA = true;
    private byte[]                prevBytes;
    private byte[]                blendBuffer;
    private boolean               ready = false;

    public RenderScriptPipeline(Context ctx) { this.ctx = ctx; }

    public synchronized void init(int sw, int sh, int tw, int th) {
        destroy();
        try {
            rs = RenderScript.create(ctx);
            Type typeSrc = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(sw).setY(sh).create();
            Type typeTgt = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(tw).setY(th).create();
            allocSrc    = Allocation.createTyped(rs, typeSrc, Allocation.USAGE_SCRIPT);
            allocTarget = Allocation.createTyped(rs, typeTgt, Allocation.USAGE_SCRIPT);
            rsResize    = ScriptIntrinsicResize.create(rs);
            rsResize.setInput(allocSrc);
            int tSize   = tw * th * 4;
            targetPingA = new byte[tSize];
            targetPingB = new byte[tSize];
            pingSlotA   = true;
            prevBytes   = null;
            blendBuffer = new byte[tSize];
            ready       = true;
            Log.i("RSPipeline", "RS init OK " + sw + "x" + sh + " -> " + tw + "x" + th);
        } catch (Exception e) {
            Log.e("RSPipeline", "RS init FAILED: " + e.getMessage());
            destroy();
        }
    }

    public synchronized void destroy() {
        ready       = false;
        prevBytes   = null;
        targetPingA = null;
        targetPingB = null;
        safeDestroy(rsResize);    rsResize    = null;
        safeDestroy(allocSrc);    allocSrc    = null;
        safeDestroy(allocTarget); allocTarget = null;
        if (rs != null) { try { rs.destroy(); } catch (Exception ignored) {} rs = null; }
        blendBuffer = null;
    }

    public boolean isReady()               { return ready; }
    public byte[]  getPreviousBytes()      { return prevBytes; }
    public byte[]  getBlendBuffer()        { return blendBuffer; }

    public void uploadSource(byte[] src) {
        if (!ready) return;
        allocSrc.copyFrom(src);
    }

    public void resize() {
        if (!ready) return;
        rsResize.forEach_bicubic(allocTarget);
    }

    public byte[] downloadTargetPing() {
        if (!ready) return null;
        byte[] slot = pingSlotA ? targetPingA : targetPingB;
        pingSlotA = !pingSlotA;
        if (slot == null) return null;
        allocTarget.copyTo(slot);
        return slot;
    }

    public void savePreviousBytes(byte[] real) {
        if (real == null) return;
        if (prevBytes == null || prevBytes.length != real.length) prevBytes = new byte[real.length];
        System.arraycopy(real, 0, prevBytes, 0, real.length);
    }

    private static void safeDestroy(Object o) {
        if (o == null) return;
        try {
            if (o instanceof Allocation)            ((Allocation) o).destroy();
            else if (o instanceof ScriptIntrinsicResize) ((ScriptIntrinsicResize) o).destroy();
        } catch (Exception ignored) {}
    }
}
