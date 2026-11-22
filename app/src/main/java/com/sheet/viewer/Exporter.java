package com.sheet.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Writes the marks into the PDF itself. Pen lines stay as real lines (sharp at any zoom);
 * signatures and stamps go in as pictures. The drawing underneath is not touched.
 */
final class Exporter {
    private Exporter() {}

    /** Builds the marked-up PDF as a temporary file inside the app. */
    static File build(Context ctx, Uri src, Marks marks, boolean schedule, String title) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());
        File tmp = new File(ctx.getCacheDir(), "export.pdf");
        try (InputStream in = ctx.getContentResolver().openInputStream(src);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            applyMarks(ctx, doc, marks);
            if (schedule) addSchedule(doc, marks, title);
            doc.save(tmp);
        }
        return tmp;
    }

    /**
     * Turns a DXF drawing into a PDF (real vector lines, so it stays sharp and prints well)
     * with the marks on top. Hidden layers stay hidden.
     */
    static File buildCad(Context ctx, DxfDoc d, Marks marks, String title) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());
        File tmp = new File(ctx.getCacheDir(), "export.pdf");
        try (PDDocument doc = new PDDocument(MemoryUsageSetting.setupTempFileOnly())) {
            PDPage page = new PDPage(new PDRectangle(d.pageW, d.pageH));
            doc.addPage(page);
            PDFont font = null;
            try {
                File f = new File("/system/fonts/Roboto-Regular.ttf");
                if (f.exists()) font = PDType0Font.load(doc, f);
            } catch (Throwable ignored) {
            }
            if (font == null) font = PDType1Font.HELVETICA;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.OVERWRITE, true)) {
                cs.transform(new Matrix(1, 0, 0, -1, 0, d.pageH));
                d.writePdf(cs, font);
            }
            applyMarks(ctx, doc, marks);
            addSchedule(doc, marks, title);
            doc.save(tmp);
        }
        return tmp;
    }

    /** Adds "Site notes" pages listing every pipe, duct, fixture and piece of equipment. */
    private static void addSchedule(PDDocument doc, Marks marks, String title) throws Exception {
        ArrayList<Object[]> items = marks.siteItems();
        if (items.isEmpty()) return;
        boolean multi = doc.getNumberOfPages() > 1;
        for (Bitmap b : Mep.schedulePages(items, title, multi)) {
            PDPage pg = new PDPage(PDRectangle.A4);
            doc.addPage(pg);
            PDImageXObject img = LosslessFactory.createFromImage(doc, b);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pg)) {
                cs.drawImage(img, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            b.recycle();
        }
    }

    /** A picture placed centred at (cx, cy), turned by ang radians, w by h page units. */
    private static void image(PDDocument doc, PDPageContentStream cs, Bitmap b, float cx, float cy,
                              float ang, float w, float h) throws Exception {
        PDImageXObject img = LosslessFactory.createFromImage(doc, b);
        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(cx, cy));
        if (ang != 0) cs.transform(Matrix.getRotateInstance(ang, 0, 0));
        cs.transform(new Matrix(1, 0, 0, -1, -w / 2, h / 2));
        cs.drawImage(img, 0, 0, w, h);
        cs.restoreGraphicsState();
        b.recycle();
    }

    private static void rgb(PDPageContentStream cs, int color, boolean stroke) throws Exception {
        float r = ((color >> 16) & 255) / 255f, g = ((color >> 8) & 255) / 255f, b = (color & 255) / 255f;
        if (stroke) cs.setStrokingColor(r, g, b);
        else cs.setNonStrokingColor(r, g, b);
    }

    /** A pipe or duct: real vector lines, with its label as a picture. */
    private static void run(PDDocument doc, PDPageContentStream cs, Marks.Run r, int num) throws Exception {
        if (r.points() < 2) return;
        int color = r.color();
        cs.saveGraphicsState();
        cs.setLineCapStyle(1);
        cs.setLineJoinStyle(1);
        if (r.kind == Marks.Run.DUCT) {
            float[][] e = Mep.ductEdges(r.pts, r.n, r.w / 2);
            cs.saveGraphicsState();
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.2f);
            cs.setGraphicsStateParameters(gs);
            rgb(cs, color, false);
            ductPath(cs, e, r.n);
            cs.fill();
            cs.restoreGraphicsState();
            rgb(cs, color, true);
            cs.setLineJoinStyle(0);
            cs.setLineWidth(Math.max(r.w * 0.1f, r.th * 0.12f));
            ductPath(cs, e, r.n);
            cs.stroke();
        } else {
            rgb(cs, color, true);
            cs.setLineWidth(r.w);
            if (Mep.PIPE_DASH[r.clampSys()]) cs.setLineDashPattern(new float[]{r.w * 4f, r.w * 2.5f}, 0);
            cs.moveTo(r.pts[0], r.pts[1]);
            for (int i = 2; i + 1 < r.n; i += 2) cs.lineTo(r.pts[i], r.pts[i + 1]);
            cs.stroke();
        }
        cs.restoreGraphicsState();
        float[] lp = Mep.runLabelPos(r, num);
        if (lp != null) {
            image(doc, cs, Mep.labelBitmap(r.label(), color, r.th, num, new float[]{lp[3], lp[4]}),
                    lp[0], lp[1], lp[2], lp[3], lp[4]);
        }
    }

    private static void ductPath(PDPageContentStream cs, float[][] e, int n) throws Exception {
        cs.moveTo(e[0][0], e[0][1]);
        for (int i = 2; i < n; i += 2) cs.lineTo(e[0][i], e[0][i + 1]);
        for (int i = n - 2; i >= 0; i -= 2) cs.lineTo(e[1][i], e[1][i + 1]);
        cs.closePath();
    }

    /** A fixture or piece of equipment, with its tag/size label under it. */
    private static void sym(PDDocument doc, PDPageContentStream cs, Marks.Stamp s, int num) throws Exception {
        image(doc, cs, Mep.symbolBitmap(s), s.rect.centerX(), s.rect.centerY(), 0,
                s.rect.width(), s.rect.height());
        String lab = Mep.symLabel(s);
        if (lab.isEmpty() && num == 0) return;
        float[] sz = Mep.labelSize(lab, s.th, num);
        image(doc, cs, Mep.labelBitmap(lab, s.color, s.th, num, sz), s.rect.centerX(),
                s.rect.bottom + s.th * 0.15f + sz[1] / 2, 0, sz[0], sz[1]);
    }

    private static void applyMarks(Context ctx, PDDocument doc, Marks marks) throws Exception {
        {
            PDImageXObject sig = null;
            int num = 0; // note numbers run through the whole file, as on screen
            for (int index : marks.usedPages()) {
                if (index < 0 || index >= doc.getNumberOfPages()) continue;
                ArrayList<Marks.Item> items = marks.list(index);
                PDPage page = doc.getPage(index);
                PDRectangle box = page.getCropBox();
                int rot = ((page.getRotation() % 360) + 360) % 360;
                float w = box.getWidth(), h = box.getHeight();
                float llx = box.getLowerLeftX(), lly = box.getLowerLeftY();
                boolean side = rot == 90 || rot == 270;
                float shownW = side ? h : w, shownH = side ? w : h;
                float[] vs = marks.size(index);
                float kx = vs == null ? 1f : shownW / vs[0];
                float ky = vs == null ? 1f : shownH / vs[1];

                // From "page as shown, measured from its top-left" to the PDF's own coordinates.
                Matrix base;
                if (rot == 90) base = new Matrix(0, 1, 1, 0, llx, lly);
                else if (rot == 180) base = new Matrix(-1, 0, 0, 1, llx + w, lly);
                else if (rot == 270) base = new Matrix(0, -1, -1, 0, llx + w, lly + h);
                else base = new Matrix(1, 0, 0, -1, llx, lly + h);

                PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true);
                try {
                    cs.saveGraphicsState();
                    cs.transform(base);
                    cs.transform(Matrix.getScaleInstance(kx, ky));
                    cs.setLineCapStyle(1);
                    cs.setLineJoinStyle(1);
                    for (Marks.Item it : items) {
                        int n = Marks.hasNote(it) ? ++num : 0;
                        if (it instanceof Marks.Stroke) {
                            stroke(cs, (Marks.Stroke) it);
                        } else if (it instanceof Marks.Run) {
                            run(doc, cs, (Marks.Run) it, n);
                        } else if (((Marks.Stamp) it).kind == Marks.Stamp.SYM) {
                            sym(doc, cs, (Marks.Stamp) it, n);
                        } else if (((Marks.Stamp) it).kind == Marks.Stamp.RECT
                                || ((Marks.Stamp) it).kind == Marks.Stamp.OVAL) {
                            shape(cs, (Marks.Stamp) it);
                        } else {
                            Marks.Stamp st = (Marks.Stamp) it;
                            PDImageXObject img;
                            if (st.kind == Marks.Stamp.SIG) {
                                if (sig == null) {
                                    Bitmap b = Sig.get(ctx);
                                    if (b == null) continue;
                                    sig = LosslessFactory.createFromImage(doc, b);
                                }
                                img = sig;
                            } else {
                                img = LosslessFactory.createFromImage(doc, stampBitmap(st));
                            }
                            cs.saveGraphicsState();
                            // Pictures are stored bottom-up; flip them back the right way.
                            cs.transform(new Matrix(1, 0, 0, -1, st.rect.left, st.rect.bottom));
                            cs.drawImage(img, 0, 0, st.rect.width(), st.rect.height());
                            cs.restoreGraphicsState();
                        }
                    }
                    cs.restoreGraphicsState();
                } finally {
                    cs.close();
                }
            }
        }
    }

    private static void stroke(PDPageContentStream cs, Marks.Stroke s) throws Exception {
        if (s.n < 2) return;
        cs.saveGraphicsState();
        if (s.hi) {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setBlendMode(BlendMode.MULTIPLY);
            cs.setGraphicsStateParameters(gs);
        }
        cs.setStrokingColor(new PDColor(new float[]{
                ((s.color >> 16) & 0xff) / 255f, ((s.color >> 8) & 0xff) / 255f,
                (s.color & 0xff) / 255f}, PDDeviceRGB.INSTANCE));
        cs.setLineWidth(s.width);
        float[] p = s.pts;
        cs.moveTo(p[0], p[1]);
        if (s.n == 2) {
            cs.lineTo(p[0] + 0.01f, p[1] + 0.01f);
        } else {
            // Same smooth curve as on screen.
            float x0 = p[0], y0 = p[1];
            for (int i = 2; i < s.n; i += 2) {
                float cx = p[i - 2], cy = p[i - 1];
                float mx = (cx + p[i]) / 2f, my = (cy + p[i + 1]) / 2f;
                cs.curveTo(x0 + (cx - x0) * 2f / 3f, y0 + (cy - y0) * 2f / 3f,
                        mx + (cx - mx) * 2f / 3f, my + (cy - my) * 2f / 3f, mx, my);
                x0 = mx;
                y0 = my;
            }
            cs.lineTo(p[s.n - 2], p[s.n - 1]);
        }
        cs.stroke();
        cs.restoreGraphicsState();
    }

    /** Rectangles and ovals go into the PDF as real shapes, sharp at any zoom. */
    private static void shape(PDPageContentStream cs, Marks.Stamp s) throws Exception {
        float sw = s.shapeWidth();
        float in = sw / 2f;
        float l = s.rect.left + in, t = s.rect.top + in;
        float w = s.rect.width() - sw, h = s.rect.height() - sw;
        if (w <= 0 || h <= 0) return;
        cs.setStrokingColor(new PDColor(new float[]{
                ((s.color >> 16) & 0xff) / 255f, ((s.color >> 8) & 0xff) / 255f,
                (s.color & 0xff) / 255f}, PDDeviceRGB.INSTANCE));
        cs.setLineWidth(sw);
        if (s.kind == Marks.Stamp.RECT) {
            cs.addRect(l, t, w, h);
        } else {
            float cx = l + w / 2f, cy = t + h / 2f, rx = w / 2f, ry = h / 2f;
            final float k = 0.5522848f;
            cs.moveTo(cx + rx, cy);
            cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry);
            cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy);
            cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry);
            cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy);
            cs.closePath();
        }
        cs.stroke();
    }

    private static Bitmap stampBitmap(Marks.Stamp st) {
        float[] nat = Marks.StampArt.natural(st);
        float k = Math.min(4f, 1800f / Math.max(nat[0], nat[1]));
        int w = Math.max(1, (int) Math.ceil(nat[0] * k)), h = Math.max(1, (int) Math.ceil(nat[1] * k));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        Marks.StampArt.draw(c, st);
        return b;
    }

    /** Copies the finished file to where the user wants it. */
    static void write(Context ctx, File tmp, Uri dst) throws Exception {
        try (InputStream in = new FileInputStream(tmp);
             OutputStream out = ctx.getContentResolver().openOutputStream(dst, "wt")) {
            if (out == null) throw new java.io.IOException("cannot write");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }
}
