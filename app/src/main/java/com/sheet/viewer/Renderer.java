package com.sheet.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Draws the PDF on its own thread so the screen never waits for it.
 *
 * Each page gets a whole-page picture (shown instantly while zooming and panning) and, when
 * zoomed in, a sharp picture of just the part on screen, redrawn continuously as the view moves.
 */
final class Renderer implements Handler.Callback {

    interface Host {
        void onDocOpened(int pages);

        void onDocFailed(String why);

        void onPage(int gen, int index, float w, float h, Bitmap base, float baseScale);

        void onBase(int gen, Bitmap base, float baseScale);

        /** bmp is null when the sharp picture is no longer needed. */
        void onPatch(int gen, Bitmap bmp, RectF rect, float scale);
    }

    private static final int MSG_OPEN = 1, MSG_PAGE = 2, MSG_BASE = 3, MSG_PATCH = 4,
            MSG_CLOSE = 5;

    private final Context ctx;
    private final Host host;
    private final PageView view;
    private final HandlerThread thread;
    private final Handler h;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Goes up every time another page is asked for; older results are thrown away. */
    volatile int gen;

    // Used only on the render thread.
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private PdfRenderer.Page page;
    private float quickScale;
    private boolean patchShown;
    private final PageView.Viewport vp = new PageView.Viewport();
    private float lastL, lastT, lastR, lastB, lastS;

    Renderer(Context ctx, Host host, PageView view) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.view = view;
        thread = new HandlerThread("sheet-render", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        h = new Handler(thread.getLooper(), this);
    }

    void open(Uri uri) {
        gen++;
        h.removeCallbacksAndMessages(null);
        h.obtainMessage(MSG_OPEN, uri).sendToTarget();
    }

    void loadPage(int index) {
        int g = ++gen;
        h.removeMessages(MSG_PAGE);
        h.removeMessages(MSG_BASE);
        h.removeMessages(MSG_PATCH);
        h.obtainMessage(MSG_PAGE, index, g).sendToTarget();
    }

    void requestPatch() {
        h.removeMessages(MSG_PATCH);
        h.sendEmptyMessage(MSG_PATCH);
    }

