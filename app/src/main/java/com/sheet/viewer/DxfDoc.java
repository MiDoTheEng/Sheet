package com.sheet.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a DXF drawing (AutoCAD's open exchange format) and draws it fast.
 *
 * The drawing becomes one big "page". Lines are kept in a grid of cells so that, when zoomed in,
 * only the cells on screen are drawn; that keeps panning and zooming smooth on large drawings.
 */
final class DxfDoc {

    static final class CadException extends Exception {
        CadException(String m) {
            super(m);
        }
    }

    static final String DWG_MSG = "This is a DWG file. Sheet reads DXF drawings: in AutoCAD use "
            + "Save As → DXF (or export a PDF), then open that file.";

    private static final int MAX_SEGS = 3_000_000;
    private static final int GRID = 48;
    private static final float PAGE_LONG = 3000f;

    // ---- the finished drawing ----
    float pageW, pageH;
    String[] layerNames;
    /** Layers that have something on them, sorted by name. */
    int[] usedLayers;
    volatile boolean[] hidden;
    volatile boolean mono;
    boolean truncated;
    boolean paperOnly;

    private float cellW, cellH;
    private Batch[][] cells;
    private Batch[] longs;
    private TextP[] texts;
    private FillP[] fills;

    private static final class Batch {
        int layer, rgb;
        float[] segs;
    }

    private static final class TextP {
        int layer, rgb;
        float x, y, ang, h, wf, r;
        String s;
    }

    private static final class FillP {
        int layer, rgb;
        float[][] rings;
        final RectF box = new RectF();
        Path path;
    }

    private static final Paint MEASURE = new Paint();

    static {
        MEASURE.setTextSize(100);
    }

    private DxfDoc() {}

    // =====================================================================================
    // Opening
    // =====================================================================================

    static DxfDoc load(Context ctx, Uri uri) throws Exception {
        byte[] head = new byte[65536];
        int hn = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new CadException("The file could not be read.");
            int r;
            while (hn < head.length && (r = in.read(head, hn, head.length - hn)) > 0) hn += r;
        }
        String hs = new String(head, 0, hn, "ISO-8859-1");
        if (hs.startsWith("AC10")) throw new CadException(DWG_MSG);
        if (hs.startsWith("AutoCAD Binary DXF")) {
            throw new CadException("This is a binary DXF. Save it as an ASCII DXF and open that.");
        }
        if (!hs.contains("SECTION")) throw new CadException("This file is not a PDF or a DXF drawing.");

        Parser p = new Parser();
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new CadException("The file could not be read.");
            p.run(new BufferedReader(new InputStreamReader(in, charset(hs)), 1 << 16));
        }
        return p.build();
    }

    /** Reads DXF text that is already open (also used to check the reader off the device). */
    static DxfDoc parse(BufferedReader r) throws Exception {
        Parser p = new Parser();
        p.run(r);
        return p.build();
    }

    /** A one-line summary of what was read, for checks. */
    String stats() {
        long segs = 0;
        for (Batch[] bs : cells) if (bs != null) for (Batch b : bs) segs += b.segs.length / 4;
        long longSegs = 0;
        for (Batch b : longs) longSegs += b.segs.length / 4;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "page %.1f x %.1f | short segs %d, long segs %d | texts %d | fills %d | layers used:",
                pageW, pageH, segs, longSegs, texts.length, fills.length));
        for (int i : usedLayers) sb.append(' ').append(layerNames[i]).append(hidden[i] ? "(off)" : "");
        for (TextP t : texts) {
            sb.append(String.format(Locale.ROOT, "%n  text \"%s\" at (%.1f, %.1f) h=%.2f ang=%.0f° wf=%.2f layer=%s rgb=%06X",
                    t.s, t.x, t.y, t.h, Math.toDegrees(t.ang), t.wf, layerNames[t.layer], t.rgb));
        }
        for (FillP f : fills) {
            sb.append(String.format(Locale.ROOT, "%n  fill rings=%d pts=%d box=[%.0f,%.0f,%.0f,%.0f] rgb=%06X",
                    f.rings.length, f.rings[0].length / 2, f.box.left, f.box.top, f.box.right, f.box.bottom, f.rgb));
        }
        return sb.toString();
    }

    private static Charset charset(String hs) {
        String ver = headerValue(hs, "$ACADVER");
        if (ver != null && ver.compareTo("AC1021") >= 0) return Charset.forName("UTF-8");
        String cp = headerValue(hs, "$DWGCODEPAGE");
        if (cp != null) {
            cp = cp.toUpperCase(Locale.ROOT);
            if (cp.startsWith("ANSI_")) {
                String name = "windows-" + cp.substring(5);
                try {
                    if (Charset.isSupported(name)) return Charset.forName(name);
                } catch (Exception ignored) {
                }
            }
        }
        try {
            return Charset.forName("windows-1252");
        } catch (Exception e) {
            return Charset.forName("ISO-8859-1");
        }
    }

    private static String headerValue(String hs, String var) {
        int i = hs.indexOf(var);
        if (i < 0) return null;
        String[] parts = hs.substring(i, Math.min(hs.length(), i + 200)).split("\r\n|\n|\r");
        return parts.length >= 3 ? parts[2].trim() : null;
    }

    // =====================================================================================
    // Drawing on screen
    // =====================================================================================

    void render(Bitmap bmp, Matrix m) {
        Canvas c = new Canvas(bmp);
        c.concat(m);
        Matrix inv = new Matrix();
        if (!m.invert(inv)) return;
        RectF vis = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
        inv.mapRect(vis);
        float scale = m.mapRadius(1f);
        boolean[] hid = hidden;
        boolean mo = mono;

        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setStyle(Paint.Style.FILL);
        for (FillP f : fills) {
            if (hid[f.layer] || !RectF.intersects(f.box, vis)) continue;
            if (f.path == null) f.path = toPath(f.rings);
            fp.setColor(fillColor(f.rgb, mo));
            c.drawPath(f.path, fp);
        }

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(0); // hairline: one crisp pixel at any zoom, like CAD
        int x0 = clampCell((int) Math.floor((vis.left - cellW / 2) / cellW));
        int x1 = clampCell((int) Math.floor((vis.right + cellW / 2) / cellW));
        int y0 = clampCell((int) Math.floor((vis.top - cellH / 2) / cellH));
        int y1 = clampCell((int) Math.floor((vis.bottom + cellH / 2) / cellH));
        for (int cy = y0; cy <= y1; cy++) {
            for (int cx = x0; cx <= x1; cx++) {
                Batch[] bs = cells[cy * GRID + cx];
                if (bs == null) continue;
                for (Batch b : bs) {
                    if (hid[b.layer]) continue;
                    lp.setColor(lineColor(b.rgb, mo));
                    c.drawLines(b.segs, lp);
                }
            }
        }
        for (Batch b : longs) {
            if (hid[b.layer]) continue;
            lp.setColor(lineColor(b.rgb, mo));
            c.drawLines(b.segs, lp);
        }

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTextSize(100);
        for (TextP t : texts) {
            if (hid[t.layer]) continue;
            if (t.h * scale < 3f) continue; // too small to read at this zoom: skipped for speed
            if (t.x + t.r < vis.left || t.x - t.r > vis.right
                    || t.y + t.r < vis.top || t.y - t.r > vis.bottom) continue;
            tp.setColor(lineColor(t.rgb, mo));
            c.save();
            c.translate(t.x, t.y);
            if (t.ang != 0) c.rotate((float) -Math.toDegrees(t.ang));
            c.scale(t.h / 100f * t.wf, t.h / 100f);
            c.drawText(t.s, 0, 0, tp);
            c.restore();
        }
    }

    private static int clampCell(int v) {
        return v < 0 ? 0 : v >= GRID ? GRID - 1 : v;
    }

    private static Path toPath(float[][] rings) {
        Path p = new Path();
        p.setFillType(Path.FillType.EVEN_ODD);
        for (float[] r : rings) {
            p.moveTo(r[0], r[1]);
            for (int i = 2; i < r.length; i += 2) p.lineTo(r[i], r[i + 1]);
            p.close();
        }
        return p;
    }

    /** Colors as they look on white paper: white becomes black, pale colors darken. */
    static int lineColor(int rgb, boolean mono) {
        if (mono) return 0xFF000000;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        if (lum > 235) return 0xFF000000;
        if (lum > 170) {
            r = r * 7 / 10;
            g = g * 7 / 10;
            b = b * 7 / 10;
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int fillColor(int rgb, boolean mono) {
        if (mono) {
            int lum = ((((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114) / 1000);
            int gv = 255 - (255 - lum) * 6 / 10;
            return 0xFF000000 | (gv << 16) | (gv << 8) | gv;
        }
        return 0xFF000000 | rgb;
    }

    // =====================================================================================
    // Writing into a PDF (vector lines, so the saved file stays sharp)
    // =====================================================================================

    /** The caller has set up the page so that y goes down from the top-left corner. */
    void writePdf(PDPageContentStream cs, PDFont font) throws IOException {
        boolean[] hid = hidden;
        boolean mo = mono;
        for (FillP f : fills) {
            if (hid[f.layer]) continue;
            color(cs, fillColor(f.rgb, mo), false);
            for (float[] r : f.rings) {
                cs.moveTo(r[0], r[1]);
                for (int i = 2; i < r.length; i += 2) cs.lineTo(r[i], r[i + 1]);
                cs.closePath();
            }
            cs.fillEvenOdd();
        }
        cs.setLineWidth(0.6f);
        cs.setLineCapStyle(1);
        cs.setLineJoinStyle(1);
        for (Batch[] bs : cells) {
            if (bs == null) continue;
            for (Batch b : bs) strokeBatch(cs, b, hid, mo);
        }
        for (Batch b : longs) strokeBatch(cs, b, hid, mo);

        for (TextP t : texts) {
            if (hid[t.layer]) continue;
            String s = encodable(font, t.s);
            if (s.trim().isEmpty()) continue;
            color(cs, lineColor(t.rgb, mo), false);
            double c = Math.cos(t.ang), sn = Math.sin(t.ang);
            cs.beginText();
            cs.setFont(font, 1f);
            cs.setTextMatrix(new com.tom_roush.pdfbox.util.Matrix(
                    (float) (t.h * t.wf * c), (float) (-t.h * t.wf * sn),
                    (float) (-t.h * sn), (float) (-t.h * c), t.x, t.y));
            cs.showText(s);
            cs.endText();
        }
    }

    private static void strokeBatch(PDPageContentStream cs, Batch b, boolean[] hid, boolean mo)
            throws IOException {
        if (hid[b.layer]) return;
        color(cs, lineColor(b.rgb, mo), true);
        float[] s = b.segs;
        for (int i = 0; i + 3 < s.length; i += 4) {
            cs.moveTo(s[i], s[i + 1]);
            cs.lineTo(s[i + 2], s[i + 3]);
        }
        cs.stroke();
    }

    private static void color(PDPageContentStream cs, int argb, boolean stroke) throws IOException {
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        if (stroke) cs.setStrokingColor(r, g, b);
        else cs.setNonStrokingColor(r, g, b);
    }

    private static String encodable(PDFont f, String s) {
        try {
            f.encode(s);
            return s;
        } catch (Exception e) {
            StringBuilder o = new StringBuilder();
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                try {
                    f.encode(ch);
                    o.append(ch);
                } catch (Exception x) {
                    o.append('?');
                }
                i += Character.charCount(cp);
            }
            return o.toString();
        }
    }

    // =====================================================================================
    // Text helpers
    // =====================================================================================

    static double width(String s, double h) {
        synchronized (MEASURE) {
            return MEASURE.measureText(s) * h / 100.0;
        }
    }

    /** \U+XXXX escapes used by older DXF files for non-Latin letters. */
    static String decodeU(String s) {
        if (s.indexOf("\\U+") < 0 && s.indexOf("\\u+") < 0) return s;
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 7 <= s.length() && (s.charAt(i + 1) == 'U' || s.charAt(i + 1) == 'u')
                    && s.charAt(i + 2) == '+') {
                try {
                    o.append((char) Integer.parseInt(s.substring(i + 3, i + 7), 16));
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            o.append(c);
        }
        return o.toString();
    }

    /** %%c diameter, %%d degree, %%p plus-minus, %%u / %%o underline marks. */
    static String percent(String s) {
        if (s.indexOf("%%") < 0) return s;
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && s.charAt(i + 1) == '%') {
                char k = Character.toLowerCase(s.charAt(i + 2));
                if (k == 'c') o.append('Ø');
                else if (k == 'd') o.append('°');
                else if (k == 'p') o.append('±');
                else if (k == '%') o.append('%');
                else if (k == 'u' || k == 'o') {
                    // underline / overline: not drawn
                } else if (Character.isDigit(k)) {
                    int j = i + 2, v = 0;
                    while (j < s.length() && j < i + 5 && Character.isDigit(s.charAt(j))) {
                        v = v * 10 + (s.charAt(j) - '0');
                        j++;
                    }
                    o.append((char) v);
                    i = j - 1;
                    continue;
                } else {
                    o.append("%%").append(s.charAt(i + 2));
                }
                i += 2;
                continue;
            }
            o.append(c);
        }
        return o.toString();
    }

    /** Removes MTEXT formatting codes, keeping the words and line breaks. */
    static String mtextPlain(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                switch (d) {
                    case 'P':
                    case 'n':
                        o.append('\n');
                        i++;
                        break;
                    case '~':
                        o.append(' ');
                        i++;
                        break;
                    case '\\':
                    case '{':
                    case '}':
                        o.append(d);
                        i++;
                        break;
                    case 'S': {
                        int end = s.indexOf(';', i);
                        String st = s.substring(i + 2, end < 0 ? s.length() : end);
                        o.append(st.replace('^', '/').replace('#', '/'));
                        i = end < 0 ? s.length() : end;
                        break;
                    }
                    case 'f': case 'F': case 'H': case 'W': case 'Q': case 'A':
                    case 'C': case 'c': case 'T': case 'p': {
                        int end = s.indexOf(';', i);
                        i = end < 0 ? i + 1 : end;
                        break;
                    }
                    default:
                        i++; // \L \l \O \o \K \k \N and the like: formatting only
                }
            } else if (c != '{' && c != '}') {
                o.append(c);
            }
        }
        return o.toString();
    }

    // =====================================================================================
    // AutoCAD color numbers
    // =====================================================================================

    private static final int[] GRAYS = {0x333333, 0x505050, 0x696969, 0x828282, 0xBEBEBE, 0xFFFFFF};
    private static final float[] BRIGHT = {1f, 0.8f, 0.6f, 0.5f, 0.3f};

    static int aci(int i) {
        switch (i) {
            case 1: return 0xFF0000;
            case 2: return 0xFFFF00;
            case 3: return 0x00FF00;
            case 4: return 0x00FFFF;
            case 5: return 0x0000FF;
            case 6: return 0xFF00FF;
            case 7: return 0x000000; // white on screen, black on paper
            case 8: return 0x808080;
            case 9: return 0xC0C0C0;
        }
        if (i >= 250 && i <= 255) return GRAYS[i - 250];
        if (i >= 10 && i <= 249) {
            int h = (i - 10) / 10, v = (i - 10) % 10;
            return Color.HSVToColor(new float[]{h * 15f, v % 2 == 0 ? 1f : 0.5f, BRIGHT[v / 2]})
                    & 0xFFFFFF;
        }
        return 0;
    }

    static int parseRgb(String s) {
        try {
            return (int) (Long.parseLong(s.trim()) & 0xFFFFFF);
        } catch (Exception e) {
            return 0;
        }
    }

    // =====================================================================================
    // Small containers
    // =====================================================================================

    private static final class FL {
        float[] a = new float[16];
        int n;

        void add(float v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        void add4(float x1, float y1, float x2, float y2) {
            if (n + 4 > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + 4));
            a[n++] = x1;
            a[n++] = y1;
            a[n++] = x2;
            a[n++] = y2;
        }

        float[] trim() {
            return Arrays.copyOf(a, n);
        }
    }

    private static final class DL {
        double[] a = new double[16];
        int n;

        void add1(double v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        void add(double x, double y) {
            add1(x);
            add1(y);
        }

        void addIfNew(double x, double y) {
            if (n >= 2 && a[n - 2] == x && a[n - 1] == y) return;
            add(x, y);
        }

        void addAll(double[] p) {
            for (double v : p) add1(v);
        }

        double[] arr() {
            return Arrays.copyOf(a, n);
        }
    }

    /** One DXF object: its type and all its (code, value) pairs. */
    private static final class E {
        final String type;
        int n;
        int[] codes = new int[24];
        String[] vals = new String[24];

        E(String t) {
            type = t;
        }

        void add(int c, String v) {
            if (n == codes.length) {
                codes = Arrays.copyOf(codes, n * 2);
                vals = Arrays.copyOf(vals, n * 2);
            }
            codes[n] = c;
            vals[n] = v;
            n++;
        }

        int index(int c) {
            for (int i = 0; i < n; i++) if (codes[i] == c) return i;
            return -1;
        }

        boolean has(int c) {
            return index(c) >= 0;
        }

        String str(int c, String def) {
            int i = index(c);
            return i < 0 ? def : vals[i];
        }

        double d(int i) {
            try {
                return Double.parseDouble(vals[i].trim());
            } catch (Exception ex) {
                return 0;
            }
        }

        double dbl(int c, double def) {
            int i = index(c);
            if (i < 0) return def;
            try {
                return Double.parseDouble(vals[i].trim());
            } catch (Exception ex) {
                return def;
            }
        }

        int integer(int c, int def) {
            int i = index(c);
            if (i < 0) return def;
            String v = vals[i].trim();
            try {
                return Integer.parseInt(v);
            } catch (Exception ex) {
                try {
                    return (int) Double.parseDouble(v);
                } catch (Exception x) {
                    return def;
                }
            }
        }

        double[] all(int c) {
            DL o = new DL();
            for (int i = 0; i < n; i++) if (codes[i] == c) o.add1(d(i));
            return o.arr();
        }

        /** Points given as repeated x (cx) / y (cy) codes. */
        double[][] pts(int cx, int cy) {
            DL xs = new DL(), ys = new DL();
            for (int i = 0; i < n; i++) {
                if (codes[i] == cx) {
                    xs.add1(d(i));
                    ys.add1(0);
                } else if (codes[i] == cy && ys.n > 0) {
                    ys.a[ys.n - 1] = d(i);
                }
            }
            return new double[][]{xs.arr(), ys.arr()};
        }
    }

    // =====================================================================================
    // Geometry helpers
    // =====================================================================================

    private static double[] interleave(double[][] xy) {
        double[] o = new double[xy[0].length * 2];
        for (int i = 0; i < xy[0].length; i++) {
            o[2 * i] = xy[0][i];
            o[2 * i + 1] = xy[1][i];
        }
        return o;
    }

    private static void mirrorX(double[] p) {
        for (int i = 0; i < p.length; i += 2) p[i] = -p[i];
    }

    private static void arcPts(DL o, double cx, double cy, double r, double a0, double a1) {
        double sw = a1 - a0;
        int n = Math.max(4, (int) Math.ceil(Math.abs(sw) / (2 * Math.PI) * 72));
        for (int i = 0; i <= n; i++) {
            double a = a0 + sw * i / n;
            o.add(cx + r * Math.cos(a), cy + r * Math.sin(a));
        }
    }

    /** The arc between two polyline points with a "bulge" (excluding both end points). */
    private static void bulgeArc(DL o, double x1, double y1, double x2, double y2, double b) {
        double th = 4 * Math.atan(b);
        double dx = x2 - x1, dy = y2 - y1, chord = Math.hypot(dx, dy);
        if (chord == 0 || th == 0) return;
        double r = chord / (2 * Math.sin(th / 2));
        double dd = r * Math.cos(th / 2);
        double cx = (x1 + x2) / 2 - dy / chord * dd, cy = (y1 + y2) / 2 + dx / chord * dd;
        double a1 = Math.atan2(y1 - cy, x1 - cx), ar = Math.abs(r);
        int n = Math.max(2, (int) Math.ceil(Math.abs(th) / (2 * Math.PI) * 72));
        for (int i = 1; i < n; i++) {
            double a = a1 + th * i / n;
            o.add(cx + ar * Math.cos(a), cy + ar * Math.sin(a));
        }
    }

    private static double[] flattenBulge(double[] xs, double[] ys, double[] bs, int n, boolean closed) {
        DL o = new DL();
        if (n == 0) return o.arr();
        o.add(xs[0], ys[0]);
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % n;
            if (bs[i] != 0) bulgeArc(o, xs[i], ys[i], xs[j], ys[j], bs[i]);
            o.add(xs[j], ys[j]);
        }
        return o.arr();
    }

    /** Points along a B-spline (de Boor), or null if the data doesn't make a valid spline. */
    private static double[] deBoor(int deg, double[] kn, double[] cx, double[] cy, double[] w) {
        int n = cx.length;
        if (deg < 1 || n < deg + 1 || kn.length != n + deg + 1) return null;
        double t0 = kn[deg], t1 = kn[n];
        if (!(t1 > t0)) return null;
        int samples = Math.min(2000, Math.max(16, n * 10));
        double[] out = new double[2 * (samples + 1)];
        double[] dx = new double[deg + 1], dy = new double[deg + 1], dw = new double[deg + 1];
        int k = deg;
        for (int s = 0; s <= samples; s++) {
            double t = t0 + (t1 - t0) * s / samples;
            while (k < n - 1 && t >= kn[k + 1]) k++;
            for (int j = 0; j <= deg; j++) {
                int idx = j + k - deg;
                double ww = w != null ? w[idx] : 1;
                if (ww == 0) ww = 1;
                dx[j] = cx[idx] * ww;
                dy[j] = cy[idx] * ww;
                dw[j] = ww;
            }
            for (int r = 1; r <= deg; r++) {
                for (int j = deg; j >= r; j--) {
                    double den = kn[j + 1 + k - r] - kn[j + k - deg];
                    double al = den == 0 ? 0 : (t - kn[j + k - deg]) / den;
                    dx[j] = (1 - al) * dx[j - 1] + al * dx[j];
                    dy[j] = (1 - al) * dy[j - 1] + al * dy[j];
                    dw[j] = (1 - al) * dw[j - 1] + al * dw[j];
                }
            }
            double ww = dw[deg] == 0 ? 1 : dw[deg];
            out[2 * s] = dx[deg] / ww;
            out[2 * s + 1] = dy[deg] / ww;
        }
        return out;
    }

    private static double[] mul(double[] A, double[] B) {
        return new double[]{
                A[0] * B[0] + A[2] * B[1], A[1] * B[0] + A[3] * B[1],
                A[0] * B[2] + A[2] * B[3], A[1] * B[2] + A[3] * B[3],
                A[0] * B[4] + A[2] * B[5] + A[4], A[1] * B[4] + A[3] * B[5] + A[5]};
    }

    private static final double[] ID = {1, 0, 0, 1, 0, 0};

    // =====================================================================================
    // Parsing
    // =====================================================================================

    private static final class Style {
        String layer = "0";
        int color = 256; // 0 by block, 256 by layer, -1 true color in rgb
        int rgb;
        String ltype;
        double lts = 1;
    }

    private abstract static class Shape {
        Style st;
    }

    private static final class PolyS extends Shape {
        double[] p;
        boolean closed;
    }

    private static final class FillS extends Shape {
        double[][] rings;
    }

    private static final class TextS extends Shape {
        double x, y, ang, h, wf;
        String s;
    }

    private static final class InsS extends Shape {
        String block;
        double ix, iy, rot, sx = 1, sy = 1, ox, oy;
        boolean mir, useBase = true;
    }

    private interface Sink {
        void add(Shape s);
    }

    private static final class Block implements Sink {
        double bx, by;
        final ArrayList<Shape> shapes = new ArrayList<>();

        public void add(Shape s) {
            shapes.add(s);
        }
    }

    private static final class Layer {
        String name;
        int rgb;
        String ltype;
        boolean hidden;
    }

    private static final class Ctx {
        int layer, rgb;
        String ltype;
    }

    private static final class TextW {
        int layer, rgb;
        float x, y, ang, h, wf;
        String s;
    }

    private static final class FillW {
        int layer, rgb;
        float[][] rings;
    }

    /** Cursor for reading the boundary data of a hatch in order. */
    private static final class Cur {
        final E e;
        int i;

        Cur(E e, int i) {
            this.e = e;
            this.i = i;
        }

        double need(int code) {
            for (int k = 0; k < 8 && i < e.n; k++) {
                int c = e.codes[i];
                i++;
                if (c == code) return e.d(i - 1);
            }
            throw new IllegalStateException("hatch");
        }

        int needI(int code) {
            return (int) Math.round(need(code));
        }

        boolean peek(int code) {
            return i < e.n && e.codes[i] == code;
        }
    }

    private static final class Parser {
        private BufferedReader br;
        private int pc;
        private String pv;
        private boolean pending;

        double ltscale = 1;
        final ArrayList<Layer> layers = new ArrayList<>();
        final HashMap<String, Integer> layerIdx = new HashMap<>();
        final HashMap<String, double[]> ltypes = new HashMap<>();
        final HashMap<String, Block> blocks = new HashMap<>();
        final World model = new World(), paper = new World();
        boolean truncated;
        long totalSegs;

        // POLYLINE ... VERTEX ... SEQEND and INSERT ... ATTRIB ... SEQEND
        int seq;
        Sink seqSink;
        E polyE;
        int polyFlags;
        boolean polySkip;
        DL px, py, pb;

        Parser() {
            layer("0");
        }

        int layer(String name) {
            String k = name.toUpperCase(Locale.ROOT);
            Integer i = layerIdx.get(k);
            if (i != null) return i;
            Layer L = new Layer();
            L.name = name;
            layers.add(L);
            layerIdx.put(k, layers.size() - 1);
            return layers.size() - 1;
        }

        // ---- reading (code, value) pairs ----

        private boolean pair() throws IOException {
            String c = br.readLine();
            if (c == null) return false;
            String v = br.readLine();
            if (v == null) return false;
            c = c.trim();
            if (!c.isEmpty() && c.charAt(0) == '﻿') c = c.substring(1).trim();
            if (c.isEmpty()) return false;
            try {
                pc = Integer.parseInt(c);
            } catch (NumberFormatException ex) {
                throw new IOException("This file is not a readable DXF drawing.");
            }
            pv = v;
            return true;
        }

        private E next() throws IOException {
            if (!pending) {
                do {
                    if (!pair()) return null;
                } while (pc != 0);
            }
            pending = false;
            E e = new E(pv.trim());
            while (pair()) {
                if (pc == 0) {
                    pending = true;
                    break;
                }
                e.add(pc, pv);
            }
            return e;
        }

        void run(BufferedReader r) throws IOException {
            br = r;
            String section = null;
            Block cur = null;
            E e;
            while ((e = next()) != null) {
                String t = e.type;
                if (t.equals("SECTION")) {
                    section = e.str(2, "").trim();
                    if (section.equals("HEADER")) header(e);
                    continue;
                }
                if (t.equals("ENDSEC")) {
                    endSeq();
                    section = null;
                    continue;
                }
                if (t.equals("EOF")) break;
                if (section == null) continue;
                switch (section) {
                    case "TABLES":
                        table(e);
                        break;
                    case "BLOCKS":
                        if (t.equals("BLOCK")) {
                            endSeq();
                            cur = new Block();
                            cur.bx = e.dbl(10, 0);
                            cur.by = e.dbl(20, 0);
                            blocks.put(e.str(2, "").trim().toUpperCase(Locale.ROOT), cur);
                        } else if (t.equals("ENDBLK")) {
                            endSeq();
                            cur = null;
                        } else if (cur != null) {
                            entity(e, cur);
                        }
                        break;
                    case "ENTITIES":
                        entity(e, e.integer(67, 0) == 1 ? paper : model);
                        break;
                }
            }
            endSeq();
        }

        private void header(E e) {
            for (int i = 0; i + 1 < e.n; i++) {
                if (e.codes[i] == 9 && e.vals[i].trim().equals("$LTSCALE")) {
                    double v = e.d(i + 1);
                    if (v > 0) ltscale = v;
                }
            }
        }

        private void table(E e) {
            if (e.type.equals("LAYER")) {
                String name = e.str(2, null);
                if (name == null) return;
                Layer L = layers.get(layer(name.trim()));
                int c = e.integer(62, 7);
                String tc = e.str(420, null);
                L.rgb = tc != null ? parseRgb(tc) : aci(Math.abs(c));
                L.ltype = e.str(6, null);
                L.hidden = c < 0 || (e.integer(70, 0) & 1) != 0;
            } else if (e.type.equals("LTYPE")) {
                String name = e.str(2, null);
                if (name == null) return;
                double[] el = e.all(49);
                if (el.length > 0) ltypes.put(name.trim().toUpperCase(Locale.ROOT), el);
            }
        }

        // ---- entities ----

        private static boolean ocsMirror(E e) {
            return e.dbl(230, 1) < 0;
        }

        private Style style(E e) {
            Style st = new Style();
            st.layer = e.str(8, "0").trim();
            layer(st.layer);
            st.color = e.integer(62, 256);
            String tc = e.str(420, null);
            if (tc != null) {
                st.color = -1;
                st.rgb = parseRgb(tc);
            }
            st.ltype = e.str(6, null);
            st.lts = e.dbl(48, 1);
            return st;
        }

        private void entity(E e, Sink s) {
            String t = e.type;
            if (seq == 1) {
                if (t.equals("VERTEX")) {
                    vertex(e);
                    return;
                }
                endSeq();
                if (t.equals("SEQEND")) return;
            } else if (seq == 2) {
                if (t.equals("ATTRIB")) {
                    if (e.integer(60, 0) != 1) text(e, seqSink, true);
                    return;
                }
                seq = 0;
                seqSink = null;
                if (t.equals("SEQEND")) return;
            }
            try {
                if (t.equals("POLYLINE")) {
                    startPoly(e, s);
                    return;
                }
                if (t.equals("INSERT")) {
                    insert(e, s, false);
                    return;
                }
                if (e.integer(60, 0) == 1) return; // invisible
                switch (t) {
                    case "LINE":
                        poly(s, e, new double[]{e.dbl(10, 0), e.dbl(20, 0), e.dbl(11, 0), e.dbl(21, 0)},
                                false);
                        break;
                    case "LWPOLYLINE":
                        lwpoly(e, s);
                        break;
                    case "CIRCLE":
                        circle(e, s);
                        break;
                    case "ARC":
                        arc(e, s);
                        break;
                    case "ELLIPSE":
                        ellipse(e, s);
                        break;
                    case "SPLINE":
                        spline(e, s);
                        break;
                    case "TEXT":
                    case "ATTRIB":
                        text(e, s, t.equals("ATTRIB"));
                        break;
                    case "MTEXT":
                        mtext(e, s);
                        break;
                    case "ACAD_TABLE":
                        insert(e, s, true);
                        break;
                    case "DIMENSION":
                        dimension(e, s);
                        break;
                    case "SOLID":
                    case "TRACE":
                        solid(e, s);
                        break;
                    case "3DFACE":
                        face(e, s);
                        break;
                    case "HATCH":
                        hatch(e, s);
                        break;
                    case "LEADER":
                        leader(e, s);
                        break;
                    case "MULTILEADER":
                    case "MLEADER":
                        mleader(e, s);
                        break;
                }
            } catch (RuntimeException ignored) {
                // One odd object should not stop the whole drawing from opening.
            }
        }

        private void poly(Sink s, E e, double[] p, boolean mir) {
            if (p == null || p.length < 4) return;
            if (mir) mirrorX(p);
            PolyS ps = new PolyS();
            ps.st = style(e);
            ps.p = p;
            s.add(ps);
        }

        private void lwpoly(E e, Sink s) {
            int cnt = 0;
            for (int i = 0; i < e.n; i++) if (e.codes[i] == 10) cnt++;
            if (cnt < 2) return;
            double[] xs = new double[cnt], ys = new double[cnt], bs = new double[cnt];
            int k = -1;
            for (int i = 0; i < e.n; i++) {
                int c = e.codes[i];
                if (c == 10) xs[++k] = e.d(i);
                else if (k >= 0 && c == 20) ys[k] = e.d(i);
                else if (k >= 0 && c == 42) bs[k] = e.d(i);
            }
            boolean closed = (e.integer(70, 0) & 1) != 0;
            poly(s, e, flattenBulge(xs, ys, bs, cnt, closed), ocsMirror(e));
        }

        private void startPoly(E e, Sink s) {
            endSeq();
            seq = 1;
            seqSink = s;
            polyE = e;
            polyFlags = e.integer(70, 0);
            polySkip = e.integer(60, 0) == 1 || (polyFlags & (16 | 64)) != 0;
            px = new DL();
            py = new DL();
            pb = new DL();
        }

        private void vertex(E e) {
            if (polySkip) return;
            if ((e.integer(70, 0) & 16) != 0) return; // spline frame point
            px.add1(e.dbl(10, 0));
            py.add1(e.dbl(20, 0));
            pb.add1(e.dbl(42, 0));
        }

        private void endSeq() {
            if (seq == 1 && !polySkip && polyE != null && px.n >= 2) {
                try {
                    double[] p = flattenBulge(px.arr(), py.arr(), pb.arr(), px.n, (polyFlags & 1) != 0);
                    boolean mir = (polyFlags & 8) == 0 && ocsMirror(polyE);
                    poly(seqSink, polyE, p, mir);
                } catch (RuntimeException ignored) {
                }
            }
            seq = 0;
            seqSink = null;
            polyE = null;
        }

        private void circle(E e, Sink s) {
            double r = e.dbl(40, 0);
            if (r <= 0) return;
            DL o = new DL();
            arcPts(o, e.dbl(10, 0), e.dbl(20, 0), r, 0, 2 * Math.PI);
            poly(s, e, o.arr(), ocsMirror(e));
        }

        private void arc(E e, Sink s) {
            double r = e.dbl(40, 0);
            if (r <= 0) return;
            double a0 = Math.toRadians(e.dbl(50, 0)), a1 = Math.toRadians(e.dbl(51, 360));
            while (a1 <= a0) a1 += 2 * Math.PI;
            DL o = new DL();
            arcPts(o, e.dbl(10, 0), e.dbl(20, 0), r, a0, a1);
            poly(s, e, o.arr(), ocsMirror(e));
        }

        private void ellipse(E e, Sink s) {
            double cx = e.dbl(10, 0), cy = e.dbl(20, 0), mx = e.dbl(11, 1), my = e.dbl(21, 0);
            double ratio = e.dbl(40, 1), t0 = e.dbl(41, 0), t1 = e.dbl(42, 2 * Math.PI);
            while (t1 <= t0) t1 += 2 * Math.PI;
            boolean mir = ocsMirror(e);
            double nx = mir ? my : -my, ny = mir ? -mx : mx;
            int n = Math.max(8, (int) Math.ceil((t1 - t0) / (2 * Math.PI) * 72));
            DL o = new DL();
            for (int i = 0; i <= n; i++) {
                double t = t0 + (t1 - t0) * i / n, c = Math.cos(t), sn = Math.sin(t);
                o.add(cx + mx * c + ratio * nx * sn, cy + my * c + ratio * ny * sn);
            }
            poly(s, e, o.arr(), false);
        }

        private void spline(E e, Sink s) {
            int deg = e.integer(71, 3);
            double[][] ctrl = e.pts(10, 20), fits = e.pts(11, 21);
            double[] w = e.all(41);
            double[] p = null;
            if (ctrl[0].length >= deg + 1) {
                p = deBoor(deg, e.all(40), ctrl[0], ctrl[1], w.length == ctrl[0].length ? w : null);
            }
            if (p == null && fits[0].length >= 2) p = interleave(fits);
            if (p == null && ctrl[0].length >= 2) p = interleave(ctrl);
            poly(s, e, p, false);
        }

        private void text(E e, Sink s, boolean attrib) {
            if (s == null) return;
            if (attrib && (e.integer(70, 0) & 1) != 0) return;
            String str = percent(decodeU(e.str(1, "")));
            if (str.trim().isEmpty()) return;
            double h = e.dbl(40, 1);
            if (h <= 0) h = 1;
            double rot = Math.toRadians(e.dbl(50, 0));
            double wf = e.dbl(41, 1);
            if (wf <= 0) wf = 1;
            int ha = e.integer(72, 0), va = e.integer(attrib ? 74 : 73, 0);
            double px0 = e.dbl(10, 0), py0 = e.dbl(20, 0);
            boolean al = (ha != 0 || va != 0) && e.has(11);
            double ax = al ? e.dbl(11, px0) : px0, ay = al ? e.dbl(21, py0) : py0;
            double w = width(str, h) * wf;
            double bx, by;
            if (al && (ha == 3 || ha == 5)) {
                double L = Math.hypot(ax - px0, ay - py0);
                if (L > 0 && w > 0) {
                    rot = Math.atan2(ay - py0, ax - px0);
                    if (ha == 5) wf *= L / w;
                    else h *= L / w;
                    w = L;
                }
                bx = px0;
                by = py0;
            } else {
                double ox = (ha == 1 || ha == 4) ? -w / 2 : ha == 2 ? -w : 0;
                double oy = ha == 4 ? -0.5 * h : va == 1 ? 0.3 * h : va == 2 ? -0.5 * h
                        : va == 3 ? -h : 0;
                double cs = Math.cos(rot), sn = Math.sin(rot);
                bx = ax + ox * cs - oy * sn;
                by = ay + ox * sn + oy * cs;
            }
            if (ocsMirror(e)) bx = -bx - w * Math.cos(rot);
            TextS t = new TextS();
            t.st = style(e);
            t.x = bx;
            t.y = by;
            t.ang = rot;
            t.h = h;
            t.wf = wf;
            t.s = str;
            s.add(t);
        }

        private void mtext(E e, Sink s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < e.n; i++) if (e.codes[i] == 3) sb.append(e.vals[i]);
            sb.append(e.str(1, ""));
            String plain = percent(mtextPlain(decodeU(sb.toString())));
            double h = e.dbl(40, 1);
            if (h <= 0) h = 1;
            double rot = e.has(11) ? Math.atan2(e.dbl(21, 0), e.dbl(11, 1))
                    : Math.toRadians(e.dbl(50, 0));
            double lf = e.dbl(44, 1);
            if (lf <= 0) lf = 1;
            mtextLines(s, style(e), plain, e.dbl(10, 0), e.dbl(20, 0), rot, h,
                    e.integer(71, 1), e.dbl(41, 0), lf);
        }

        private static ArrayList<String> wrap(String p, double maxW, double h) {
            ArrayList<String> out = new ArrayList<>();
            if (width(p, h) <= maxW * 1.02) {
                out.add(p);
                return out;
            }
            StringBuilder cur = new StringBuilder();
            for (String wd : p.split(" ")) {
                String tryS = cur.length() == 0 ? wd : cur + " " + wd;
                if (cur.length() > 0 && width(tryS, h) > maxW * 1.02) {
                    out.add(cur.toString());
                    cur = new StringBuilder(wd);
                } else {
                    cur.setLength(0);
                    cur.append(tryS);
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }

        private void mtextLines(Sink s, Style st, String plain, double x, double y, double rot,
                                double h, int attach, double refW, double lf) {
            ArrayList<String> lines = new ArrayList<>();
            for (String para : plain.split("\n", -1)) {
                if (refW > 0) lines.addAll(wrap(para, refW, h));
                else lines.add(para);
            }
            while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            if (lines.isEmpty()) return;
            double sp = h * 5.0 / 3.0 * lf;
            double totalH = h + (lines.size() - 1) * sp;
            if (attach < 1 || attach > 9) attach = 1;
            int col = (attach - 1) % 3, row = (attach - 1) / 3;
            double top = row == 0 ? 0 : row == 1 ? totalH / 2 : totalH;
            double cs = Math.cos(rot), sn = Math.sin(rot);
            for (int i = 0; i < lines.size(); i++) {
                String L = lines.get(i);
                if (L.trim().isEmpty()) continue;
                double lw = width(L, h);
                double lx = col == 0 ? 0 : col == 1 ? -lw / 2 : -lw;
                double ly = top - h - i * sp;
                TextS t = new TextS();
                t.st = st;
                t.x = x + lx * cs - ly * sn;
                t.y = y + lx * sn + ly * cs;
                t.ang = rot;
                t.h = h;
                t.wf = 1;
                t.s = L;
                s.add(t);
            }
        }

        private void insert(E e, Sink s, boolean table) {
            boolean vis = e.integer(60, 0) != 1;
            if (!table && e.integer(66, 0) == 1) {
                endSeq();
                seq = 2;
                seqSink = vis ? s : null;
            }
            String name = e.str(2, null);
            if (!vis || name == null) return;
            Style st = style(e);
            boolean mir = ocsMirror(e);
            double ix = e.dbl(10, 0), iy = e.dbl(20, 0);
            if (table) {
                InsS is = new InsS();
                is.st = st;
                is.block = name.trim();
                is.ix = ix;
                is.iy = iy;
                is.mir = mir;
                s.add(is);
                return;
            }
            double sx = e.dbl(41, 1), sy = e.dbl(42, 1), rot = Math.toRadians(e.dbl(50, 0));
            int cols = Math.max(1, e.integer(70, 1)), rows = Math.max(1, e.integer(71, 1));
            if ((long) cols * rows > 5000) {
                cols = 1;
                rows = 1;
            }
            double csp = e.dbl(44, 0), rsp = e.dbl(45, 0);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    InsS is = new InsS();
                    is.st = st;
                    is.block = name.trim();
                    is.ix = ix;
                    is.iy = iy;
                    is.rot = rot;
                    is.sx = sx == 0 ? 1 : sx;
                    is.sy = sy == 0 ? 1 : sy;
                    is.ox = c * csp;
                    is.oy = r * rsp;
                    is.mir = mir;
                    s.add(is);
                }
            }
        }

        private void dimension(E e, Sink s) {
            String name = e.str(2, null);
            if (name == null) return;
            InsS is = new InsS();
            is.st = style(e);
            is.block = name.trim();
            is.useBase = false;
            s.add(is);
        }

        private void solid(E e, Sink s) {
            double x3 = e.dbl(13, e.dbl(12, 0)), y3 = e.dbl(23, e.dbl(22, 0));
            double[] p = {e.dbl(10, 0), e.dbl(20, 0), e.dbl(11, 0), e.dbl(21, 0), x3, y3,
                    e.dbl(12, 0), e.dbl(22, 0)};
            if (ocsMirror(e)) mirrorX(p);
            FillS f = new FillS();
            f.st = style(e);
            f.rings = new double[][]{p};
            s.add(f);
        }

        private void face(E e, Sink s) {
            double[] p = {e.dbl(10, 0), e.dbl(20, 0), e.dbl(11, 0), e.dbl(21, 0), e.dbl(12, 0),
                    e.dbl(22, 0), e.dbl(13, e.dbl(12, 0)), e.dbl(23, e.dbl(22, 0)), e.dbl(10, 0),
                    e.dbl(20, 0)};
            poly(s, e, p, false);
        }

        private static void edgeArc(DL o, double cx, double cy, double r, double a0d, double a1d,
                                    boolean ccw) {
            double a0 = Math.toRadians(a0d), a1 = Math.toRadians(a1d);
            while (a1 <= a0) a1 += 2 * Math.PI;
            int n = Math.max(4, (int) Math.ceil((a1 - a0) / (2 * Math.PI) * 72));
            for (int i = 0; i <= n; i++) {
                double t = a0 + (a1 - a0) * i / n, a = ccw ? t : -t;
                o.addIfNew(cx + r * Math.cos(a), cy + r * Math.sin(a));
            }
        }

        private static void edgeEllipse(DL o, double cx, double cy, double mx, double my,
                                        double ratio, double a0d, double a1d, boolean ccw) {
            double a0 = Math.toRadians(a0d), a1 = Math.toRadians(a1d);
            while (a1 <= a0) a1 += 2 * Math.PI;
            int n = Math.max(6, (int) Math.ceil((a1 - a0) / (2 * Math.PI) * 72));
            for (int i = 0; i <= n; i++) {
                double t = a0 + (a1 - a0) * i / n, a = ccw ? t : -t;
                double c = Math.cos(a), sn = Math.sin(a);
                o.addIfNew(cx + mx * c - ratio * my * sn, cy + my * c + ratio * mx * sn);
            }
        }

        /** Solid-filled hatches (walls, filled areas). Pattern hatches are left out. */
        private void hatch(E e, Sink s) {
            boolean solid = e.integer(70, 0) == 1 || e.integer(450, 0) == 1;
            if (!solid) return;
            int start = e.index(91);
            if (start < 0) return;
            Cur c = new Cur(e, start);
            int np = c.needI(91);
            ArrayList<double[]> rings = new ArrayList<>();
            for (int pth = 0; pth < np; pth++) {
                int flags = c.needI(92);
                DL o = new DL();
                if ((flags & 2) != 0) {
                    boolean hb = c.needI(72) != 0;
                    c.needI(73);
                    int nv = c.needI(93);
                    double[] xs = new double[nv], ys = new double[nv], bs = new double[nv];
                    for (int v = 0; v < nv; v++) {
                        xs[v] = c.need(10);
                        ys[v] = c.need(20);
                        if (hb && c.peek(42)) bs[v] = c.need(42);
                    }
                    o.addAll(flattenBulge(xs, ys, bs, nv, true));
                } else {
                    int ne = c.needI(93);
                    for (int k = 0; k < ne; k++) {
                        int type = c.needI(72);
                        if (type == 1) {
                            double x1 = c.need(10), y1 = c.need(20), x2 = c.need(11), y2 = c.need(21);
                            o.addIfNew(x1, y1);
                            o.addIfNew(x2, y2);
                        } else if (type == 2) {
                            double cx = c.need(10), cy = c.need(20), r = c.need(40);
                            double a0 = c.need(50), a1 = c.need(51);
                            edgeArc(o, cx, cy, r, a0, a1, c.needI(73) != 0);
                        } else if (type == 3) {
                            double cx = c.need(10), cy = c.need(20), mx = c.need(11), my = c.need(21);
                            double ratio = c.need(40), a0 = c.need(50), a1 = c.need(51);
                            edgeEllipse(o, cx, cy, mx, my, ratio, a0, a1, c.needI(73) != 0);
                        } else if (type == 4) {
                            int deg = c.needI(94);
                            boolean rat = c.needI(73) != 0;
                            c.needI(74);
                            int nk = c.needI(95), nc = c.needI(96);
                            double[] kn = new double[nk];
                            for (int j = 0; j < nk; j++) kn[j] = c.need(40);
                            double[] sx = new double[nc], sy = new double[nc];
                            double[] sw = rat ? new double[nc] : null;
                            for (int j = 0; j < nc; j++) {
                                sx[j] = c.need(10);
                                sy[j] = c.need(20);
                                if (rat) sw[j] = c.peek(42) ? c.need(42) : 1;
                            }
                            if (c.peek(97)) {
                                int nf = c.needI(97);
                                for (int j = 0; j < nf; j++) {
                                    c.need(11);
                                    c.need(21);
                                }
                            }
                            if (c.peek(12)) {
                                c.need(12);
                                c.need(22);
                                c.need(13);
                                c.need(23);
                            }
                            double[] sp = deBoor(deg, kn, sx, sy, sw);
                            if (sp != null) for (int j = 0; j < sp.length; j += 2) o.addIfNew(sp[j], sp[j + 1]);
                        } else {
                            throw new IllegalStateException("hatch edge");
                        }
                    }
                }
                if (c.peek(97)) {
                    int ns = c.needI(97);
                    for (int k = 0; k < ns && c.peek(330); k++) c.i++;
                }
                double[] ring = o.arr();
                if (ring.length >= 6) rings.add(ring);
            }
            if (rings.isEmpty()) return;
            if (ocsMirror(e)) for (double[] r : rings) mirrorX(r);
            FillS f = new FillS();
            f.st = style(e);
            f.rings = rings.toArray(new double[0][]);
            s.add(f);
        }

        private void leader(E e, Sink s) {
            double[][] v = e.pts(10, 20);
            if (v[0].length >= 2) poly(s, e, interleave(v), false);
        }

        /** Multileaders: the label text, the leader lines and the arrow heads. */
        private void mleader(E e, Sink s) {
            Style st = style(e);
            int state = 0;
            boolean ctxEnded = false;
            String txt = null;
            double tx = 0, ty = 0, th = 0, dirx = 1, diry = 0, arrow = 0;
            boolean hasPos = false;
            double lastX = 0, lastY = 0, dvx = 0, dvy = 0, dlen = 0, pendX = 0;
            boolean hasLast = false;
            DL line = null;
            ArrayList<double[]> lines = new ArrayList<>(), doglegs = new ArrayList<>();
            for (int i = 0; i < e.n; i++) {
                int c = e.codes[i];
                String v = e.vals[i].trim();
                if (c == 302 && v.startsWith("LEADER{")) {
                    state = 1;
                    hasLast = false;
                    dlen = 0;
                    continue;
                }
                if (c == 304 && v.startsWith("LEADER_LINE{")) {
                    state = 2;
                    line = new DL();
                    continue;
                }
                if (c == 305 && state == 2) {
                    if (line != null) {
                        if (hasLast) line.add(lastX, lastY);
                        lines.add(line.arr());
                    }
                    line = null;
                    state = 1;
                    continue;
                }
                if (c == 303 && state == 1) {
                    if (hasLast && dlen != 0) {
                        doglegs.add(new double[]{lastX, lastY, lastX + dvx * dlen, lastY + dvy * dlen});
                    }
                    state = 0;
                    continue;
                }
                if (c == 301) {
                    ctxEnded = true;
                    continue;
                }
                if (state == 0) {
                    if (ctxEnded) continue;
                    if (c == 304 && txt == null) txt = e.vals[i];
                    else if (c == 12) {
                        tx = e.d(i);
                        hasPos = true;
                    } else if (c == 22) ty = e.d(i);
                    else if (c == 13) dirx = e.d(i);
                    else if (c == 23) diry = e.d(i);
                    else if (c == 41 && th == 0) th = e.d(i);
                    else if (c == 140 && arrow == 0) arrow = e.d(i);
                } else if (state == 1) {
                    if (c == 10) lastX = e.d(i);
                    else if (c == 20) {
                        lastY = e.d(i);
                        hasLast = true;
                    } else if (c == 11) dvx = e.d(i);
                    else if (c == 21) dvy = e.d(i);
                    else if (c == 40) dlen = e.d(i);
                } else {
                    if (c == 10) pendX = e.d(i);
                    else if (c == 20 && line != null) line.add(pendX, e.d(i));
                }
            }
            for (double[] L : lines) {
                if (L.length < 4) continue;
                PolyS ps = new PolyS();
                ps.st = st;
                ps.p = L;
                s.add(ps);
                if (arrow > 0) {
                    double x0 = L[0], y0 = L[1], ux = L[2] - x0, uy = L[3] - y0;
                    double len = Math.hypot(ux, uy);
                    if (len > 0) {
                        ux /= len;
                        uy /= len;
                        double bx = x0 + ux * arrow, by = y0 + uy * arrow, hw = arrow / 6;
                        FillS f = new FillS();
                        f.st = st;
                        f.rings = new double[][]{{x0, y0, bx - uy * hw, by + ux * hw, bx + uy * hw, by - ux * hw}};
                        s.add(f);
                    }
                }
            }
            for (double[] L : doglegs) {
                PolyS ps = new PolyS();
                ps.st = st;
                ps.p = L;
                s.add(ps);
            }
            if (txt != null && hasPos) {
                String plain = percent(mtextPlain(decodeU(txt)));
                mtextLines(s, st, plain, tx, ty, Math.atan2(diry, dirx), th > 0 ? th : 1, 1, 0, 1);
            }
        }

        // ---- placing everything in the drawing's own coordinates ----

        final class World implements Sink {
            final HashMap<Long, FL> segs = new HashMap<>();
            final ArrayList<TextW> texts = new ArrayList<>();
            final ArrayList<FillW> fills = new ArrayList<>();
            boolean hasOrigin;
            double ox, oy;
            long segCount;

            public void add(Shape sh) {
                emit(sh, ID, null, 0);
            }

            boolean hasContent() {
                return segCount > 0 || !texts.isEmpty() || !fills.isEmpty();
            }

            private void org(double x, double y) {
                if (!hasOrigin) {
                    hasOrigin = true;
                    ox = x;
                    oy = y;
                }
            }

            private int layerOf(Style st, Ctx c) {
                if (c != null && st.layer.equals("0")) return c.layer;
                return layer(st.layer);
            }

            private int rgbOf(Style st, Ctx c, int L) {
                if (st.color == -1) return st.rgb;
                if (st.color == 0) return c != null ? c.rgb : 0;
                if (st.color < 0 || st.color >= 256) return layers.get(L).rgb;
                return aci(st.color);
            }

            private String ltName(Style st, Ctx c, int L) {
                String n = st.ltype;
                if (n == null || n.trim().equalsIgnoreCase("BYLAYER")) n = layers.get(L).ltype;
                else if (n.trim().equalsIgnoreCase("BYBLOCK")) n = c != null ? c.ltype : null;
                return n == null ? null : n.trim();
            }

            void emit(Shape sh, double[] M, Ctx c, int depth) {
                if (truncated) return;
                int L = layerOf(sh.st, c);
                if (sh instanceof PolyS) poly((PolyS) sh, M, c, L);
                else if (sh instanceof TextS) text((TextS) sh, M, c, L);
                else if (sh instanceof FillS) fill((FillS) sh, M, c, L);
                else if (sh instanceof InsS) ins((InsS) sh, M, c, L, depth);
            }

            private FL batch(int L, int rgb) {
                long key = ((long) L << 32) | (rgb & 0xFFFFFFFFL);
                FL f = segs.get(key);
                if (f == null) {
                    f = new FL();
                    segs.put(key, f);
                }
                return f;
            }

            private void seg(FL out, float x1, float y1, float x2, float y2) {
                if (totalSegs >= MAX_SEGS) {
                    truncated = true;
                    return;
                }
                totalSegs++;
                segCount++;
                out.add4(x1, y1, x2, y2);
            }

            private void poly(PolyS p, double[] M, Ctx c, int L) {
                int n = p.p.length / 2;
                float[] q = new float[(n + (p.closed ? 1 : 0)) * 2];
                for (int i = 0; i < n; i++) {
                    double x = p.p[2 * i], y = p.p[2 * i + 1];
                    double wx = M[0] * x + M[2] * y + M[4], wy = M[1] * x + M[3] * y + M[5];
                    org(wx, wy);
                    q[2 * i] = (float) (wx - ox);
                    q[2 * i + 1] = (float) (wy - oy);
                }
                if (p.closed) {
                    q[2 * n] = q[0];
                    q[2 * n + 1] = q[1];
                }
                FL out = batch(L, rgbOf(p.st, c, L));
                String lt = ltName(p.st, c, L);
                double[] pat = lt == null ? null : ltypes.get(lt.toUpperCase(Locale.ROOT));
                if (pat != null) {
                    double sc = Math.sqrt(Math.abs(M[0] * M[3] - M[1] * M[2])) * p.st.lts * ltscale;
                    dash(q, out, pat, sc);
                } else {
                    for (int i = 2; i < q.length; i += 2) seg(out, q[i - 2], q[i - 1], q[i], q[i + 1]);
                }
            }

            private void dash(float[] q, FL out, double[] pat, double sc) {
                double total = 0;
                for (double v : pat) total += Math.abs(v);
                total *= sc;
                double len = 0;
                for (int i = 2; i < q.length; i += 2) len += Math.hypot(q[i] - q[i - 2], q[i + 1] - q[i - 1]);
                if (total <= 0 || len / total > 4000) {
                    for (int i = 2; i < q.length; i += 2) seg(out, q[i - 2], q[i - 1], q[i], q[i + 1]);
                    return;
                }
                double dot = Math.max(total * 0.004, 1e-9);
                int pi = 0;
                double rem = Math.abs(pat[0]) * sc;
                boolean on = pat[0] >= 0;
                if (rem <= 0) rem = dot;
                for (int i = 2; i < q.length; i += 2) {
                    double x1 = q[i - 2], y1 = q[i - 1], x2 = q[i], y2 = q[i + 1];
                    double L = Math.hypot(x2 - x1, y2 - y1);
                    if (L == 0) continue;
                    double ux = (x2 - x1) / L, uy = (y2 - y1) / L, pos = 0;
                    int guard = 0;
                    while (pos < L && guard++ < 200000) {
                        double step = Math.min(rem, L - pos);
                        if (on) {
                            seg(out, (float) (x1 + ux * pos), (float) (y1 + uy * pos),
                                    (float) (x1 + ux * (pos + step)), (float) (y1 + uy * (pos + step)));
                        }
                        pos += step;
                        rem -= step;
                        if (rem <= 1e-12) {
                            pi = (pi + 1) % pat.length;
                            double v = pat[pi];
                            on = v >= 0;
                            rem = Math.abs(v) * sc;
                            if (rem <= 0) rem = dot;
                        }
                    }
                }
            }

            private void text(TextS t, double[] M, Ctx c, int L) {
                double cs = Math.cos(t.ang), sn = Math.sin(t.ang);
                double dx = M[0] * cs + M[2] * sn, dy = M[1] * cs + M[3] * sn;
                double ux = -M[0] * sn + M[2] * cs, uy = -M[1] * sn + M[3] * cs;
                double dl = Math.hypot(dx, dy), ul = Math.hypot(ux, uy);
                if (dl == 0 || ul == 0) return;
                double wx = M[0] * t.x + M[2] * t.y + M[4], wy = M[1] * t.x + M[3] * t.y + M[5];
                org(wx, wy);
                TextW w = new TextW();
                w.layer = L;
                w.rgb = rgbOf(t.st, c, L);
                w.x = (float) (wx - ox);
                w.y = (float) (wy - oy);
                w.ang = (float) Math.atan2(dy, dx);
                w.h = (float) (t.h * ul);
                w.wf = (float) (t.wf * dl / ul);
                w.s = t.s;
                texts.add(w);
            }

            private void fill(FillS f, double[] M, Ctx c, int L) {
                float[][] rings = new float[f.rings.length][];
                for (int r = 0; r < rings.length; r++) {
                    double[] p = f.rings[r];
                    float[] q = new float[p.length];
                    for (int i = 0; i + 1 < p.length; i += 2) {
                        double wx = M[0] * p[i] + M[2] * p[i + 1] + M[4];
                        double wy = M[1] * p[i] + M[3] * p[i + 1] + M[5];
                        org(wx, wy);
                        q[i] = (float) (wx - ox);
                        q[i + 1] = (float) (wy - oy);
                    }
                    rings[r] = q;
                }
                FillW w = new FillW();
                w.layer = L;
                w.rgb = rgbOf(f.st, c, L);
                w.rings = rings;
                fills.add(w);
            }

            private void ins(InsS is, double[] M, Ctx c, int L, int depth) {
                if (depth > 16) return;
                Block b = blocks.get(is.block.toUpperCase(Locale.ROOT));
                if (b == null || b.shapes.isEmpty()) return;
                Ctx k = new Ctx();
                k.layer = L;
                k.rgb = rgbOf(is.st, c, L);
                k.ltype = ltName(is.st, c, L);
                double[] T = M;
                if (is.mir) T = mul(T, new double[]{-1, 0, 0, 1, 0, 0});
                T = mul(T, new double[]{1, 0, 0, 1, is.ix, is.iy});
                if (is.rot != 0) {
                    double cs = Math.cos(is.rot), sn = Math.sin(is.rot);
                    T = mul(T, new double[]{cs, sn, -sn, cs, 0, 0});
                }
                if (is.ox != 0 || is.oy != 0) T = mul(T, new double[]{1, 0, 0, 1, is.ox, is.oy});
                T = mul(T, new double[]{is.sx, 0, 0, is.sy, 0, 0});
                if (is.useBase) T = mul(T, new double[]{1, 0, 0, 1, -b.bx, -b.by});
                for (Shape sh : b.shapes) {
                    if (truncated) return;
                    emit(sh, T, k, depth + 1);
                }
            }
        }

        // ---- the finished drawing ----

        @SuppressWarnings("unchecked")
        DxfDoc build() throws CadException {
            World w = model.hasContent() ? model : paper;
            if (!w.hasContent()) throw new CadException("This drawing has nothing Sheet can show.");
            DxfDoc d = new DxfDoc();
            d.truncated = truncated;
            d.paperOnly = w == paper;

            // Extents, ignoring a few stray objects far away from the drawing.
            long total = w.segCount * 2 + w.texts.size() + w.fills.size();
            int step = (int) Math.max(1, total / 200000);
            FL xs = new FL(), ys = new FL();
            int k = 0;
            for (FL f : w.segs.values()) {
                for (int i = 0; i + 1 < f.n; i += 2) {
                    if (k++ % step == 0) {
                        xs.add(f.a[i]);
                        ys.add(f.a[i + 1]);
                    }
                }
            }
            for (TextW t : w.texts) {
                xs.add(t.x);
                ys.add(t.y);
            }
            for (FillW f : w.fills) {
                xs.add(f.rings[0][0]);
                ys.add(f.rings[0][1]);
            }
            float[] sx = xs.trim(), sy = ys.trim();
            Arrays.sort(sx);
            Arrays.sort(sy);
            int nn = sx.length;
            float lx = sx[(int) (nn * 0.002)], hx = sx[Math.min(nn - 1, (int) (nn * 0.998))];
            float ly = sy[(int) (nn * 0.002)], hy = sy[Math.min(nn - 1, (int) (nn * 0.998))];
            float pad = Math.max(Math.max(hx - lx, hy - ly), 1e-3f) * 0.25f;
            float wl = lx - pad, wr = hx + pad, wt = ly - pad, wb = hy + pad;

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (FL f : w.segs.values()) {
                for (int i = 0; i + 1 < f.n; i += 2) {
                    float x = f.a[i], y = f.a[i + 1];
                    if (x < wl || x > wr || y < wt || y > wb) continue;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            for (TextW t : w.texts) {
                if (t.x < wl || t.x > wr || t.y < wt || t.y > wb) continue;
                double tw = width(t.s, t.h) * t.wf;
                minX = Math.min(minX, t.x);
                maxX = Math.max(maxX, t.x + tw);
                minY = Math.min(minY, t.y);
                maxY = Math.max(maxY, t.y + t.h);
            }
            for (FillW f : w.fills) {
                for (float[] r : f.rings) {
                    for (int i = 0; i + 1 < r.length; i += 2) {
                        float x = r[i], y = r[i + 1];
                        if (x < wl || x > wr || y < wt || y > wb) continue;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (minX > maxX || minY > maxY) {
                minX = wl;
                maxX = wr;
                minY = wt;
                maxY = wb;
            }
            double span = Math.max(Math.max(maxX - minX, maxY - minY), 1e-6);
            double margin = span * 0.02;
            minX -= margin;
            maxX += margin;
            minY -= margin;
            maxY += margin;
            double kk = PAGE_LONG / Math.max(maxX - minX, maxY - minY);
            double need = 60 / kk;
            if (maxX - minX < need) {
                double ex = (need - (maxX - minX)) / 2;
                minX -= ex;
                maxX += ex;
            }
            if (maxY - minY < need) {
                double ex = (need - (maxY - minY)) / 2;
                minY -= ex;
                maxY += ex;
            }
            final float K = (float) kk, X0 = (float) minX, Y1 = (float) maxY;
            d.pageW = (float) ((maxX - minX) * kk);
            d.pageH = (float) ((maxY - minY) * kk);
            float pw = d.pageW, ph = d.pageH;
            d.cellW = pw / GRID;
            d.cellH = ph / GRID;

            boolean[] used = new boolean[layers.size()];

            // Lines into the grid.
            HashMap<Long, FL>[] cellMaps = new HashMap[GRID * GRID];
            HashMap<Long, FL> longMap = new HashMap<>();
            Iterator<Map.Entry<Long, FL>> it = w.segs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, FL> en = it.next();
                long key = en.getKey();
                FL f = en.getValue();
                it.remove();
                int layer = (int) (key >>> 32);
                for (int i = 0; i + 3 < f.n; i += 4) {
                    float x1 = (f.a[i] - X0) * K, y1 = (Y1 - f.a[i + 1]) * K;
                    float x2 = (f.a[i + 2] - X0) * K, y2 = (Y1 - f.a[i + 3]) * K;
                    if (Math.max(x1, x2) < 0 || Math.min(x1, x2) > pw
                            || Math.max(y1, y2) < 0 || Math.min(y1, y2) > ph) continue;
                    used[layer] = true;
                    FL t;
                    if (Math.abs(x2 - x1) <= d.cellW && Math.abs(y2 - y1) <= d.cellH) {
                        int cx = clampCell((int) ((x1 + x2) / 2 / d.cellW));
                        int cy = clampCell((int) ((y1 + y2) / 2 / d.cellH));
                        HashMap<Long, FL> m = cellMaps[cy * GRID + cx];
                        if (m == null) {
                            m = new HashMap<>();
                            cellMaps[cy * GRID + cx] = m;
                        }
                        t = m.get(key);
                        if (t == null) {
                            t = new FL();
                            m.put(key, t);
                        }
                    } else {
                        t = longMap.get(key);
                        if (t == null) {
                            t = new FL();
                            longMap.put(key, t);
                        }
                    }
                    t.add4(x1, y1, x2, y2);
                }
            }
            d.cells = new Batch[GRID * GRID][];
            for (int i = 0; i < cellMaps.length; i++) {
                if (cellMaps[i] != null) d.cells[i] = toBatches(cellMaps[i]);
                cellMaps[i] = null;
            }
            d.longs = toBatches(longMap);

            // Text.
            ArrayList<TextP> tl = new ArrayList<>();
            for (TextW t : w.texts) {
                TextP p = new TextP();
                p.x = (t.x - X0) * K;
                p.y = (Y1 - t.y) * K;
                p.h = t.h * K;
                if (p.x < -pw || p.x > 2 * pw || p.y < -ph || p.y > 2 * ph) continue;
                p.ang = t.ang;
                p.wf = t.wf;
                p.s = t.s;
                p.layer = t.layer;
                p.rgb = t.rgb;
                p.r = (float) (width(t.s, p.h) * t.wf + p.h);
                used[t.layer] = true;
                tl.add(p);
            }
            d.texts = tl.toArray(new TextP[0]);

            // Fills.
            ArrayList<FillP> fl = new ArrayList<>();
            for (FillW f : w.fills) {
                FillP p = new FillP();
                p.layer = f.layer;
                p.rgb = f.rgb;
                p.rings = new float[f.rings.length][];
                boolean first = true;
                for (int r = 0; r < f.rings.length; r++) {
                    float[] src = f.rings[r], q = new float[src.length];
                    for (int i = 0; i + 1 < src.length; i += 2) {
                        q[i] = (src[i] - X0) * K;
                        q[i + 1] = (Y1 - src[i + 1]) * K;
                        if (first) {
                            p.box.set(q[i], q[i + 1], q[i], q[i + 1]);
                            first = false;
                        } else {
                            p.box.union(q[i], q[i + 1]);
                        }
                    }
                    p.rings[r] = q;
                }
                if (first || p.box.right < 0 || p.box.left > pw || p.box.bottom < 0 || p.box.top > ph) {
                    continue;
                }
                used[f.layer] = true;
                fl.add(p);
            }
            d.fills = fl.toArray(new FillP[0]);

            // Layers.
            d.layerNames = new String[layers.size()];
            boolean[] hid = new boolean[layers.size()];
            ArrayList<Integer> ul = new ArrayList<>();
            for (int i = 0; i < layers.size(); i++) {
                d.layerNames[i] = layers.get(i).name;
                hid[i] = layers.get(i).hidden;
                if (used[i]) ul.add(i);
            }
            final String[] names = d.layerNames;
            Collections.sort(ul, (a, b) -> names[a].compareToIgnoreCase(names[b]));
            d.usedLayers = new int[ul.size()];
            for (int i = 0; i < ul.size(); i++) d.usedLayers[i] = ul.get(i);
            d.hidden = hid;
            return d;
        }

        private static Batch[] toBatches(HashMap<Long, FL> m) {
            Batch[] out = new Batch[m.size()];
            int i = 0;
            for (Map.Entry<Long, FL> en : m.entrySet()) {
                Batch b = new Batch();
                long key = en.getKey();
                b.layer = (int) (key >>> 32);
                b.rgb = (int) key;
                b.segs = en.getValue().trim();
                out[i++] = b;
            }
            return out;
        }
    }
}
