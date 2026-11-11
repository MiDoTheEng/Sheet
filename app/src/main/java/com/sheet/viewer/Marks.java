package com.sheet.viewer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Everything drawn on top of the PDF: pen strokes, signatures and stamps, per page.
 * All positions are in page points measured from the top-left corner of the page as shown.
 */
final class Marks {

    static abstract class Item {
    }

    static final class Stroke extends Item {
        int color;
        float width;
        boolean hi;
        float[] pts = new float[64];
        int n; // number of floats used in pts
        final RectF box = new RectF();
        private Path path;

        void add(float x, float y) {
            if (n + 2 > pts.length) {
                float[] bigger = new float[pts.length * 2];
                System.arraycopy(pts, 0, bigger, 0, n);
                pts = bigger;
            }
            pts[n++] = x;
            pts[n++] = y;
            if (n == 2) box.set(x, y, x, y);
            else box.union(x, y);
            path = null;
        }

        /** A smooth line through the points. */
        Path path() {
            if (path != null) return path;
            Path p = new Path();
            if (n >= 2) {
                p.moveTo(pts[0], pts[1]);
                if (n == 2) {
                    p.lineTo(pts[0] + 0.01f, pts[1] + 0.01f);
                } else {
                    for (int i = 2; i < n; i += 2) {
                        float mx = (pts[i - 2] + pts[i]) / 2f, my = (pts[i - 1] + pts[i + 1]) / 2f;
                        p.quadTo(pts[i - 2], pts[i - 1], mx, my);
                    }
                    p.lineTo(pts[n - 2], pts[n - 1]);
                }
            }
            path = p;
            return p;
        }

        boolean near(float x, float y, float r) {
            float pad = r + width / 2f;
            if (x < box.left - pad || x > box.right + pad || y < box.top - pad || y > box.bottom + pad) {
                return false;
            }
            if (n == 2) return Math.hypot(x - pts[0], y - pts[1]) <= pad;
            for (int i = 2; i < n; i += 2) {
                if (segDist(x, y, pts[i - 2], pts[i - 1], pts[i], pts[i + 1]) <= pad) return true;
            }
            return false;
        }

