package com.sheet.viewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;

/**
 * Site markup for MEP work: pipe and duct systems, fixture and equipment symbols, and how they
 * are drawn on the page, in saved PDFs and in the "Site notes" schedule.
 */
final class Mep {
    private Mep() {}

    // ---- pipes ----
    static final String[] PIPE_NAMES = {"Cold water", "Hot water", "Drainage", "Vent",
            "Chilled water supply", "Chilled water return", "Condensate", "Fire fighting", "Gas",
            "Rain water"};
    static final String[] PIPE_ABBR = {"CW", "HW", "D", "V", "CHWS", "CHWR", "CD", "FF", "G", "RW"};
    static final int[] PIPE_COLOR = {0xFF1E88E5, 0xFFE53935, 0xFF6D4C41, 0xFF43A047, 0xFF00897B,
            0xFF00897B, 0xFF8E24AA, 0xFFC62828, 0xFFF9A825, 0xFF3949AB};
    static final boolean[] PIPE_DASH = {false, false, false, true, false, true, false, false, false,
            false};
    static final String[] PIPE_SIZES = {"Ø15", "Ø20", "Ø25", "Ø32", "Ø40",
            "Ø50", "Ø65", "Ø80", "Ø100", "Ø150", "Ø200"};

    // ---- ducts ----
    static final String[] DUCT_NAMES = {"Supply air", "Return air", "Fresh air", "Exhaust air"};
    static final String[] DUCT_ABBR = {"SA", "RA", "FA", "EA"};
    static final int[] DUCT_COLOR = {0xFF1E88E5, 0xFFFB8C00, 0xFF43A047, 0xFF8E24AA};
    static final String[] DUCT_SIZES = {"300x200", "400x250", "500x300", "600x400", "800x400",
            "1000x500", "Ø200", "Ø250"};

