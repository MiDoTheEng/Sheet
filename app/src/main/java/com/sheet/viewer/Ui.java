package com.sheet.viewer;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;

/** Small shared helpers: colours, sizes, simple views. */
final class Ui {
    static final int BG = 0xFF15161A;
    static final int BAR = 0xFF1E2026;
    static final int CARD = 0xFF272A31;
    static final int TEXT = 0xFFE8EAED;
    static final int DIM = 0xFF9AA0A6;
    static final int ACCENT = 0xFFFFB020;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView label(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** A flat text button for the top bar. */
    static TextView barButton(Context c, String s) {
        TextView t = label(c, s, 15, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 12);
        t.setPadding(p, 0, p, 0);
        t.setMinWidth(dp(c, 44));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable dot(int color, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    static String displayName(Context c, Uri u) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(u,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null) name = "Drawing.pdf";
        return name;
    }

    /** A short stable key for a file, used to name what the app keeps about it. */
    static String key(Uri u) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(u.toString().getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(u.toString().hashCode());
        }
    }
}
