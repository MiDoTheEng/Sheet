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
 * Draws the file on its own thread so the screen never waits for it.
 *
 * Each page gets a whole-page picture (shown instantly while zooming and panning) and, when
 * zoomed in, a sharp picture of just the part on screen, redrawn continuously as the view moves.
 * The same engine serves PDF pages and DXF drawings.
 */
final class Renderer implements Handler.Callback {

    interface Host {
        /** cad is the drawing when the file is a DXF, otherwise null. */
        void onDocOpened(int pages, DxfDoc cad);

        void onDocFailed(String why);

        void onPage(int gen, int index, float w, float h, Bitmap base, float baseScale);

        void onBase(int gen, Bitmap base, float baseScale);

        /** bmp is null when the sharp picture is no longer needed. */
        void onPatch(int gen, Bitmap bmp, RectF rect, float scale);
    }

    /** Something that has pages and can draw them. */
    private interface Source {
        int pageCount();

        /** Opens a page and returns its width and height. */
        float[] open(int index) throws Exception;

        int current();

        void render(Bitmap b, Matrix m);

        void close();
    }

    private static final class PdfSource implements Source {
        private ParcelFileDescriptor pfd;
        private PdfRenderer renderer;
        private PdfRenderer.Page page;

        PdfSource(Context ctx, Uri uri) throws Exception {
            try {
                pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new java.io.IOException("no file");
                renderer = new PdfRenderer(pfd);
            } catch (SecurityException se) {
                close();
                throw se;
            } catch (Exception first) {
                // Some places (cloud folders) give a file that can't be read in pieces:
                // bring a copy into the app and read that.
                close();
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
        }

        public int pageCount() {
            return renderer.getPageCount();
        }

        public float[] open(int index) {
            if (page != null) page.close();
            page = null;
            page = renderer.openPage(index);
            return new float[]{page.getWidth(), page.getHeight()};
        }

        public int current() {
            return page == null ? -1 : page.getIndex();
        }

        public void render(Bitmap b, Matrix m) {
            page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        }

        public void close() {
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
        }
    }

    private static final class CadSource implements Source {
        final DxfDoc doc;
        private int cur = -1;

        CadSource(DxfDoc d) {
            doc = d;
        }

        public int pageCount() {
            return 1;
        }

        public float[] open(int index) {
            cur = 0;
            return new float[]{doc.pageW, doc.pageH};
        }

        public int current() {
            return cur;
        }

        public void render(Bitmap b, Matrix m) {
            doc.render(b, m);
        }

        public void close() {
        }
    }

    private static final int MSG_OPEN = 1, MSG_PAGE = 2, MSG_BASE = 3, MSG_PATCH = 4,
            MSG_CLOSE = 5, MSG_REFRESH = 6;

    private final Context ctx;
    private final Host host;
    private final PageView view;
    private final HandlerThread thread;
    private final Handler h;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Goes up every time another page is asked for; older results are thrown away. */
    volatile int gen;

    // Used only on the render thread.
    private Source src;
    private float pageW, pageH;
    private float quickScale, baseScale;
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
        h.removeMessages(MSG_REFRESH);
        h.obtainMessage(MSG_PAGE, index, g).sendToTarget();
    }

    void requestPatch() {
        h.removeMessages(MSG_PATCH);
        h.sendEmptyMessage(MSG_PATCH);
    }

    /** Draws the current page again in place (after layers or colors change), keeping the zoom. */
    void refresh() {
        h.removeMessages(MSG_REFRESH);
        h.removeMessages(MSG_PATCH);
        h.sendEmptyMessage(MSG_REFRESH);
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
                doBase(m.arg1, false);
                break;
            case MSG_REFRESH:
                doBase(gen, true);
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
        if (src != null) src.close();
        src = null;
        patchShown = false;
        lastS = 0;
    }

    private void doOpen(Uri uri) {
        closeDoc();
        String why = null;
        DxfDoc cad = null;
        try {
            byte[] head = new byte[1024];
            int hn = 0;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("no file");
                int r;
                while (hn < head.length && (r = in.read(head, hn, head.length - hn)) > 0) hn += r;
            }
            String hs = new String(head, 0, hn, "ISO-8859-1");
            if (hs.contains("%PDF")) {
                src = new PdfSource(ctx, uri);
            } else if (hs.startsWith("AC10")) {
                why = DxfDoc.DWG_MSG;
            } else {
                try {
                    cad = DxfDoc.load(ctx, uri);
                    src = new CadSource(cad);
                } catch (DxfDoc.CadException ce) {
                    // Not a DXF: perhaps a PDF with something before its header.
                    try {
                        src = new PdfSource(ctx, uri);
                    } catch (Throwable t) {
                        why = ce.getMessage();
                    }
                }
            }
        } catch (SecurityException se) {
            why = "This PDF is locked with a password, or the app is not allowed to read it.";
        } catch (OutOfMemoryError oom) {
            why = "This drawing is too large for this device's memory.";
        } catch (Throwable t) {
            why = "This file could not be opened as a PDF or a DXF drawing.";
        }
        if (src == null) {
            closeDoc();
            final String w = why != null ? why : "This file could not be opened.";
            ui.post(() -> host.onDocFailed(w));
            return;
        }
        final int count = src.pageCount();
        final DxfDoc fc = cad;
        ui.post(() -> host.onDocOpened(count, fc));
    }

    private Bitmap renderWhole(float s) {
        int bw = Math.max(1, Math.round(pageW * s)), bh = Math.max(1, Math.round(pageH * s));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Matrix m = new Matrix();
        m.setScale(bw / pageW, bh / pageH);
        src.render(b, m);
        return b;
    }

    private void doPage(final int index, final int g) {
        if (src == null || g != gen) return;
        try {
            patchShown = false;
            lastS = 0;
            float[] wh = src.open(index);
            pageW = wh[0];
            pageH = wh[1];
            final float w = pageW, hgt = pageH;
            quickScale = Math.min(1400f / Math.max(w, hgt), 4f);
            baseScale = quickScale;
            final Bitmap b = renderWhole(quickScale);
            if (g != gen) return;
            final float s = quickScale;
            ui.post(() -> host.onPage(g, index, w, hgt, b, s));
            h.obtainMessage(MSG_BASE, g, 0).sendToTarget();
        } catch (final Throwable t) {
            ui.post(() -> host.onDocFailed("This page could not be drawn."));
        }
    }

    /** A finer whole-page picture, as large as memory sensibly allows. */
    private void doBase(final int g, boolean force) {
        if (src == null || src.current() < 0 || g != gen) return;
        try {
            float s = (float) Math.sqrt(8_000_000.0 / (pageW * pageH));
            s = Math.min(s, 4096f / Math.max(pageW, pageH));
            s = Math.min(s, 6f);
            if (s < quickScale * 1.15f) {
                if (!force) return;
                s = quickScale;
            }
            final Bitmap b = renderWhole(s);
            if (g != gen) return;
            baseScale = s;
            final float fs = s;
            ui.post(() -> host.onBase(g, b, fs));
            if (force) {
                lastS = 0; // the sharp picture must be drawn again too
            }
            // The view may already be zoomed in.
            doPatch();
        } catch (Throwable ignored) {
            // Not enough memory for the finer picture: the quick one stays.
        }
    }

    /** A sharp picture of only what is on screen (plus a margin), at the current zoom. */
    private void doPatch() {
        final int g = gen;
        if (src == null || !view.snapshot(vp) || vp.page != src.current()) return;
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
            src.render(bmp, m);

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
