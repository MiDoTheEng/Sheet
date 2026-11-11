package com.sheet.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** The viewer: one page at a time, pan/zoom, doodle, signature and stamps, then save. */
public final class ViewerActivity extends Activity implements Renderer.Host, PageView.Host {
    private static final int REQ_SAVE_COPY = 2, REQ_SIG = 3;
    private static final int[] COLORS = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF000000,
            0xFFFB8C00, 0xFF8E24AA};

    private Uri uri;
    private String name, fileKey;
    private final Marks marks = new Marks();
    private PageView page;
    private Renderer renderer;
    private int pageCount, pageIndex;
    private boolean pendingSig; // place the signature right after it is imported

    private TextView pageLabel, saveBtn;
    private ToolButton bHand, bPen, bHi, bEraser, bSign, bDate, bStamp, bUndo, bRedo;
    private FrameLayout busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        name = Ui.displayName(this, uri);
        fileKey = Ui.key(uri);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // --- top bar ---
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.BAR);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        TextView back = Ui.barButton(this, "‹");
        back.setTextSize(26);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = Ui.label(this, name, 15, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView prev = Ui.barButton(this, "‹");
        prev.setOnClickListener(v -> go(pageIndex - 1));
        top.addView(prev, barLp());
        pageLabel = Ui.barButton(this, "–");
        pageLabel.setTextColor(Ui.DIM);
        pageLabel.setOnClickListener(v -> askPage());
        top.addView(pageLabel, barLp());
        TextView next = Ui.barButton(this, "›");
        next.setOnClickListener(v -> go(pageIndex + 1));
        top.addView(next, barLp());

        saveBtn = Ui.barButton(this, "Save");
        saveBtn.setTextColor(Ui.ACCENT);
        saveBtn.setOnClickListener(v -> askSave());
        top.addView(saveBtn, barLp());

        // --- the page ---
        FrameLayout mid = new FrameLayout(this);
        page = new PageView(this);
        page.setMarks(marks);
        page.setHost(this);
        mid.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        busy = new FrameLayout(this);
        busy.setBackgroundColor(0x99000000);
        busy.setClickable(true);
        ProgressBar pb = new ProgressBar(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER;
        busy.addView(pb, plp);
        busy.setVisibility(View.GONE);
        mid.addView(busy, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(mid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- toolbar ---
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(Ui.BAR);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setMinimumWidth(getResources().getDisplayMetrics().widthPixels);
        hs.addView(bar);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));

        bHand = tool(bar, ToolButton.PAN, "Move", v -> setTool(PageView.HAND));
        bPen = tool(bar, ToolButton.PEN, "Pen", v -> {
            if (page.tool == PageView.PEN) penDialog();
            else setTool(PageView.PEN);
        });
        bHi = tool(bar, ToolButton.MARKER, "Marker", v -> {
            if (page.tool == PageView.HI) penDialog();
            else setTool(PageView.HI);
        });
        bEraser = tool(bar, ToolButton.ERASER, "Eraser", v -> setTool(PageView.ERASER));
        bSign = tool(bar, ToolButton.SIGN, "Sign", v -> sign());
        bSign.setOnLongClickListener(v -> {
            sigMenu();
            return true;
        });
        bDate = tool(bar, ToolButton.DATE, "Date", v -> placeDate());
        bStamp = tool(bar, ToolButton.STAMP, "Stamp", v -> stampMenu());
        bUndo = tool(bar, ToolButton.UNDO, "Undo", v -> {
            int p = marks.undo();
            if (p == pageIndex) page.refresh();
            else if (p >= 0) go(p);
            marksChangedUi();
        });
        bRedo = tool(bar, ToolButton.REDO, "Redo", v -> {
            int p = marks.redo();
            if (p == pageIndex) page.refresh();
            else if (p >= 0) go(p);
            marksChangedUi();
        });

        setContentView(root);
        setTool(PageView.HAND);
        marksChangedUi();

        page.penColor = Store.stampColor(this);
        loadMarks();
        renderer = new Renderer(this, this, page);
        renderer.open(uri);
    }

    private LinearLayout.LayoutParams barLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private ToolButton tool(LinearLayout bar, int kind, String label, View.OnClickListener l) {
        ToolButton t = new ToolButton(this, kind, label);
        t.setOnClickListener(l);
        bar.addView(t, new LinearLayout.LayoutParams(Ui.dp(this, 62),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    private void setTool(int t) {
        page.tool = t;
        page.deselect();
        bHand.setOn(t == PageView.HAND);
        bPen.setOn(t == PageView.PEN);
        bHi.setOn(t == PageView.HI);
        bEraser.setOn(t == PageView.ERASER);
    }

    private void marksChangedUi() {
        bUndo.setDim(!marks.canUndo());
        bRedo.setDim(!marks.canRedo());
    }

    // ---- renderer callbacks ----

    @Override
    public void onDocOpened(int pages) {
        pageCount = pages;
        int start = Math.min(Store.lastPage(this, fileKey), pages - 1);
        go(Math.max(0, start));
    }

    @Override
    public void onDocFailed(String why) {
        Ui.toast(this, why);
        finish();
    }

    @Override
    public void onPage(int gen, int index, float w, float h, Bitmap base, float baseScale) {
        if (gen != renderer.gen) {
            base.recycle();
            return;
        }
        marks.setSize(index, w, h);
        for (Bitmap old : page.setPage(index, w, h, base, baseScale)) {
            if (old != null) old.recycle();
        }
    }

    @Override
    public void onBase(int gen, Bitmap base, float baseScale) {
        if (gen != renderer.gen) {
            base.recycle();
            return;
        }
        Bitmap old = page.setBase(base, baseScale);
        if (old != null) old.recycle();
    }

    @Override
    public void onPatch(int gen, Bitmap bmp, RectF rect, float scale) {
        if (gen != renderer.gen) {
            if (bmp != null) bmp.recycle();
            return;
        }
        Bitmap old = page.setPatch(bmp, rect, scale);
        if (old != null) old.recycle();
    }

    // ---- page view callbacks ----

    @Override
    public void onViewChanged() {
        renderer.requestPatch();
    }

    @Override
    public void onPageFling(int dir) {
        go(pageIndex + dir);
    }

    @Override
    public void onMarksChanged() {
        marksChangedUi();
    }

    private void go(int index) {
        if (pageCount <= 0) return;
        index = Math.max(0, Math.min(pageCount - 1, index));
        pageIndex = index;
        pageLabel.setText((index + 1) + " / " + pageCount);
        Store.setLastPage(this, fileKey, index);
        renderer.loadPage(index);
    }

    private void askPage() {
        if (pageCount <= 1) return;
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 – " + pageCount);
        new AlertDialog.Builder(this).setTitle("Go to page").setView(e)
                .setPositiveButton("Go", (d, w) -> {
                    try {
                        go(Integer.parseInt(e.getText().toString().trim()) - 1);
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- pen settings ----

    private void penDialog() {
        boolean hi = page.tool == PageView.HI;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, 0);
        box.addView(colorRow(hi ? page.hiColor : page.penColor, c -> {
            if (page.tool == PageView.HI) page.hiColor = c;
            else {
                page.penColor = c;
                Store.setStampColor(this, c);
            }
        }, hi));

        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        sizes.setGravity(Gravity.CENTER);
        sizes.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        float[] widths = hi ? new float[]{10, 16, 26} : new float[]{2, 3.2f, 5.5f};
        String[] labels = {"Thin", "Medium", "Thick"};
        for (int i = 0; i < 3; i++) {
            final float wdp = widths[i];
            TextView t = Ui.label(this, labels[i], 14, Ui.TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            float cur = hi ? page.hiWidthPx : page.penWidthPx;
            if (Math.abs(cur - Ui.dp(this, wdp)) < Ui.dp(this, 1)) {
                t.setBackground(Ui.round(0x33FFB020, Ui.dp(this, 10)));
                t.setTextColor(Ui.ACCENT);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            sizes.addView(t, lp);
            t.setOnClickListener(v -> {
                if (page.tool == PageView.HI) page.hiWidthPx = Ui.dp(this, wdp);
                else page.penWidthPx = Ui.dp(this, wdp);
            });
        }
        box.addView(sizes);
        new AlertDialog.Builder(this).setTitle(hi ? "Marker" : "Pen").setView(box)
                .setPositiveButton("Done", null).show();
    }

    private interface ColorPick {
        void pick(int c);
    }

    private LinearLayout colorRow(int current, final ColorPick cb, boolean hi) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int[] colors = hi ? new int[]{0xFFFFEB3B, 0xFF69F0AE, 0xFF40C4FF, 0xFFFF8A80}
                : COLORS;
        int size = Ui.dp(this, 36), m = Ui.dp(this, 6);
        for (final int c : colors) {
            View v = new View(this);
            v.setBackground(Ui.dot(c, c == current ? Ui.dp(this, 3) : Ui.dp(this, 1),
                    c == current ? Ui.ACCENT : 0x33000000));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(m, 0, m, 0);
            row.addView(v, lp);
            final LinearLayout frow = row;
            v.setOnClickListener(x -> {
                cb.pick(c);
                for (int i = 0; i < frow.getChildCount(); i++) {
                    View ch = frow.getChildAt(i);
                    boolean sel = ch == x;
                    int cc = hi ? colors[i] : COLORS[i];
                    ch.setBackground(Ui.dot(cc, sel ? Ui.dp(this, 3) : Ui.dp(this, 1),
                            sel ? Ui.ACCENT : 0x33000000));
                }
            });
        }
        return row;
    }

    // ---- signature ----

    private void sign() {
        if (Sig.get(this) == null) {
            pendingSig = true;
            pickSig();
            return;
        }
        placeSig();
    }

    private void placeSig() {
        Bitmap b = Sig.get(this);
        if (b == null) return;
        Marks.Stamp s = new Marks.Stamp();
        s.kind = Marks.Stamp.SIG;
        page.place(s, (float) b.getWidth() / b.getHeight(), 0.3f, 0.25f);
        setTool(PageView.HAND);
        bHand.setOn(true);
    }

    private void pickSig() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Pick your signature image"), REQ_SIG);
        } catch (Exception e) {
            Ui.toast(this, "No image picker found");
        }
    }

    private void sigMenu() {
        if (Sig.get(this) == null) {
            pickSig();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Signature")
                .setItems(new String[]{"Replace the image", "Remove the saved signature"},
                        (d, w) -> {
                            if (w == 0) pickSig();
                            else {
                                Sig.remove(this);
                                Ui.toast(this, "Signature removed");
                            }
                        })
                .show();
    }

    // ---- date and text stamps ----

    private static String today() {
        return new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(new Date());
    }

    private void placeDate() {
        Marks.Stamp s = new Marks.Stamp();
        s.kind = Marks.Stamp.TEXT;
        s.text = today();
        s.color = Store.stampColor(this);
        s.boxed = true;
        placeText(s);
    }

    private void placeText(Marks.Stamp s) {
        float[] nat = Marks.StampArt.natural(s.text, s.boxed);
        page.place(s, nat[0] / nat[1], 0.42f, 0.3f);
        setTool(PageView.HAND);
        bHand.setOn(true);
    }

    private void stampMenu() {
        final ArrayList<Store.SavedStamp> saved = Store.stamps(this);
        String[] names = new String[saved.size() + 1];
        names[0] = "+  New stamp…";
        for (int i = 0; i < saved.size(); i++) {
            Store.SavedStamp st = saved.get(i);
            names[i + 1] = st.text.replace('\n', ' ') + (st.date ? "  (+ date)" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Stamps")
                .setItems(names, (d, w) -> {
                    if (w == 0) {
                        newStampDialog();
                        return;
                    }
                    Store.SavedStamp st = saved.get(w - 1);
                    Marks.Stamp s = new Marks.Stamp();
                    s.kind = Marks.Stamp.TEXT;
                    s.text = st.date ? st.text + "\n" + today() : st.text;
                    s.color = st.color;
                    s.boxed = st.boxed;
                    placeText(s);
                });
        if (!saved.isEmpty()) {
            b.setNeutralButton("Edit list", (d, w) -> editStamps(saved));
        }
        b.show();
    }

    private void editStamps(final ArrayList<Store.SavedStamp> saved) {
        String[] names = new String[saved.size()];
        for (int i = 0; i < saved.size(); i++) names[i] = saved.get(i).text.replace('\n', ' ');
        new AlertDialog.Builder(this).setTitle("Tap a stamp to delete it")
                .setItems(names, (d, w) -> {
                    saved.remove(w);
                    Store.saveStamps(this, saved);
                    Ui.toast(this, "Stamp deleted");
                })
                .setNegativeButton("Close", null).show();
    }

    private void newStampDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 10), p, 0);

        final EditText text = new EditText(this);
        text.setHint("Stamp text (e.g. APPROVED, REV 2, FOR REVIEW)");
        text.setMinLines(1);
        text.setMaxLines(4);
        box.addView(text);

        final int[] color = {Store.stampColor(this)};
        LinearLayout colors = colorRow(color[0], c -> color[0] = c, false);
        colors.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(colors);

        final CheckBox boxed = new CheckBox(this);
        boxed.setText("Draw a frame around it");
        boxed.setChecked(true);
        box.addView(boxed);
        final CheckBox withDate = new CheckBox(this);
        withDate.setText("Add today's date under the text");
        box.addView(withDate);
        final CheckBox keep = new CheckBox(this);
        keep.setText("Save this stamp for reuse");
        keep.setChecked(true);
        box.addView(keep);

        new AlertDialog.Builder(this).setTitle("New stamp").setView(box)
                .setPositiveButton("Place", (d, w) -> {
                    String t = text.getText().toString().trim();
                    if (t.isEmpty()) return;
                    Store.setStampColor(this, color[0]);
                    if (keep.isChecked()) {
                        ArrayList<Store.SavedStamp> saved = Store.stamps(this);
                        Store.SavedStamp st = new Store.SavedStamp();
                        st.text = t;
                        st.color = color[0];
                        st.boxed = boxed.isChecked();
                        st.date = withDate.isChecked();
                        saved.add(0, st);
                        Store.saveStamps(this, saved);
                    }
                    Marks.Stamp s = new Marks.Stamp();
                    s.kind = Marks.Stamp.TEXT;
                    s.text = withDate.isChecked() ? t + "\n" + today() : t;
                    s.color = color[0];
                    s.boxed = boxed.isChecked();
                    placeText(s);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- saving the PDF ----

    private void askSave() {
        if (marks.isEmpty()) {
            Ui.toast(this, "Nothing has been drawn yet");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Save")
                .setItems(new String[]{"Save as a new copy", "Overwrite the original file"},
                        (d, w) -> {
                            if (w == 0) saveCopy();
                            else confirmOverwrite();
                        })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveCopy() {
        String base = name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? name.substring(0, name.length() - 4) : name;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, base + " - marked.pdf");
        try {
            startActivityForResult(i, REQ_SAVE_COPY);
        } catch (Exception e) {
            Ui.toast(this, "No file picker found on this device");
        }
    }

    private void confirmOverwrite() {
        new AlertDialog.Builder(this).setTitle("Overwrite the original?")
                .setMessage("The marks will be written into\n" + name
                        + "\nand this cannot be undone afterwards.")
                .setPositiveButton("Overwrite", (d, w) -> doSave(null))
                .setNegativeButton("Cancel", null).show();
    }

    /** dst == null means overwrite the original file. */
    private void doSave(final Uri dst) {
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String err = null;
            boolean overwrote = false;
            try {
                File tmp = Exporter.build(this, uri, marks);
                if (dst != null) {
                    Exporter.write(this, tmp, dst);
                } else {
                    renderer.closeDocAndWait();
                    try {
                        Exporter.write(this, tmp, uri);
                        overwrote = true;
                    } catch (Exception e) {
                        err = "This file does not allow overwriting here — use “Save as a new copy” instead.";
                    }
                }
            } catch (Throwable t) {
                err = "Saving failed: " + (t.getMessage() == null ? t.getClass().getSimpleName()
                        : t.getMessage());
            }
            final String ferr = err;
            final boolean reopened = dst == null;
            final boolean fOverwrote = overwrote;
            runOnUiThread(() -> {
                busy.setVisibility(View.GONE);
                if (fOverwrote) {
                    // The marks are now part of the file itself.
                    marks.clear();
                    page.refresh();
                    marksChangedUi();
                    saveMarks();
                }
                if (reopened) {
                    renderer.open(uri);
                    renderer.loadPage(pageIndex);
                }
                Ui.toast(this, ferr != null ? ferr
                        : (fOverwrote ? "Saved into the original file" : "Copy saved"));
            });
        }, "sheet-save").start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_SAVE_COPY) {
            doSave(data.getData());
        } else if (req == REQ_SIG) {
            final Uri img = data.getData();
            busy.setVisibility(View.VISIBLE);
            new Thread(() -> {
                final boolean ok = Sig.importFrom(this, img);
                runOnUiThread(() -> {
                    busy.setVisibility(View.GONE);
                    if (!ok) {
                        Ui.toast(this, "That image could not be read");
                    } else if (pendingSig) {
                        pendingSig = false;
                        placeSig();
                    } else {
                        Ui.toast(this, "Signature saved");
                    }
                });
            }, "sheet-sig").start();
        }
    }

    // ---- keeping unsaved marks ----

    private File marksFile() {
        return new File(getFilesDir(), "marks_" + fileKey + ".json");
    }

    private void loadMarks() {
        File f = marksFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int off = 0, n;
            while (off < d.length && (n = in.read(d, off, d.length - off)) > 0) off += n;
            marks.fromJson(new String(d, "UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private void saveMarks() {
        try {
            if (marks.isEmpty()) {
                marksFile().delete();
                return;
            }
            byte[] d = marks.toJson().getBytes("UTF-8");
            try (FileOutputStream out = new FileOutputStream(marksFile())) {
                out.write(d);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveMarks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (renderer != null) renderer.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (page.hasSelection()) {
            page.deselect();
            return;
        }
        if (page.tool != PageView.HAND) {
            setTool(PageView.HAND);
            return;
        }
        super.onBackPressed();
    }
}
