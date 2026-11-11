package com.sheet.viewer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Xfermode;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;

/**
 * Shows one page and everything drawn on it. Zooming and panning only move pictures that are
 * already made, so they follow the fingers at the screen's full frame rate; the sharp picture
 * catches up from the render thread.
 */
final class PageView extends View {

    interface Host {
        void onViewChanged();

        void onPageFling(int dir);

        void onMarksChanged();
    }

    /** What the render thread needs to know about the view. */
    static final class Viewport {
        int page, vw, vh;
        float scale, offX, offY, pageW, pageH, baseScale;
    }

    static final int HAND = 0, PEN = 1, HI = 2, ERASER = 3;

    private static final int G_NONE = 0, G_NAV = 1, G_DRAW = 2, G_ERASE = 3, G_MOVE = 4,
            G_RESIZE = 5, G_DELETE = 6, G_PINCH = 7;

    int tool = HAND;
    int penColor = 0xFFE53935, hiColor = 0xFFFFEB3B;
    float penWidthPx, hiWidthPx;

    private Host host;
    private Marks marks;

    private boolean hasPage;
    private int pageIndex;
    private float pageW, pageH;
    private Bitmap base;
    private float baseScale = 1;
    private Bitmap patch;
    private final RectF patchRect = new RectF();
    private float patchScale;

    private float scale = 1, offX, offY, fitScale = 1;
    private final Object lock = new Object();
    private final Viewport pub = new Viewport();
    private boolean pubValid;

    private ArrayList<Marks.Item> items;
    private Marks.Stroke cur;
    private Marks.Stamp selected;

