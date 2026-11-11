package com.sheet.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/** A toolbar button: a small line icon drawn in code with a label under it. */
final class ToolButton extends View {
    static final int PAN = 0, PEN = 1, MARKER = 2, ERASER = 3, SIGN = 4, DATE = 5, STAMP = 6,
            UNDO = 7, REDO = 8, TEXT = 9, TICK = 10, BARPOS = 11;

    private final int kind;
    private final String label;
    private final Path icon = new Path();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean on;

    ToolButton(Context c, int kind, String label) {
        super(c);
        this.kind = kind;
        this.label = label;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(1.7f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(c, 10.5f));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        back.setColor(0x26FFB020);
        build();
        setClickable(true);
    }

    void setOn(boolean v) {
        if (on != v) {
            on = v;
            invalidate();
        }
    }

    /** Greys the button out without blocking long-press. */
    void setDim(boolean dim) {
        setAlpha(dim ? 0.35f : 1f);
    }

    // Icons are drawn on a 24 x 24 grid.
    private void build() {
        Path p = icon;
        switch (kind) {
            case PAN:
                p.moveTo(12, 3); p.lineTo(12, 21);
                p.moveTo(3, 12); p.lineTo(21, 12);
                p.moveTo(9.5f, 5.5f); p.lineTo(12, 3); p.lineTo(14.5f, 5.5f);
                p.moveTo(9.5f, 18.5f); p.lineTo(12, 21); p.lineTo(14.5f, 18.5f);
                p.moveTo(5.5f, 9.5f); p.lineTo(3, 12); p.lineTo(5.5f, 14.5f);
                p.moveTo(18.5f, 9.5f); p.lineTo(21, 12); p.lineTo(18.5f, 14.5f);
                break;
            case PEN:
                p.moveTo(4.5f, 19.5f); p.lineTo(6, 14.5f); p.lineTo(16, 4.5f); p.lineTo(19.5f, 8);
                p.lineTo(9.5f, 18); p.close();
                p.moveTo(14, 6.5f); p.lineTo(17.5f, 10);
                break;
            case MARKER:
                p.moveTo(7, 15); p.lineTo(14.5f, 4.5f); p.lineTo(20, 8.5f); p.lineTo(12.5f, 19);
                p.close();
                p.moveTo(7, 15); p.lineTo(5.5f, 19.5f); p.lineTo(12.5f, 19);
                p.moveTo(3.5f, 22); p.lineTo(15, 22);
                break;
            case ERASER:
                p.moveTo(9, 19); p.lineTo(4, 14); p.lineTo(13, 5); p.lineTo(20, 12); p.lineTo(13, 19);
                p.close();
                p.moveTo(8.5f, 9.5f); p.lineTo(15.5f, 16.5f);
                p.moveTo(13, 19); p.lineTo(20.5f, 19);
                break;
            case SIGN:
                p.moveTo(3, 14);
                p.cubicTo(6, 4, 9.5f, 4, 8.5f, 11);
                p.cubicTo(7.5f, 17.5f, 11, 17, 13, 11.5f);
                p.cubicTo(14, 8.5f, 16, 9.5f, 16, 12.5f);
                p.cubicTo(16, 15, 19, 14.5f, 21, 11.5f);
                p.moveTo(3, 20); p.lineTo(21, 20);
                break;
            case DATE:
                p.addRoundRect(4, 5.5f, 20, 20.5f, 2, 2, Path.Direction.CW);
                p.moveTo(4, 10); p.lineTo(20, 10);
                p.moveTo(8, 3); p.lineTo(8, 7);
                p.moveTo(16, 3); p.lineTo(16, 7);
                p.moveTo(8, 14); p.lineTo(10, 14);
                p.moveTo(14, 14); p.lineTo(16, 14);
                p.moveTo(8, 17); p.lineTo(10, 17);
                break;
            case STAMP:
                p.moveTo(9.5f, 3.5f); p.lineTo(14.5f, 3.5f); p.lineTo(14, 9); p.lineTo(17.5f, 13.5f);
                p.lineTo(6.5f, 13.5f); p.lineTo(10, 9); p.close();
                p.addRect(4.5f, 13.5f, 19.5f, 17, Path.Direction.CW);
                p.moveTo(4.5f, 20.5f); p.lineTo(19.5f, 20.5f);
                break;
            case UNDO:
                p.moveTo(9, 5); p.lineTo(4, 10); p.lineTo(9, 15);
                p.moveTo(4, 10); p.lineTo(14.5f, 10);
                p.cubicTo(18, 10, 20, 12.5f, 20, 14.5f);
                p.cubicTo(20, 17, 18, 19, 14.5f, 19);
                p.lineTo(11, 19);
                break;
            case REDO:
                p.moveTo(15, 5); p.lineTo(20, 10); p.lineTo(15, 15);
                p.moveTo(20, 10); p.lineTo(9.5f, 10);
                p.cubicTo(6, 10, 4, 12.5f, 4, 14.5f);
                p.cubicTo(4, 17, 6, 19, 9.5f, 19);
                p.lineTo(13, 19);
                break;
            case TEXT:
                p.moveTo(4.5f, 20); p.lineTo(10.5f, 4); p.lineTo(12.5f, 4); p.lineTo(18.5f, 20);
                p.moveTo(7, 14.5f); p.lineTo(16, 14.5f);
                p.moveTo(20, 15); p.lineTo(20, 21);
                p.moveTo(17, 18); p.lineTo(23, 18);
                break;
            case TICK:
                p.moveTo(4, 13); p.lineTo(9.5f, 19); p.lineTo(20, 5.5f);
                break;
            case BARPOS:
                p.addRoundRect(3.5f, 3.5f, 20.5f, 20.5f, 2, 2, Path.Direction.CW);
                p.moveTo(3.5f, 16.5f); p.lineTo(20.5f, 16.5f);
                p.moveTo(12, 13.5f); p.lineTo(12, 6.5f);
                p.moveTo(9.5f, 9); p.lineTo(12, 6.5f); p.lineTo(14.5f, 9);
                break;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        int color = on ? Ui.ACCENT : Ui.TEXT;
        if (on) {
            float m = 3 * d;
            c.drawRoundRect(m, m, w - m, h - m, 10 * d, 10 * d, back);
        }
        float size = 24 * d;
        float top = (h - size - 14 * d) / 2f;
        c.save();
        c.translate((w - size) / 2f, top);
        c.scale(d, d);
        line.setColor(color);
        c.drawPath(icon, line);
        c.restore();
        text.setColor(on ? Ui.ACCENT : Ui.DIM);
        c.drawText(label, w / 2f, top + size + 12 * d, text);
    }
}
