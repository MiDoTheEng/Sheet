package com.sheet.viewer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cropping the picked signature photo: drag a box around the signature itself. */
public final class CropActivity extends Activity {
    private Bitmap bmp;
    private CropView crop;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        bmp = getIntent().getData() == null ? null : Sig.decodeRaw(this, getIntent().getData());
        if (bmp == null) {
            Ui.toast(this, "That image could not be read");
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        TextView hint = Ui.label(this, "Drag the corners around your signature", 14, Ui.DIM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        crop = new CropView(this, bmp);
        root.addView(crop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Ui.BAR);
        TextView cancel = Ui.label(this, "Cancel", 16, Ui.DIM);
        TextView use = Ui.label(this, "Use this", 16, Ui.ACCENT);
        use.setTypeface(Typeface.DEFAULT_BOLD);
        for (TextView t : new TextView[]{cancel, use}) {
            t.setGravity(Gravity.CENTER);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));
        setContentView(root);

        cancel.setOnClickListener(v -> finish());
        use.setOnClickListener(v -> {
            use.setEnabled(false);
            final Bitmap out = crop.result();
            new Thread(() -> {
                final boolean ok = out != null && Sig.saveBitmap(this, out);
                runOnUiThread(() -> {
                    if (ok) setResult(RESULT_OK);
                    else Ui.toast(this, "That image could not be used");
                    finish();
                });
            }, "sheet-crop").start();
        });
    }

    /** The photo with a movable, resizable crop box over it. */
    static final class CropView extends View {
        private final Bitmap bmp;
        private final RectF img = new RectF(), sel = new RectF();
        private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint dim = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float hr, minS;
        private int drag = -1; // 0 TL, 1 TR, 2 BL, 3 BR, 4 move
        private float lx, ly;

        CropView(Context c, Bitmap b) {
            super(c);
            bmp = b;
            dim.setColor(0xAA000000);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Ui.dp(c, 2));
            border.setColor(0xFFFFFFFF);
            dot.setColor(Ui.ACCENT);
            hr = Ui.dp(c, 12);
            minS = Ui.dp(c, 44);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float pad = Ui.dp(getContext(), 16);
            float s = Math.min((w - 2 * pad) / bmp.getWidth(), (h - 2 * pad) / bmp.getHeight());
            float iw = bmp.getWidth() * s, ih = bmp.getHeight() * s;
            img.set((w - iw) / 2f, (h - ih) / 2f, (w + iw) / 2f, (h + ih) / 2f);
            float in = Math.min(iw, ih) * 0.06f;
            sel.set(img.left + in, img.top + in, img.right - in, img.bottom - in);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Ui.BG);
            c.drawBitmap(bmp, null, img, bp);
            c.drawRect(0, 0, getWidth(), sel.top, dim);
            c.drawRect(0, sel.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, sel.top, sel.left, sel.bottom, dim);
            c.drawRect(sel.right, sel.top, getWidth(), sel.bottom, dim);
            c.drawRect(sel, border);
            c.drawCircle(sel.left, sel.top, hr, dot);
            c.drawCircle(sel.right, sel.top, hr, dot);
            c.drawCircle(sel.left, sel.bottom, hr, dot);
            c.drawCircle(sel.right, sel.bottom, hr, dot);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    float grab = hr * 2.4f;
                    if (Math.hypot(x - sel.left, y - sel.top) < grab) drag = 0;
                    else if (Math.hypot(x - sel.right, y - sel.top) < grab) drag = 1;
                    else if (Math.hypot(x - sel.left, y - sel.bottom) < grab) drag = 2;
                    else if (Math.hypot(x - sel.right, y - sel.bottom) < grab) drag = 3;
                    else if (sel.contains(x, y)) drag = 4;
                    else drag = -1;
                    lx = x;
                    ly = y;
                    return drag >= 0;
                case MotionEvent.ACTION_MOVE:
                    if (drag < 0) return false;
                    if (drag == 4) {
                        float dx = Math.max(img.left - sel.left, Math.min(img.right - sel.right, x - lx));
                        float dy = Math.max(img.top - sel.top, Math.min(img.bottom - sel.bottom, y - ly));
                        sel.offset(dx, dy);
                        lx = x;
                        ly = y;
                    } else {
                        if (drag == 0 || drag == 2) {
                            sel.left = Math.max(img.left, Math.min(sel.right - minS, x));
                        } else {
                            sel.right = Math.min(img.right, Math.max(sel.left + minS, x));
                        }
                        if (drag == 0 || drag == 1) {
                            sel.top = Math.max(img.top, Math.min(sel.bottom - minS, y));
                        } else {
                            sel.bottom = Math.min(img.bottom, Math.max(sel.top + minS, y));
                        }
                    }
                    invalidate();
                    return true;
                default:
                    drag = -1;
                    return true;
            }
        }

        /** The chosen part of the photo, in the photo's own pixels. */
        Bitmap result() {
            try {
                float s = bmp.getWidth() / img.width();
                int x = Math.max(0, Math.round((sel.left - img.left) * s));
                int y = Math.max(0, Math.round((sel.top - img.top) * s));
                int w = Math.min(bmp.getWidth() - x, Math.round(sel.width() * s));
                int h = Math.min(bmp.getHeight() - y, Math.round(sel.height() * s));
                if (w < 4 || h < 4) return null;
                return Bitmap.createBitmap(bmp, x, y, w, h);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
