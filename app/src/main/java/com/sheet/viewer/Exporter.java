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
    static File build(Context ctx, Uri src, Marks marks) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());
        File tmp = new File(ctx.getCacheDir(), "export.pdf");
        try (InputStream in = ctx.getContentResolver().openInputStream(src);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            PDImageXObject sig = null;
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
                        if (it instanceof Marks.Stroke) {
                            stroke(cs, (Marks.Stroke) it);
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
            doc.save(tmp);
        }
        return tmp;
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

    private static Bitmap stampBitmap(Marks.Stamp st) {
        float[] nat = Marks.StampArt.natural(st.text, st.boxed);
        float k = Math.min(4f, 1800f / Math.max(nat[0], nat[1]));
        int w = Math.max(1, (int) Math.ceil(nat[0] * k)), h = Math.max(1, (int) Math.ceil(nat[1] * k));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        Marks.StampArt.draw(c, st.text, st.color, st.boxed);
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