    /** Lets go of the file and waits until that is done (needed before overwriting it). */
    void closeDocAndWait() {
        gen++;
        h.removeCallbacksAndMessages(null);
        CountDownLatch latch = new CountDownLatch(1);
        h.obtainMessage(MSG_CLOSE, latch).sendToTarget();
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    void shutdown() {
        gen++;
        h.removeCallbacksAndMessages(null);
        h.obtainMessage(MSG_CLOSE, null).sendToTarget();
        thread.quitSafely();
    }

    @Override
    public boolean handleMessage(Message m) {
        switch (m.what) {
            case MSG_OPEN:
                doOpen((Uri) m.obj);
                break;
            case MSG_PAGE:
                doPage(m.arg1, m.arg2);
                break;
            case MSG_BASE:
                doBase(m.arg1);
                break;
            case MSG_PATCH:
                doPatch();
                break;
            case MSG_CLOSE:
                closeDoc();
                if (m.obj instanceof CountDownLatch) ((CountDownLatch) m.obj).countDown();
                break;
        }
        return true;
    }

    private void closeDoc() {
        try {
            if (page != null) page.close();
        } catch (Throwable ignored) {
        }
        page = null;
        try {
            if (renderer != null) renderer.close();
        } catch (Throwable ignored) {
        }
        renderer = null;
        try {
            if (pfd != null) pfd.close();
        } catch (Throwable ignored) {
        }
        pfd = null;
        patchShown = false;
        lastS = 0;
    }

    private void doOpen(Uri uri) {
        closeDoc();
        try {
            try {
                pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new java.io.IOException("no file");
                renderer = new PdfRenderer(pfd);
            } catch (SecurityException se) {
                throw se;
            } catch (Exception first) {
                // Some places (cloud folders) give a file that can't be read in pieces:
                // bring a copy into the app and read that.
                try {
                    if (pfd != null) pfd.close();
                } catch (Throwable ignored) {
                }
                pfd = null;
                File copy = new File(ctx.getCacheDir(), "open.pdf");
                try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new java.io.IOException("no file");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
            }
            final int count = renderer.getPageCount();
            ui.post(() -> host.onDocOpened(count));
        } catch (final Throwable t) {
            closeDoc();
            final String why = (t instanceof SecurityException)
                    ? "This PDF is locked with a password, or the app is not allowed to read it."
                    : "This file could not be opened as a PDF.";
            ui.post(() -> host.onDocFailed(why));
        }
    }

    private Bitmap renderWhole(float s, float w, float hgt) {
        int bw = Math.max(1, Math.round(w * s)), bh = Math.max(1, Math.round(hgt * s));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Matrix m = new Matrix();
        m.setScale(bw / w, bh / hgt);
        page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return b;
    }

    private void doPage(final int index, final int g) {
        if (renderer == null || g != gen) return;
        try {
            if (page != null) {
                page.close();
                page = null;
            }
            patchShown = false;
            lastS = 0;
            page = renderer.openPage(index);
            final float w = page.getWidth(), hgt = page.getHeight();
            quickScale = Math.min(1400f / Math.max(w, hgt), 4f);
            final Bitmap b = renderWhole(quickScale, w, hgt);
            if (g != gen) return;
            final float s = quickScale;
            ui.post(() -> host.onPage(g, index, w, hgt, b, s));
            h.obtainMessage(MSG_BASE, g, 0).sendToTarget();
        } catch (final Throwable t) {
            ui.post(() -> host.onDocFailed("This page could not be drawn."));
        }
    }

    /** A finer whole-page picture, as large as memory sensibly allows. */
    private void doBase(final int g) {
        if (page == null || g != gen) return;
        try {
            float w = page.getWidth(), hgt = page.getHeight();
            float s = (float) Math.sqrt(8_000_000.0 / (w * hgt));
            s = Math.min(s, 4096f / Math.max(w, hgt));
            s = Math.min(s, 6f);
            if (s < quickScale * 1.15f) return;
            final Bitmap b = renderWhole(s, w, hgt);
            if (g != gen) return;
            final float fs = s;
            ui.post(() -> host.onBase(g, b, fs));
            // The view may already be zoomed in.
            requestPatch();
        } catch (Throwable ignored) {
            // Not enough memory for the finer picture: the quick one stays.
        }
    }

    /** A sharp picture of only what is on screen (plus a margin), at the current zoom. */
    private void doPatch() {
        final int g = gen;
        if (page == null || !view.snapshot(vp) || vp.page != page.getIndex()) return;
        try {
            if (vp.scale <= vp.baseScale * 1.02f) {
                if (patchShown) {
                    patchShown = false;
                    lastS = 0;
                    ui.post(() -> host.onPatch(g, null, null, 0));
                }
                return;
            }
            float s = vp.scale;
            float l = -vp.offX / s, t = -vp.offY / s;
            float r = l + vp.vw / s, b = t + vp.vh / s;
            float mx = (r - l) * 0.2f, my = (b - t) * 0.2f;
            l = Math.max(0, l - mx);
            t = Math.max(0, t - my);
            r = Math.min(vp.pageW, r + mx);
            b = Math.min(vp.pageH, b + my);
            if (r <= l || b <= t) return;
            if (s == lastS && l == lastL && t == lastT && r == lastR && b == lastB) return;
            int bw = Math.round((r - l) * s), bh = Math.round((b - t) * s);
            if (bw < 1 || bh < 1) return;

            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(-l * s, -t * s);
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            if (g != gen) {
                bmp.recycle();
                return;
            }
            lastS = s;
            lastL = l;
            lastT = t;
            lastR = r;
            lastB = b;
            patchShown = true;
            final RectF rect = new RectF(l, t, r, b);
            final Bitmap out = bmp;
            final float fs = s;
            ui.post(() -> host.onPatch(g, out, rect, fs));
        } catch (Throwable ignored) {
            // Out of memory or a page the system can't draw at this size: keep the base picture.
        }
    }
}