        private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
            float dx = bx - ax, dy = by - ay;
            float len2 = dx * dx + dy * dy;
            float t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
        }
    }

    static final class Stamp extends Item {
        static final int SIG = 0, TEXT = 1, TICK = 2;
        int kind;
        String text = "";
        int color = 0xFFE53935;
        boolean boxed = true;
        /** 0 sans, 1 serif, 2 typewriter, 3 handwriting. */
        int font;
        /** 0 none, 1 green check, 2 red check, 3 red cross. On TEXT it sits beside the text. */
        int tick;
        final RectF rect = new RectF();
    }

    /** Draws a text or tick stamp in its own units; the caller scales it to the stamp's box. */
    static final class StampArt {
        private static final float TS = 100f, LH = 118f, BW = 8f, TICKW = 104f, GAP = 26f;
        static final int GREEN = 0xFF2E9E3F, RED = 0xFFE53935;
        private static final Typeface[] FACES = {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
                Typeface.create(Typeface.SERIF, Typeface.BOLD),
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Typeface.create("cursive", Typeface.NORMAL)};
        private static final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private static final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        static {
            text.setTextSize(TS);
            text.setTextAlign(Paint.Align.CENTER);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        private StampArt() {}

        private static float pad(Stamp s) {
            return s.boxed ? 36f : 8f;
        }

        static Typeface face(int font) {
            return FACES[Math.max(0, Math.min(FACES.length - 1, font))];
        }

        /** Natural width and height of a stamp. */
        static float[] natural(Stamp s) {
            if (s.kind == Stamp.TICK) return new float[]{120f, 120f};
            text.setTypeface(face(s.font));
            String[] lines = s.text.split("\n", -1);
            float tw = 40f;
            for (String l : lines) tw = Math.max(tw, text.measureText(l));
            float pad = pad(s);
            float w = tw + 2 * pad + (s.boxed ? 20f : 0f) + (s.tick != 0 ? GAP + TICKW : 0f);
            float h = 2 * pad + (lines.length - 1) * LH + TS;
            if (s.tick != 0) h = Math.max(h, 2 * pad + TICKW);
            return new float[]{w, h};
        }

        static void draw(Canvas c, Stamp s) {
            if (s.kind == Stamp.TICK) {
                tick(c, s.tick, 10f, 10f, 100f);
                return;
            }
            float[] nat = natural(s); // also sets the typeface
            float pad = pad(s);
            String[] lines = s.text.split("\n", -1);
            float tickPart = s.tick != 0 ? GAP + TICKW : 0f;
            float textH = (lines.length - 1) * LH + TS;
            float top = (nat[1] - textH) / 2f;
            text.setColor(s.color);
            for (int i = 0; i < lines.length; i++) {
                c.drawText(lines[i], (nat[0] - tickPart) / 2f, top + TS * 0.84f + i * LH, text);
            }
            if (s.tick != 0) {
                tick(c, s.tick, nat[0] - pad - TICKW, (nat[1] - TICKW) / 2f, TICKW);
            }
            if (s.boxed) {
                line.setColor(s.color);
                line.setStrokeWidth(BW);
                c.drawRoundRect(BW / 2f, BW / 2f, nat[0] - BW / 2f, nat[1] - BW / 2f, 20f, 20f, line);
            }
        }

        private static void tick(Canvas c, int code, float x, float y, float size) {
            line.setColor(code == 1 ? GREEN : RED);
            line.setStrokeWidth(size * 0.15f);
            Path p = new Path();
            if (code == 3) {
                p.moveTo(x + size * 0.16f, y + size * 0.16f);
                p.lineTo(x + size * 0.84f, y + size * 0.84f);
                p.moveTo(x + size * 0.84f, y + size * 0.16f);
                p.lineTo(x + size * 0.16f, y + size * 0.84f);
            } else {
                p.moveTo(x + size * 0.10f, y + size * 0.56f);
                p.lineTo(x + size * 0.38f, y + size * 0.84f);
                p.lineTo(x + size * 0.90f, y + size * 0.18f);
            }
            c.drawPath(p, line);
        }
    }

    private static final class Op {
        final int page;
        final Item item;
        final boolean add;

        Op(int page, Item item, boolean add) {
            this.page = page;
            this.item = item;
            this.add = add;
        }
    }

    private final SparseArray<ArrayList<Item>> pages = new SparseArray<>();
    /** Size of each visited page as shown on screen, in points. */
    private final SparseArray<float[]> sizes = new SparseArray<>();
    private final ArrayList<Op> undo = new ArrayList<>();
    private final ArrayList<Op> redo = new ArrayList<>();

    ArrayList<Item> list(int page) {
        ArrayList<Item> l = pages.get(page);
        if (l == null) {
            l = new ArrayList<>();
            pages.put(page, l);
        }
        return l;
    }

    void setSize(int page, float w, float h) {
        sizes.put(page, new float[]{w, h});
    }

    float[] size(int page) {
        return sizes.get(page);
    }

    void add(int page, Item it) {
        list(page).add(it);
        undo.add(new Op(page, it, true));
        redo.clear();
    }

    void remove(int page, Item it) {
        if (list(page).remove(it)) {
            undo.add(new Op(page, it, false));
            redo.clear();
        }
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Returns the page that changed, or -1. */
    int undo() {
        if (undo.isEmpty()) return -1;
        Op o = undo.remove(undo.size() - 1);
        if (o.add) list(o.page).remove(o.item);
        else list(o.page).add(o.item);
        redo.add(o);
        return o.page;
    }

    int redo() {
        if (redo.isEmpty()) return -1;
        Op o = redo.remove(redo.size() - 1);
        if (o.add) list(o.page).add(o.item);
        else list(o.page).remove(o.item);
        undo.add(o);
        return o.page;
    }

    boolean isEmpty() {
        for (int i = 0; i < pages.size(); i++) if (!pages.valueAt(i).isEmpty()) return false;
        return true;
    }

    void clear() {
        pages.clear();
        undo.clear();
        redo.clear();
    }

    /** Page numbers that have something on them. */
    ArrayList<Integer> usedPages() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            if (!pages.valueAt(i).isEmpty()) out.add(pages.keyAt(i));
        }
        return out;
    }

    // ---- keeping unsaved marks between sessions ----

    String toJson() throws Exception {
        JSONArray all = new JSONArray();
        for (int i = 0; i < pages.size(); i++) {
            ArrayList<Item> l = pages.valueAt(i);
            if (l.isEmpty()) continue;
            int page = pages.keyAt(i);
            JSONObject po = new JSONObject();
            po.put("page", page);
            float[] sz = sizes.get(page);
            if (sz != null) {
                po.put("w", sz[0]);
                po.put("h", sz[1]);
            }
            JSONArray items = new JSONArray();
            for (Item it : l) {
                JSONObject o = new JSONObject();
                if (it instanceof Stroke) {
                    Stroke s = (Stroke) it;
                    o.put("k", "stroke");
                    o.put("c", s.color);
                    o.put("w", s.width);
                    o.put("hi", s.hi);
                    JSONArray p = new JSONArray();
                    for (int j = 0; j < s.n; j++) p.put(Math.round(s.pts[j] * 100f) / 100.0);
                    o.put("p", p);
                } else {
                    Stamp s = (Stamp) it;
                    o.put("k", s.kind == Stamp.SIG ? "sig" : s.kind == Stamp.TICK ? "tick" : "text");
                    o.put("t", s.text);
                    o.put("c", s.color);
                    o.put("b", s.boxed);
                    o.put("f", s.font);
                    o.put("tk", s.tick);
                    o.put("l", s.rect.left);
                    o.put("tp", s.rect.top);
                    o.put("r", s.rect.right);
                    o.put("bt", s.rect.bottom);
                }
                items.put(o);
            }
            po.put("items", items);
            all.put(po);
        }
        return all.toString();
    }

    void fromJson(String json) throws Exception {
        clear();
        JSONArray all = new JSONArray(json);
        for (int i = 0; i < all.length(); i++) {
            JSONObject po = all.getJSONObject(i);
            int page = po.getInt("page");
            if (po.has("w")) setSize(page, (float) po.getDouble("w"), (float) po.getDouble("h"));
            JSONArray items = po.getJSONArray("items");
            ArrayList<Item> l = list(page);
            for (int j = 0; j < items.length(); j++) {
                JSONObject o = items.getJSONObject(j);
                String k = o.getString("k");
                if (k.equals("stroke")) {
                    Stroke s = new Stroke();
                    s.color = o.getInt("c");
                    s.width = (float) o.getDouble("w");
                    s.hi = o.optBoolean("hi", false);
                    JSONArray p = o.getJSONArray("p");
                    for (int q = 0; q + 1 < p.length(); q += 2) {
                        s.add((float) p.getDouble(q), (float) p.getDouble(q + 1));
                    }
                    if (s.n >= 2) l.add(s);
                } else {
                    Stamp s = new Stamp();
                    s.kind = k.equals("sig") ? Stamp.SIG : k.equals("tick") ? Stamp.TICK : Stamp.TEXT;
                    s.text = o.optString("t", "");
                    s.color = o.optInt("c", 0xFFE53935);
                    s.boxed = o.optBoolean("b", true);
                    s.font = o.optInt("f", 0);
                    s.tick = o.optInt("tk", 0);
                    s.rect.set((float) o.getDouble("l"), (float) o.getDouble("tp"),
                            (float) o.getDouble("r"), (float) o.getDouble("bt"));
                    l.add(s);
                }
            }
        }
    }
}