    // ---- fixtures and equipment ----
    static final int WC = 0, BASIN = 1, SINK = 2, SHOWER = 3, FD = 4, WH = 5, AC = 6, FCU = 7,
            DIFF = 8, GRILLE = 9, EXFAN = 10, THERMO = 11, VALVE = 12, PUMP = 13, PANEL = 14,
            LIGHT = 15;
    static final String[] SYM_NAMES = {"WC", "Wash basin", "Kitchen sink", "Shower",
            "Floor drain", "Water heater", "Split AC unit", "FCU / AHU", "Supply diffuser",
            "Return grille", "Exhaust fan", "Thermostat", "Valve", "Pump", "Electrical panel (DB)",
            "Light"};
    /** Short names: shown in the MEP bar and under each placed item. */
    static final String[] SYM_SHORT = {"WC", "Wash basin", "Kitchen sink", "Shower", "Floor drain",
            "Water heater", "Split AC", "FCU / AHU", "Diffuser", "Return grille", "Exhaust fan",
            "Thermostat", "Valve", "Pump", "Panel (DB)", "Light"};
    /** Pipe names short enough for the MEP panel. */
    static final String[] PIPE_SHORT = {"Cold water", "Hot water", "Drainage", "Vent",
            "CHW supply", "CHW return", "Condensate", "Fire fighting", "Gas", "Rain water"};
    /** 0 plumbing, 1 HVAC, 2 general, 3 electrical. */
    static final int[] SYM_GROUP = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 3, 3};
    static final int[] GROUP_COLOR = {0xFF1565C0, 0xFF00897B, 0xFF6D4C41, 0xFFEF6C00};
    /** Width / height of each symbol. */
    static final float[] SYM_ASPECT = {0.7f, 1.3f, 1.8f, 1f, 1f, 1f, 2.8f, 1.8f, 1f, 1.6f, 1f, 1f,
            1.6f, 1f, 2f, 1f};

    static int symColor(int sym) {
        return GROUP_COLOR[SYM_GROUP[Math.max(0, Math.min(SYM_GROUP.length - 1, sym))]];
    }

    // =====================================================================================
    // Symbols
    // =====================================================================================

    /** Draws a fixture or equipment symbol filling r. */
    static void drawSymbol(Canvas c, int sym, RectF r, int color) {
        float w = r.width(), h = r.height(), L = r.left, T = r.top;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        float sw = Math.min(w, h) * 0.07f;
        p.setStrokeWidth(sw);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(color);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xD9FFFFFF);
        float in = sw / 2f;
        RectF box = new RectF(L + in, T + in, L + w - in, T + h - in);

        switch (sym) {
            case WC: {
                RectF tank = new RectF(L + w * 0.08f, T + in, L + w * 0.92f, T + h * 0.27f);
                RectF bowl = new RectF(L + w * 0.14f, T + h * 0.31f, L + w * 0.86f, T + h - in);
                c.drawRoundRect(tank, sw, sw, bg);
                c.drawRoundRect(tank, sw, sw, p);
                c.drawOval(bowl, bg);
                c.drawOval(bowl, p);
                break;
            }
            case BASIN: {
                c.drawRoundRect(box, h * 0.15f, h * 0.15f, bg);
                c.drawRoundRect(box, h * 0.15f, h * 0.15f, p);
                c.drawOval(L + w * 0.16f, T + h * 0.22f, L + w * 0.84f, T + h * 0.84f, p);
                c.drawCircle(L + w * 0.5f, T + h * 0.53f, sw * 0.9f, f);
                break;
            }
            case SINK: {
                c.drawRect(box, bg);
                c.drawRect(box, p);
                c.drawRoundRect(L + w * 0.07f, T + h * 0.14f, L + w * 0.47f, T + h * 0.86f, sw, sw, p);
                c.drawRoundRect(L + w * 0.53f, T + h * 0.14f, L + w * 0.93f, T + h * 0.86f, sw, sw, p);
                break;
            }
            case SHOWER: {
                c.drawRect(box, bg);
                c.drawRect(box, p);
                c.drawLine(box.left, box.top, box.right, box.bottom, p);
                c.drawLine(box.right, box.top, box.left, box.bottom, p);
                c.drawCircle(L + w / 2, T + h / 2, Math.min(w, h) * 0.12f, bg);
                c.drawCircle(L + w / 2, T + h / 2, Math.min(w, h) * 0.12f, p);
                break;
            }
            case FD: {
                float rr = Math.min(w, h) / 2 - in;
                c.drawCircle(L + w / 2, T + h / 2, rr, bg);
                c.drawCircle(L + w / 2, T + h / 2, rr, p);
                float k = rr * 0.6f;
                c.drawLine(L + w / 2 - k, T + h / 2, L + w / 2 + k, T + h / 2, p);
                c.drawLine(L + w / 2, T + h / 2 - k, L + w / 2, T + h / 2 + k, p);
                break;
            }
            case WH: {
                float rr = Math.min(w, h) / 2 - in;
                c.drawCircle(L + w / 2, T + h / 2, rr, bg);
                c.drawCircle(L + w / 2, T + h / 2, rr, p);
                inner(c, "WH", r, color, 0.36f);
                break;
            }
            case AC: {
                c.drawRoundRect(box, h * 0.2f, h * 0.2f, bg);
                c.drawRoundRect(box, h * 0.2f, h * 0.2f, p);
                c.drawLine(L + w * 0.08f, T + h * 0.72f, L + w * 0.92f, T + h * 0.72f, p);
                inner(c, "AC", new RectF(L, T, L + w, T + h * 0.7f), color, 0.55f);
                break;
            }
            case FCU: {
                c.drawRect(box, bg);
                c.drawRect(box, p);
                inner(c, "FCU", r, color, 0.42f);
                break;
            }
            case DIFF: {
                c.drawRect(box, bg);
                c.drawRect(box, p);
                RectF ir = new RectF(L + w * 0.3f, T + h * 0.3f, L + w * 0.7f, T + h * 0.7f);
                c.drawRect(ir, p);
                c.drawLine(box.left, box.top, ir.left, ir.top, p);
                c.drawLine(box.right, box.top, ir.right, ir.top, p);
                c.drawLine(box.left, box.bottom, ir.left, ir.bottom, p);
                c.drawLine(box.right, box.bottom, ir.right, ir.bottom, p);
                break;
            }
            case GRILLE: {
                c.drawRect(box, bg);
                c.drawRect(box, p);
                for (int i = 1; i <= 4; i++) {
                    float y = T + h * i / 5f;
                    c.drawLine(L + w * 0.1f, y, L + w * 0.9f, y, p);
                }
                break;
            }
            case EXFAN: {
                float rr = Math.min(w, h) / 2 - in, cx = L + w / 2, cy = T + h / 2;
                c.drawCircle(cx, cy, rr, bg);
                c.drawCircle(cx, cy, rr, p);
                float k = rr * 0.7071f;
                c.drawLine(cx - k, cy - k, cx + k, cy + k, p);
                c.drawLine(cx + k, cy - k, cx - k, cy + k, p);
                c.drawCircle(cx, cy, rr * 0.22f, f);
                break;
            }
            case THERMO: {
                c.drawRoundRect(box, sw, sw, bg);
                c.drawRoundRect(box, sw, sw, p);
                inner(c, "T", r, color, 0.6f);
                break;
            }
            case VALVE: {
                Path v = new Path();
                v.moveTo(box.left, box.top);
                v.lineTo(L + w / 2, T + h / 2);
                v.lineTo(box.left, box.bottom);
                v.close();
                v.moveTo(box.right, box.top);
                v.lineTo(L + w / 2, T + h / 2);
                v.lineTo(box.right, box.bottom);
                v.close();
                c.drawPath(v, bg);
                c.drawPath(v, p);
                break;
            }
            case PUMP: {
                float rr = Math.min(w, h) / 2 - in, cx = L + w / 2, cy = T + h / 2;
                c.drawCircle(cx, cy, rr, bg);
                c.drawCircle(cx, cy, rr, p);
                Path t = new Path();
                t.moveTo(cx - rr * 0.45f, cy - rr * 0.55f);
                t.lineTo(cx + rr * 0.6f, cy);
                t.lineTo(cx - rr * 0.45f, cy + rr * 0.55f);
                t.close();
                c.drawPath(t, f);
                break;
            }
            case PANEL: {
                c.drawRect(box, bg);
                Path t = new Path();
                t.moveTo(box.right, box.top);
                t.lineTo(box.right, box.bottom);
                t.lineTo(box.left, box.bottom);
                t.close();
                c.drawPath(t, f);
                c.drawRect(box, p);
                break;
            }
            case LIGHT:
            default: {
                float rr = Math.min(w, h) / 2 - in, cx = L + w / 2, cy = T + h / 2;
                c.drawCircle(cx, cy, rr, bg);
                c.drawCircle(cx, cy, rr, p);
                float k = rr * 0.7071f;
                c.drawLine(cx - k, cy - k, cx + k, cy + k, p);
                c.drawLine(cx + k, cy - k, cx - k, cy + k, p);
                break;
            }
        }
    }

    private static void inner(Canvas c, String s, RectF r, int color, float frac) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(color);
        float size = r.height() * frac;
        t.setTextSize(100);
        float tw = t.measureText(s) / 100f * size;
        if (tw > r.width() * 0.84f) size *= r.width() * 0.84f / tw;
        t.setTextSize(size);
        c.drawText(s, r.centerX(), r.centerY() + size * 0.36f, t);
    }

    // =====================================================================================
    // Pipes and ducts
    // =====================================================================================

    /** The two side lines of a duct, offset by half its width with mitred corners. */
    static float[][] ductEdges(float[] pts, int n, float hw) {
        int m = n / 2;
        float[] lft = new float[n], rgt = new float[n];
        for (int i = 0; i < m; i++) {
            float nx, ny, len = hw;
            float[] n0 = i > 0 ? normal(pts, i - 1, i) : null;
            float[] n1 = i < m - 1 ? normal(pts, i, i + 1) : null;
            if (n0 == null) {
                nx = n1[0];
                ny = n1[1];
            } else if (n1 == null) {
                nx = n0[0];
                ny = n0[1];
            } else {
                nx = n0[0] + n1[0];
                ny = n0[1] + n1[1];
                float l = (float) Math.hypot(nx, ny);
                if (l < 1e-4f) {
                    nx = n1[0];
                    ny = n1[1];
                } else {
                    nx /= l;
                    ny /= l;
                    float dot = nx * n1[0] + ny * n1[1];
                    len = hw / Math.max(0.35f, dot);
                }
            }
            float x = pts[2 * i], y = pts[2 * i + 1];
            lft[2 * i] = x + nx * len;
            lft[2 * i + 1] = y + ny * len;
            rgt[2 * i] = x - nx * len;
            rgt[2 * i + 1] = y - ny * len;
        }
        return new float[][]{lft, rgt};
    }

    private static float[] normal(float[] p, int a, int b) {
        float dx = p[2 * b] - p[2 * a], dy = p[2 * b + 1] - p[2 * a + 1];
        float l = (float) Math.hypot(dx, dy);
        if (l < 1e-6f) return new float[]{0, -1};
        return new float[]{-dy / l, dx / l};
    }

    static void drawRun(Canvas c, Marks.Run r, int num) {
        if (r.points() < 1) return;
        int color = r.color();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        if (r.points() == 1) {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(r.pts[0], r.pts[1], r.kind == Marks.Run.DUCT ? r.w / 2 : r.w, p);
            return;
        }
        if (r.kind == Marks.Run.DUCT) {
            float[][] e = ductEdges(r.pts, r.n, r.w / 2);
            Path body = new Path();
            body.moveTo(e[0][0], e[0][1]);
            for (int i = 2; i < r.n; i += 2) body.lineTo(e[0][i], e[0][i + 1]);
            for (int i = r.n - 2; i >= 0; i -= 2) body.lineTo(e[1][i], e[1][i + 1]);
            body.close();
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor((color & 0x00FFFFFF) | 0x33000000);
            c.drawPath(body, fill);
            p.setStrokeWidth(Math.max(r.w * 0.1f, r.th * 0.12f));
            p.setStrokeJoin(Paint.Join.MITER);
            c.drawPath(body, p);
        } else {
            p.setStrokeWidth(r.w);
            if (Mep.PIPE_DASH[r.clampSys()]) {
                p.setPathEffect(new DashPathEffect(new float[]{r.w * 4f, r.w * 2.5f}, 0));
            }
            Path path = new Path();
            path.moveTo(r.pts[0], r.pts[1]);
            for (int i = 2; i < r.n; i += 2) path.lineTo(r.pts[i], r.pts[i + 1]);
            c.drawPath(path, p);
        }
        float[] lp = runLabelPos(r, num);
        if (lp != null) {
            c.save();
            c.translate(lp[0], lp[1]);
            c.rotate((float) Math.toDegrees(lp[2]));
            drawLabel(c, r.label(), color, r.th, num);
            c.restore();
        }
    }

    /** Where a run's label goes: {centre x, centre y, angle (radians), width, height}. */
    static float[] runLabelPos(Marks.Run r, int num) {
        if (r.points() < 2) return null;
        int best = 1;
        float bestL = -1;
        for (int i = 1; i < r.points(); i++) {
            float l = (float) Math.hypot(r.pts[2 * i] - r.pts[2 * i - 2], r.pts[2 * i + 1] - r.pts[2 * i - 1]);
            if (l > bestL) {
                bestL = l;
                best = i;
            }
        }
        float ax = r.pts[2 * best - 2], ay = r.pts[2 * best - 1], bx = r.pts[2 * best], by = r.pts[2 * best + 1];
        float ang = (float) Math.atan2(by - ay, bx - ax);
        // Keep the words readable: never upside down.
        if (ang > Math.PI / 2 + 0.01) ang -= (float) Math.PI;
        else if (ang < -Math.PI / 2 - 0.01) ang += (float) Math.PI;
        float[] sz = labelSize(r.label(), r.th, num);
        float off = (r.kind == Marks.Run.DUCT ? r.w / 2 : r.w / 2) + sz[1] / 2 + r.th * 0.15f;
        // "Above" the line in the label's own direction.
        float nx = (float) Math.sin(ang), ny = (float) -Math.cos(ang);
        float cx = (ax + bx) / 2 + nx * off, cy = (ay + by) / 2 + ny * off;
        return new float[]{cx, cy, ang, sz[0], sz[1]};
    }

    // =====================================================================================
    // Labels (text with a white halo, plus a numbered bubble when there is a note)
    // =====================================================================================

    private static TextPaint labelPaint(float th) {
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(th);
        return t;
    }

    /** Width and height of a label, in the same units as th. */
    static float[] labelSize(String text, float th, int num) {
        float w = text.isEmpty() ? 0 : labelPaint(th).measureText(text);
        float h = th * 1.35f;
        if (num > 0) {
            float bd = th * 1.3f;
            w += (text.isEmpty() ? 0 : th * 0.35f) + bd;
            h = Math.max(h, bd);
        }
        return new float[]{Math.max(w, th * 0.5f) + th * 0.3f, h};
    }

    /** Draws a label centred on (0, 0). */
    static void drawLabel(Canvas c, String text, int color, float th, int num) {
        float[] sz = labelSize(text, th, num);
        float x = -sz[0] / 2 + th * 0.15f;
        if (!text.isEmpty()) {
            TextPaint t = labelPaint(th);
            float base = th * 0.36f;
            t.setStyle(Paint.Style.STROKE);
            t.setStrokeWidth(th * 0.28f);
            t.setStrokeJoin(Paint.Join.ROUND);
            t.setColor(0xF2FFFFFF);
            c.drawText(text, x, base, t);
            t.setStyle(Paint.Style.FILL);
            t.setColor(color);
            c.drawText(text, x, base, t);
            x += t.measureText(text) + th * 0.35f;
        }
        if (num > 0) {
            float rr = th * 0.65f, cx = x + rr;
            Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
            b.setColor(0xFFFFFFFF);
            c.drawCircle(cx, 0, rr, b);
            b.setStyle(Paint.Style.STROKE);
            b.setStrokeWidth(th * 0.12f);
            b.setColor(color);
            c.drawCircle(cx, 0, rr, b);
            Paint n = new Paint(Paint.ANTI_ALIAS_FLAG);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextAlign(Paint.Align.CENTER);
            n.setColor(color);
            String s = String.valueOf(num);
            n.setTextSize(th * (s.length() > 2 ? 0.6f : 0.8f));
            c.drawText(s, cx, n.getTextSize() * 0.36f, n);
        }
    }

    /** The text written under a fixture or piece of equipment: its tag, or else its name. */
    static String symLabel(Marks.Stamp s) {
        StringBuilder sb = new StringBuilder();
        if (!s.tag.trim().isEmpty()) sb.append(s.tag.trim());
        else sb.append(SYM_SHORT[Math.max(0, Math.min(SYM_SHORT.length - 1, s.sym))]);
        if (!s.size.trim().isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(s.size.trim());
        }
        return sb.toString();
    }

    /** Draws a placed fixture/equipment: the symbol and, under it, its tag, size and number. */
    static void drawSym(Canvas c, Marks.Stamp s, int num) {
        drawSymbol(c, s.sym, s.rect, s.color);
        String lab = symLabel(s);
        if (lab.isEmpty() && num == 0) return;
        float[] sz = labelSize(lab, s.th, num);
        c.save();
        c.translate(s.rect.centerX(), s.rect.bottom + s.th * 0.15f + sz[1] / 2);
        drawLabel(c, lab, s.color, s.th, num);
        c.restore();
    }

    /** A label drawn into a picture (for saving into a PDF), k pixels per page unit. */
    static Bitmap labelBitmap(String text, int color, float th, int num, float[] sz) {
        float k = Math.min(8f, Math.max(2f, 64f / th));
        int w = Math.max(1, (int) Math.ceil(sz[0] * k)), h = Math.max(1, (int) Math.ceil(sz[1] * k));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        c.translate(sz[0] / 2, sz[1] / 2);
        drawLabel(c, text, color, th, num);
        return b;
    }

    /** A symbol drawn into a picture (for saving into a PDF). */
    static Bitmap symbolBitmap(Marks.Stamp s) {
        float w = s.rect.width(), h = s.rect.height();
        float k = Math.min(6f, 900f / Math.max(w, h));
        int bw = Math.max(1, (int) Math.ceil(w * k)), bh = Math.max(1, (int) Math.ceil(h * k));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        drawSymbol(c, s.sym, new RectF(0, 0, bw, bh), s.color);
        return b;
    }

    // =====================================================================================
    // "Site notes" schedule pages (A4, drawn as pictures so any language shows correctly)
    // =====================================================================================

    static String describe(Marks.Item it) {
        if (it instanceof Marks.Run) return ((Marks.Run) it).describe();
        Marks.Stamp s = (Marks.Stamp) it;
        return SYM_NAMES[Math.max(0, Math.min(SYM_NAMES.length - 1, s.sym))];
    }

    static String sizeOf(Marks.Item it) {
        return it instanceof Marks.Run ? ((Marks.Run) it).size : ((Marks.Stamp) it).size;
    }

    static String tagOf(Marks.Item it) {
        return it instanceof Marks.Run ? ((Marks.Run) it).tag : ((Marks.Stamp) it).tag;
    }

    static String noteOf(Marks.Item it) {
        return it instanceof Marks.Run ? ((Marks.Run) it).note : ((Marks.Stamp) it).note;
    }

    static int colorOf(Marks.Item it) {
        return it instanceof Marks.Run ? ((Marks.Run) it).color() : ((Marks.Stamp) it).color;
    }

    /** One A4 picture per page of the schedule (1240 x 1754 px, 150 dpi). */
    static ArrayList<Bitmap> schedulePages(ArrayList<Object[]> items, String fileName,
                                           boolean multiPage) {
        final int W = 1240, H = 1754, M = 80;
        final int[] colX = {M, M + 70, M + 150, M + 450, M + 610, M + 770};
        final int[] colW = {64, 74, 290, 150, 150, W - M - (M + 770)};
        String[] heads = {"No.", multiPage ? "Page" : "", "Item", "Size", "Tag", "Note"};
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(24);
        tp.setColor(0xFF202124);
        TextPaint hp = new TextPaint(tp);
        hp.setTypeface(Typeface.DEFAULT_BOLD);
        hp.setColor(0xFF5F6368);
        hp.setTextSize(21);
        Paint rule = new Paint();
        rule.setColor(0xFFDADCE0);
        rule.setStrokeWidth(2);
        String date = new java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH)
                .format(new java.util.Date());

        ArrayList<Bitmap> out = new ArrayList<>();
        Bitmap bmp = null;
        Canvas c = null;
        int y = 0;
        for (int idx = 0; idx <= items.size(); idx++) {
            if (bmp == null) {
                bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(0xFFFFFFFF);
                c = new Canvas(bmp);
                TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setTextSize(44);
                title.setColor(0xFF202124);
                c.drawText("Site notes", M, M + 40, title);
                TextPaint sub = new TextPaint(tp);
                sub.setColor(0xFF5F6368);
                c.drawText(fileName + "   ·   " + date + (out.isEmpty() ? "" : "   ·   continued"),
                        M, M + 84, sub);
                y = M + 130;
                for (int k = 0; k < heads.length; k++) c.drawText(heads[k], colX[k], y, hp);
                y += 16;
                c.drawLine(M, y, W - M, y, rule);
                y += 14;
            }
            if (idx == items.size()) break;
            Object[] row = items.get(idx);
            Marks.Item it = (Marks.Item) row[1];
            int num = (Integer) row[2];
            String[] cells = {num > 0 ? String.valueOf(num) : "", multiPage ? String.valueOf((Integer) row[0] + 1) : "",
                    describe(it), sizeOf(it), tagOf(it), noteOf(it)};
            StaticLayout[] ls = new StaticLayout[cells.length];
            int rowH = 0;
            for (int k = 0; k < cells.length; k++) {
                TextPaint p = k == 2 ? new TextPaint(tp) : tp;
                if (k == 2) p.setColor(colorOf(it));
                ls[k] = StaticLayout.Builder.obtain(cells[k], 0, cells[k].length(), p, colW[k])
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                rowH = Math.max(rowH, ls[k].getHeight());
            }
            if (y + rowH + 20 > H - M && y > M + 160) {
                out.add(bmp);
                bmp = null;
                idx--; // draw this row on the next page
                continue;
            }
            for (int k = 0; k < cells.length; k++) {
                c.save();
                c.translate(colX[k], y);
                ls[k].draw(c);
                c.restore();
            }
            y += rowH + 12;
            c.drawLine(M, y, W - M, y, rule);
            y += 12;
        }
        if (bmp != null) out.add(bmp);
        return out;
    }
}
