package com.sheet.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** The user's signature image, kept inside the app so it can be placed with one tap. */
final class Sig {
    private static final int MAX = 1400;
    private static Bitmap bmp;
    private static boolean loaded;

    private Sig() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "signature.png");
    }

    static synchronized Bitmap get(Context c) {
        if (!loaded) {
            loaded = true;
            File f = file(c);
            if (f.exists()) bmp = BitmapFactory.decodeFile(f.getPath());
        }
        return bmp;
    }

    static synchronized void remove(Context c) {
        file(c).delete();
        bmp = null;
        loaded = true;
    }

    /**
     * Takes a picture of a signature, makes the paper around it see-through, trims the empty
     * edges and keeps it. Returns false if the picture could not be read.
     */
    static synchronized boolean importFrom(Context c, Uri uri) {
        try {
            Bitmap src = decode(c, uri);
            if (src == null) return false;
            Bitmap out = clean(src);
            try (FileOutputStream fo = new FileOutputStream(file(c))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            }
            bmp = out;
            loaded = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bitmap decode(Context c, Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source s = ImageDecoder.createSource(c.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(s, (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
                int big = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (big / sample > MAX) sample *= 2;
                decoder.setTargetSampleSize(sample);
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int big = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (big / sample > MAX) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, o2);
        }
    }

    private static Bitmap clean(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // Does the picture already have a see-through background?
        int clear = 0;
        for (int p : px) if ((p >>> 24) < 250) clear++;
        boolean hasAlpha = clear > px.length / 50;

        if (!hasAlpha) {
            // Paper brightness, judged from the border of the picture.
            long sum = 0;
            int cnt = 0;
            for (int x = 0; x < w; x++) {
                sum += lum(px[x]) + lum(px[(h - 1) * w + x]);
                cnt += 2;
            }
            for (int y = 0; y < h; y++) {
                sum += lum(px[y * w]) + lum(px[y * w + w - 1]);
                cnt += 2;
            }
            int paper = (int) (sum / Math.max(1, cnt));
            if (paper > 140) {
                // Light paper: the darker a pixel is than the paper, the more solid it stays.
                float top = paper - 22f;
                for (int i = 0; i < px.length; i++) {
                    int p = px[i];
                    float a = (top - lum(p)) * 255f / 80f;
                    int ai = a <= 0 ? 0 : a >= 255 ? 255 : (int) a;
                    px[i] = (ai << 24) | (p & 0x00FFFFFF);
                }
            }
        }

        // Trim empty edges.
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[row + x] >>> 24) > 40) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        if (r < l || b < t) {
            l = 0;
            t = 0;
            r = w - 1;
            b = h - 1;
        }
        int pad = 6;
        l = Math.max(0, l - pad);
        t = Math.max(0, t - pad);
        r = Math.min(w - 1, r + pad);
        b = Math.min(h - 1, b + pad);
        int cw = r - l + 1, ch = b - t + 1;
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        out.setPixels(px, t * w + l, w, 0, 0, cw, ch);
        return out;
    }

    private static int lum(int p) {
        return (((p >> 16) & 0xff) * 3 + ((p >> 8) & 0xff) * 6 + (p & 0xff)) / 10;
    }
}