    private int gesture = G_NONE;
    private float lastX, lastY, lastFx, lastFy, grabDx, grabAspect;
    private boolean moved;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint white = new Paint();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Xfermode multiply = new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY);
    private final Matrix tmpM = new Matrix();
    private final RectF tmpR = new RectF();

    private final GestureDetector det;
    private final ScaleGestureDetector scaleDet;
    private final OverScroller scroller;
    private ValueAnimator anim;
    private final float handleR, hitR;

    PageView(Context c) {
        super(c);
        white.setColor(0xFFFFFFFF);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        selLine.setStyle(Paint.Style.STROKE);
        selLine.setStrokeWidth(Ui.dp(c, 1.5f));
        selLine.setColor(Ui.ACCENT);
        selLine.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 6), Ui.dp(c, 4)}, 0));
        handleMark.setStyle(Paint.Style.STROKE);
        handleMark.setStrokeWidth(Ui.dp(c, 2));
        handleMark.setStrokeCap(Paint.Cap.ROUND);
        handleMark.setColor(0xFFFFFFFF);
        handleR = Ui.dp(c, 13);
        hitR = Ui.dp(c, 24);
        penWidthPx = Ui.dp(c, 3.2f);
        hiWidthPx = Ui.dp(c, 16);
        scroller = new OverScroller(c);

        det = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e2.getPointerCount() > 1 || scaleDet.isInProgress()) return false;
                offX -= dx;
                offY -= dy;
                viewChanged();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scale <= fitScale * 1.02f) {
                    // Whole page on screen: a sideways flick turns the page.
                    if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > Ui.dp(getContext(), 500)) {
                        if (host != null) host.onPageFling(vx < 0 ? 1 : -1);
                    }
                    return true;
                }
                float cw = pageW * scale, ch = pageH * scale;
                int minX = cw <= getWidth() ? (int) offX : (int) (getWidth() - cw);
                int maxX = cw <= getWidth() ? (int) offX : 0;
                int minY = ch <= getHeight() ? (int) offY : (int) (getHeight() - ch);
                int maxY = ch <= getHeight() ? (int) offY : 0;
                scroller.fling((int) offX, (int) offY, (int) vx, (int) vy, minX, maxX, minY, maxY);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float target = scale < fitScale * 1.5f ? fitScale * 3.5f : fitScale;
                animateZoom(target, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                Marks.Stamp hit = stampAt(e.getX(), e.getY());
                if (hit != selected) {
                    selected = hit;
                    invalidate();
                }
                return true;
            }
        });

        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX(), fy = d.getFocusY();
                zoomBy(d.getScaleFactor(), fx, fy);
                offX += fx - lastFx;
                offY += fy - lastFy;
                lastFx = fx;
                lastFy = fy;
                viewChanged();
                return true;
            }
        });
        scaleDet.setQuickScaleEnabled(false);
    }

    void setHost(Host h) {
        host = h;
    }

    void setMarks(Marks m) {
        marks = m;
        if (hasPage) items = m.list(pageIndex);
        selected = null;
        invalidate();
    }

    // ---- pictures from the render thread ----

    /** Shows another page. Returns the pictures no longer needed. */
    Bitmap[] setPage(int index, float w, float h, Bitmap b, float bScale) {
        Bitmap[] old = {base, patch};
        scroller.forceFinished(true);
        if (anim != null) anim.cancel();
        pageIndex = index;
        pageW = w;
        pageH = h;
        base = b;
        baseScale = bScale;
        patch = null;
        hasPage = true;
        items = marks.list(index);
        selected = null;
        cur = null;
        gesture = G_NONE;
        computeFit();
        scale = fitScale;
        offX = offY = 0;
        viewChanged();
        return old;
    }

    Bitmap setBase(Bitmap b, float bScale) {
        Bitmap old = base;
        base = b;
        baseScale = bScale;
        publish();
        invalidate();
        return old;
    }

    Bitmap setPatch(Bitmap b, RectF r, float s) {
        Bitmap old = patch;
        patch = b;
        if (b != null) {
            patchRect.set(r);
            patchScale = s;
        }
        invalidate();
        return old;
    }

    boolean snapshot(Viewport out) {
        synchronized (lock) {
            if (!pubValid) return false;
            out.page = pub.page;
            out.vw = pub.vw;
            out.vh = pub.vh;
            out.scale = pub.scale;
            out.offX = pub.offX;
            out.offY = pub.offY;
            out.pageW = pub.pageW;
            out.pageH = pub.pageH;
            out.baseScale = pub.baseScale;
            return true;
        }
    }

    private void publish() {
        synchronized (lock) {
            pubValid = hasPage && getWidth() > 0 && getHeight() > 0;
            pub.page = pageIndex;
            pub.vw = getWidth();
            pub.vh = getHeight();
            pub.scale = scale;
            pub.offX = offX;
            pub.offY = offY;
            pub.pageW = pageW;
            pub.pageH = pageH;
            pub.baseScale = baseScale;
        }
    }

    // ---- view position ----

    private void computeFit() {
        if (!hasPage || getWidth() == 0 || getHeight() == 0) return;
        float pad = Ui.dp(getContext(), 6);
        fitScale = Math.min((getWidth() - 2 * pad) / pageW, (getHeight() - 2 * pad) / pageH);
        if (fitScale <= 0) fitScale = 0.01f;
    }

    private float maxScale() {
        return Math.max(fitScale * 60f, 14f);
    }

    private void zoomBy(float f, float fx, float fy) {
        float ns = Math.max(fitScale, Math.min(maxScale(), scale * f));
        f = ns / scale;
        offX = fx - (fx - offX) * f;
        offY = fy - (fy - offY) * f;
        scale = ns;
    }

    private void clamp() {
        float cw = pageW * scale, ch = pageH * scale;
        int vw = getWidth(), vh = getHeight();
        if (cw <= vw) offX = (vw - cw) / 2f;
        else offX = Math.min(0, Math.max(vw - cw, offX));
        if (ch <= vh) offY = (vh - ch) / 2f;
        else offY = Math.min(0, Math.max(vh - ch, offY));
    }

    private void viewChanged() {
        if (!hasPage) return;
        clamp();
        publish();
        invalidate();
        if (host != null) host.onViewChanged();
    }

    private void animateZoom(final float target, final float fx, final float fy) {
        if (anim != null) anim.cancel();
        final float start = scale;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            float s = (float) (start * Math.pow(target / start, t));
            zoomBy(s / scale, fx, fy);
            viewChanged();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (!hasPage) return;
        boolean wasFit = scale <= fitScale * 1.02f;
        computeFit();
        if (wasFit || scale < fitScale) scale = fitScale;
        viewChanged();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.getCurrX();
            offY = scroller.getCurrY();
            viewChanged();
        }
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Ui.BG);
        if (!hasPage) return;

        c.drawRect(offX, offY, offX + pageW * scale, offY + pageH * scale, white);
        if (base != null && !base.isRecycled()) {
            tmpR.set(offX, offY, offX + pageW * scale, offY + pageH * scale);
            c.drawBitmap(base, null, tmpR, bmpPaint);
        }
        if (patch != null && !patch.isRecycled() && patchScale > baseScale) {
            tmpR.set(offX + patchRect.left * scale, offY + patchRect.top * scale,
                    offX + patchRect.right * scale, offY + patchRect.bottom * scale);
            c.drawBitmap(patch, null, tmpR, bmpPaint);
        }

        c.save();
        c.clipRect(offX, offY, offX + pageW * scale, offY + pageH * scale);
        c.translate(offX, offY);
        c.scale(scale, scale);
        if (items != null) {
            for (int i = 0, n = items.size(); i < n; i++) drawItem(c, items.get(i));
        }
        if (cur != null) drawItem(c, cur);
        c.restore();

        if (selected != null) {
            RectF r = screenRect(selected);
            c.drawRect(r, selLine);
            handle.setColor(0xFFE53935);
            c.drawCircle(r.left, r.top, handleR, handle);
            float k = handleR * 0.4f;
            c.drawLine(r.left - k, r.top - k, r.left + k, r.top + k, handleMark);
            c.drawLine(r.left - k, r.top + k, r.left + k, r.top - k, handleMark);
            handle.setColor(Ui.ACCENT);
            c.drawCircle(r.right, r.bottom, handleR, handle);
            c.drawLine(r.right - k, r.bottom - k, r.right + k, r.bottom + k, handleMark);
        }
    }

    private void drawItem(Canvas c, Marks.Item it) {
        if (it instanceof Marks.Stroke) {
            Marks.Stroke s = (Marks.Stroke) it;
            ink.setColor(s.color);
            ink.setStrokeWidth(s.width);
            ink.setXfermode(s.hi ? multiply : null);
            c.drawPath(s.path(), ink);
        } else {
            Marks.Stamp st = (Marks.Stamp) it;
            if (st.kind == Marks.Stamp.SIG) {
                Bitmap b = Sig.get(getContext());
                if (b != null) c.drawBitmap(b, null, st.rect, bmpPaint);
            } else {
                float[] nat = Marks.StampArt.natural(st);
                c.save();
                c.translate(st.rect.left, st.rect.top);
                c.scale(st.rect.width() / nat[0], st.rect.height() / nat[1]);
                Marks.StampArt.draw(c, st);
                c.restore();
            }
        }
    }

    private RectF screenRect(Marks.Stamp s) {
        tmpR.set(offX + s.rect.left * scale, offY + s.rect.top * scale,
                offX + s.rect.right * scale, offY + s.rect.bottom * scale);
        return tmpR;
    }

    private Marks.Stamp stampAt(float x, float y) {
        if (items == null) return null;
        float px = (x - offX) / scale, py = (y - offY) / scale;
        float slack = Ui.dp(getContext(), 6) / scale;
        for (int i = items.size() - 1; i >= 0; i--) {
            Marks.Item it = items.get(i);
            if (it instanceof Marks.Stamp) {
                RectF r = ((Marks.Stamp) it).rect;
                if (px >= r.left - slack && px <= r.right + slack
                        && py >= r.top - slack && py <= r.bottom + slack) {
                    return (Marks.Stamp) it;
                }
            }
        }
        return null;
    }

    // ---- stamps ----

    /**
     * Puts a stamp in the middle of what is on screen, selected and ready to drag.
     * aspect is width / height; the fractions are of the visible part of the page.
     */
    void place(Marks.Stamp st, float aspect, float fracW, float maxFracH) {
        if (!hasPage) return;
        float visW = Math.min(getWidth(), pageW * scale) / scale;
        float visH = Math.min(getHeight(), pageH * scale) / scale;
        float w = visW * fracW, h = w / aspect;
        if (h > visH * maxFracH) {
            h = visH * maxFracH;
            w = h * aspect;
        }
        float cx = (getWidth() / 2f - offX) / scale, cy = (getHeight() / 2f - offY) / scale;
        if (w < pageW) cx = Math.max(w / 2f, Math.min(pageW - w / 2f, cx));
        if (h < pageH) cy = Math.max(h / 2f, Math.min(pageH - h / 2f, cy));
        st.rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        marks.add(pageIndex, st);
        selected = st;
        tool = HAND;
        invalidate();
        if (host != null) host.onMarksChanged();
    }

    boolean hasSelection() {
        return selected != null;
    }

    void deselect() {
        if (selected != null) {
            selected = null;
            invalidate();
        }
    }

    /** Call after undo/redo or any change made from outside. */
    void refresh() {
        if (selected != null && (items == null || !items.contains(selected))) selected = null;
        invalidate();
    }

    // ---- touch ----

    private int pickGesture(float x, float y) {
        if (selected != null) {
            RectF r = screenRect(selected);
            if (Math.hypot(x - r.left, y - r.top) <= hitR) return G_DELETE;
            if (Math.hypot(x - r.right, y - r.bottom) <= hitR) {
                grabDx = r.right - x;
                grabAspect = selected.rect.width() / Math.max(0.001f, selected.rect.height());
                return G_RESIZE;
            }
            if (r.contains(x, y)) return G_MOVE;
        }
        if (tool == PEN || tool == HI) return G_DRAW;
        if (tool == ERASER) return G_ERASE;
        return G_NAV;
    }

    private void addPoint(float x, float y, boolean force) {
        float px = Math.max(0, Math.min(pageW, (x - offX) / scale));
        float py = Math.max(0, Math.min(pageH, (y - offY) / scale));
        if (!force && cur.n >= 2) {
            float dx = (px - cur.pts[cur.n - 2]) * scale, dy = (py - cur.pts[cur.n - 1]) * scale;
            if (dx * dx + dy * dy < 2.25f) return;
        }
        cur.add(px, py);
    }

    private void eraseAt(float x, float y) {
        if (items == null) return;
        float px = (x - offX) / scale, py = (y - offY) / scale;
        float r = Ui.dp(getContext(), 12) / scale;
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            Marks.Item it = items.get(i);
            if (it instanceof Marks.Stroke && ((Marks.Stroke) it).near(px, py, r)) {
                marks.remove(pageIndex, it);
                changed = true;
            }
        }
        if (changed) {
            invalidate();
            if (host != null) host.onMarksChanged();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!hasPage) return false;
        int a = e.getActionMasked();
        scaleDet.onTouchEvent(e);
        float x = e.getX(), y = e.getY();

        if (a == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            if (anim != null) anim.cancel();
            moved = false;
            gesture = pickGesture(x, y);
            if (gesture == G_DRAW || gesture == G_ERASE) selected = null;
            if (gesture == G_DRAW) {
                cur = new Marks.Stroke();
                boolean hi = tool == HI;
                cur.hi = hi;
                cur.color = hi ? hiColor : penColor;
                cur.width = (hi ? hiWidthPx : penWidthPx) / scale;
                addPoint(x, y, true);
            }
            lastX = x;
            lastY = y;
        }

        if (a == MotionEvent.ACTION_POINTER_DOWN && gesture != G_PINCH) {
            // A second finger always means move and zoom, whatever tool is chosen.
            if (gesture == G_NAV) {
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                det.onTouchEvent(cancel);
                cancel.recycle();
            }
            if ((gesture == G_MOVE || gesture == G_RESIZE) && moved && host != null) {
                host.onMarksChanged();
            }
            cur = null;
            gesture = G_PINCH;
            invalidate();
        }

        switch (gesture) {
            case G_NAV:
                det.onTouchEvent(e);
                break;
            case G_DRAW:
                if (cur == null) break;
                if (a == MotionEvent.ACTION_MOVE) {
                    for (int i = 0, n = e.getHistorySize(); i < n; i++) {
                        addPoint(e.getHistoricalX(i), e.getHistoricalY(i), false);
                    }
                    addPoint(x, y, false);
                    invalidate();
                } else if (a == MotionEvent.ACTION_UP) {
                    addPoint(x, y, cur.n == 2);
                    marks.add(pageIndex, cur);
                    cur = null;
                    invalidate();
                    if (host != null) host.onMarksChanged();
                } else if (a == MotionEvent.ACTION_CANCEL) {
                    cur = null;
                    invalidate();
                }
                break;
            case G_ERASE:
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                    for (int i = 0, n = e.getHistorySize(); i < n; i++) {
                        eraseAt(e.getHistoricalX(i), e.getHistoricalY(i));
                    }
                    eraseAt(x, y);
                }
                break;
            case G_MOVE:
                if (a == MotionEvent.ACTION_MOVE && selected != null) {
                    RectF r = selected.rect;
                    float dx = (x - lastX) / scale, dy = (y - lastY) / scale;
                    if (r.width() < pageW) dx = Math.max(-r.left, Math.min(pageW - r.right, dx));
                    if (r.height() < pageH) dy = Math.max(-r.top, Math.min(pageH - r.bottom, dy));
                    r.offset(dx, dy);
                    lastX = x;
                    lastY = y;
                    moved = true;
                    invalidate();
                } else if (a == MotionEvent.ACTION_UP && moved && host != null) {
                    host.onMarksChanged();
                }
                break;
            case G_RESIZE:
                if (a == MotionEvent.ACTION_MOVE && selected != null) {
                    RectF r = selected.rect;
                    float minW = Ui.dp(getContext(), 28) / scale;
                    float w = Math.max(minW, (x + grabDx - offX) / scale - r.left);
                    r.right = r.left + w;
                    r.bottom = r.top + w / grabAspect;
                    moved = true;
                    invalidate();
                } else if (a == MotionEvent.ACTION_UP && moved && host != null) {
                    host.onMarksChanged();
                }
                break;
            case G_DELETE:
                if (a == MotionEvent.ACTION_UP && selected != null) {
                    RectF r = screenRect(selected);
                    if (Math.hypot(x - r.left, y - r.top) <= hitR * 1.5f) {
                        marks.remove(pageIndex, selected);
                        selected = null;
                        invalidate();
                        if (host != null) host.onMarksChanged();
                    }
                }
                break;
            case G_PINCH:
                if (a == MotionEvent.ACTION_POINTER_UP && e.getPointerCount() == 2) {
                    int left = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(left);
                    lastY = e.getY(left);
                } else if (a == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1
                        && !scaleDet.isInProgress()) {
                    // One finger stayed down after a pinch: keep dragging the page with it.
                    offX += x - lastX;
                    offY += y - lastY;
                    lastX = x;
                    lastY = y;
                    viewChanged();
                }
                break;
        }

        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) gesture = G_NONE;
        return true;
    }
}
