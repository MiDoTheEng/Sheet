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
    private static final int REQ_SAVE_COPY = 2, REQ_SIG = 3, REQ_CROP = 4;
    private static final int[] COLORS = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF000000,
            0xFFFB8C00, 0xFF8E24AA};

    private Uri uri;
    private String name, fileKey;
    private final Marks marks = new Marks();
    private PageView page;
    private Renderer renderer;
    private int pageCount, pageIndex;
    private boolean pendingSig; // place the signature right after it is imported

    private TextView pageLabel, saveBtn, layersBtn, prevBtn, nextBtn;
    private DxfDoc cad; // set when the file is a CAD drawing
    private LinearLayout runBar;
    private TextView runTitle, runInfo, runDone;
    private ToolButton bHand, bPen, bHi, bEraser, bSign, bDate, bStamp, bUndo, bRedo;
    private FrameLayout busy;
    private int barPos;
    private boolean barVertical;

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

        prevBtn = Ui.barButton(this, "‹");
        prevBtn.setOnClickListener(v -> go(pageIndex - 1));
        top.addView(prevBtn, barLp());
        pageLabel = Ui.barButton(this, "–");
        pageLabel.setTextColor(Ui.DIM);
        pageLabel.setOnClickListener(v -> askPage());
        top.addView(pageLabel, barLp());
        nextBtn = Ui.barButton(this, "›");
        nextBtn.setOnClickListener(v -> go(pageIndex + 1));
        top.addView(nextBtn, barLp());

        layersBtn = Ui.barButton(this, "Layers");
        layersBtn.setOnClickListener(v -> layersDialog());
        layersBtn.setVisibility(View.GONE);
        top.addView(layersBtn, barLp());

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
        buildRunBar(mid);
        mid.addView(busy, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- toolbar; its place (bottom / top / left / right) is the user's choice ---
        barPos = Store.barPos(this);
        barVertical = barPos >= 2;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(barVertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        View barWrap;
        if (barVertical) {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.setBackgroundColor(Ui.BAR);
            sv.setVerticalScrollBarEnabled(false);
            sv.setFillViewport(true);
            sv.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            barWrap = sv;
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setBackgroundColor(Ui.BAR);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setFillViewport(true);
            hs.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            barWrap = hs;
        }

        LinearLayout midRow = new LinearLayout(this);
        midRow.setOrientation(LinearLayout.HORIZONTAL);
        if (barPos == 2) {
            midRow.addView(barWrap, new LinearLayout.LayoutParams(Ui.dp(this, 68),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        midRow.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        if (barPos == 3) {
            midRow.addView(barWrap, new LinearLayout.LayoutParams(Ui.dp(this, 68),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (barPos == 1) {
            root.addView(barWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));
        }
        root.addView(midRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (barPos == 0) {
            root.addView(barWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));
        }

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
        tool(bar, ToolButton.TEXT, "Text", v -> textDialog());
        bSign = tool(bar, ToolButton.SIGN, "Sign", v -> sign());
        bSign.setOnLongClickListener(v -> {
            sigMenu();
            return true;
        });
        bDate = tool(bar, ToolButton.DATE, "Date", v -> placeDate());
        bStamp = tool(bar, ToolButton.STAMP, "Stamp", v -> stampMenu());
        tool(bar, ToolButton.TICK, "Tick", v -> tickMenu());
        tool(bar, ToolButton.SHAPES, "Shape", v -> shapeMenu());
        tool(bar, ToolButton.MEP, "MEP", v -> mepMenu());
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
        tool(bar, ToolButton.BARPOS, "Bar", v -> {
            Store.setBarPos(this, (barPos + 1) % 4);
            recreate();
        });

        setContentView(root);
        setTool(PageView.HAND);
        marksChangedUi();

        page.penColor = Store.stampColor(this);
        loadMarks();
        busy.setVisibility(View.VISIBLE); // big drawings take a moment to read
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
        bar.addView(t, barVertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60))
                : new LinearLayout.LayoutParams(Ui.dp(this, 62), ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    private void setTool(int t) {
        if (page.drawingRun()) cancelRunMode();
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
    public void onDocOpened(int pages, DxfDoc cadDoc) {
        busy.setVisibility(View.GONE);
        pageCount = pages;
        cad = cadDoc;
        if (cad != null) {
            // Restore the layers and print look chosen last time for this drawing.
            ArrayList<String> off = Store.cadHiddenLayers(this, fileKey);
            if (off != null) {
                boolean[] h = new boolean[cad.layerNames.length];
                for (int i = 0; i < h.length; i++) h[i] = off.contains(cad.layerNames[i]);
                cad.hidden = h;
            }
            cad.mono = Store.cadMono(this);
            page.maxZoom = 400f;
            layersBtn.setVisibility(View.VISIBLE);
            prevBtn.setVisibility(View.GONE);
            nextBtn.setVisibility(View.GONE);
            pageLabel.setVisibility(View.GONE);
            if (cad.truncated) {
                Ui.toast(this, "This drawing is very large: only part of it could be loaded.");
            } else if (cad.paperOnly) {
                Ui.toast(this, "Showing the paper layout (model space is empty).");
            }
        }
        int start = Math.min(Store.lastPage(this, fileKey), pages - 1);
        go(Math.max(0, start));
    }

    @Override
    public void onDocFailed(String why) {
        busy.setVisibility(View.GONE);
        new AlertDialog.Builder(this).setMessage(why)
                .setPositiveButton("OK", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // ---- CAD layers ----

    private void layersDialog() {
        final DxfDoc d = cad;
        if (d == null) return;
        final int[] used = d.usedLayers;
        String[] names = new String[used.length + 1];
        final boolean[] checked = new boolean[used.length + 1];
        names[0] = "◐  Black & white (print look)";
        checked[0] = d.mono;
        boolean[] hid = d.hidden;
        for (int i = 0; i < used.length; i++) {
            names[i + 1] = d.layerNames[used[i]];
            checked[i + 1] = !hid[used[i]];
        }
        new AlertDialog.Builder(this).setTitle("Layers")
                .setMultiChoiceItems(names, checked, (dlg, which, on) -> checked[which] = on)
                .setPositiveButton("Apply", (dlg, w) -> {
                    boolean[] h = d.hidden.clone();
                    for (int i = 0; i < used.length; i++) h[used[i]] = !checked[i + 1];
                    applyLayers(d, h, checked[0]);
                })
                .setNeutralButton("All on", (dlg, w) -> applyLayers(d,
                        new boolean[d.layerNames.length], d.mono))
                .setNegativeButton("Cancel", null).show();
    }

    private void applyLayers(DxfDoc d, boolean[] h, boolean mono) {
        d.hidden = h;
        d.mono = mono;
        ArrayList<String> off = new ArrayList<>();
        for (int i = 0; i < h.length; i++) if (h[i]) off.add(d.layerNames[i]);
        Store.setCadHiddenLayers(this, fileKey, off);
        Store.setCadMono(this, mono);
        renderer.refresh();
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

    // ---- site markup: pipes, ducts, fixtures and equipment ----

    @Override
    public void onEditSite(Marks.Item it) {
        siteDialog(it, pageIndex);
    }

    @Override
    public void onDraftChanged(int n) {
        runInfo.setText(n == 0 ? "Tap where the run starts. Drag to move around."
                : n == 1 ? "Tap the next point \u2014 it snaps straight or to 45\u00B0"
                : n + " points \u00B7 tap Done when the run is finished");
        runDone.setAlpha(n >= 2 ? 1f : 0.4f);
    }

    private void buildRunBar(FrameLayout mid) {
        runBar = new LinearLayout(this);
        runBar.setOrientation(LinearLayout.HORIZONTAL);
        runBar.setGravity(Gravity.CENTER_VERTICAL);
        runBar.setBackground(Ui.round(0xF2202228, Ui.dp(this, 14)));
        int p = Ui.dp(this, 12);
        runBar.setPadding(p, Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        runBar.setClickable(true); // touches on the bar don't reach the page

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        runTitle = Ui.label(this, "", 15, Ui.TEXT);
        runTitle.setTypeface(Typeface.DEFAULT_BOLD);
        runInfo = Ui.label(this, "", 12, Ui.DIM);
        texts.addView(runTitle);
        texts.addView(runInfo);
        runBar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView undoPt = Ui.barButton(this, "\u21B6");
        undoPt.setTextSize(22);
        undoPt.setOnClickListener(v -> page.undoDraftPoint());
        runBar.addView(undoPt, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 44)));
        runDone = Ui.barButton(this, "Done");
        runDone.setTextColor(Ui.ACCENT);
        runDone.setOnClickListener(v -> finishRunMode());
        runBar.addView(runDone, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        TextView cancel = Ui.barButton(this, "\u2715");
        cancel.setTextColor(Ui.DIM);
        cancel.setOnClickListener(v -> cancelRunMode());
        runBar.addView(cancel, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        int m = Ui.dp(this, 8);
        lp.setMargins(m, m, m, 0);
        runBar.setVisibility(View.GONE);
        mid.addView(runBar, lp);
    }

    private void menuRow(LinearLayout box, String text, int color, final AlertDialog[] dlg,
                         final Runnable action) {
        TextView t = Ui.label(this, text, 16, Ui.TEXT);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 11), Ui.dp(this, 4), Ui.dp(this, 11));
        if (color != 0) {
            android.graphics.drawable.GradientDrawable d = Ui.dot(color, 0, 0);
            int s = Ui.dp(this, 14);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawables(d, null, null, null);
            t.setCompoundDrawablePadding(Ui.dp(this, 12));
        }
        t.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            action.run();
        });
        box.addView(t);
    }

    private void mepMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, 0, p, Ui.dp(this, 8));
        final AlertDialog[] dlg = new AlertDialog[1];

        int count = marks.siteItems().size();
        if (count > 0) {
            menuRow(box, "\u2630   List of site items (" + count + ")", 0, dlg, this::siteList);
        }
        box.addView(dialogCaption("PIPES \u2014 then tap points along the route"));
        for (int i = 0; i < Mep.PIPE_NAMES.length; i++) {
            final int sys = i;
            menuRow(box, Mep.PIPE_NAMES[i] + "   " + Mep.PIPE_ABBR[i], Mep.PIPE_COLOR[i], dlg,
                    () -> startRun(Marks.Run.PIPE, sys));
        }
        box.addView(dialogCaption("DUCTS"));
        for (int i = 0; i < Mep.DUCT_NAMES.length; i++) {
            final int sys = i;
            menuRow(box, Mep.DUCT_NAMES[i] + "   " + Mep.DUCT_ABBR[i], Mep.DUCT_COLOR[i], dlg,
                    () -> startRun(Marks.Run.DUCT, sys));
        }
        box.addView(dialogCaption("FIXTURES & EQUIPMENT"));
        for (int i = 0; i < Mep.SYM_NAMES.length; i++) {
            final int sym = i;
            menuRow(box, Mep.SYM_NAMES[i], Mep.symColor(i), dlg, () -> placeSym(sym));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Site markup").setView(sv)
                .setNegativeButton("Close", null).show();
    }

    private void startRun(int kind, int sys) {
        setTool(PageView.HAND);
        bHand.setOn(false);
        page.startRun(kind, sys);
        boolean duct = kind == Marks.Run.DUCT;
        runTitle.setText((duct ? Mep.DUCT_NAMES[sys] + " duct" : Mep.PIPE_NAMES[sys] + " pipe"));
        runTitle.setTextColor(duct ? Mep.DUCT_COLOR[sys] : Mep.PIPE_COLOR[sys]);
        onDraftChanged(0);
        runBar.setVisibility(View.VISIBLE);
    }

    private void finishRunMode() {
        if (page.draftPoints() < 2) {
            Ui.toast(this, "Tap at least two points along the route first");
            return;
        }
        Marks.Run r = page.finishRun();
        runBar.setVisibility(View.GONE);
        bHand.setOn(true);
        marksChangedUi();
        if (r != null) siteDialog(r, pageIndex); // straight away: size, tag, note
    }

    private void cancelRunMode() {
        page.cancelRun();
        runBar.setVisibility(View.GONE);
        bHand.setOn(true);
    }

    private void placeSym(int sym) {
        setTool(PageView.HAND);
        Marks.Stamp s = new Marks.Stamp();
        s.kind = Marks.Stamp.SYM;
        s.sym = sym;
        s.color = Mep.symColor(sym);
        s.th = Ui.dp(this, 11) / page.viewScale();
        float aspect = Mep.SYM_ASPECT[sym];
        page.place(s, aspect, 0.12f * Math.max(1f, aspect * 0.65f), 0.15f);
        bHand.setOn(true);
        marksChangedUi();
    }

    /** Size, tag and note for a pipe, duct, fixture or piece of equipment. */
    private void siteDialog(final Marks.Item it, final int pg) {
        final boolean isRun = it instanceof Marks.Run;
        final Marks.Run r = isRun ? (Marks.Run) it : null;
        final Marks.Stamp s = isRun ? null : (Marks.Stamp) it;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, 0, p, 0);

        final int[] sys = {isRun ? r.sys : 0};
        if (isRun) {
            box.addView(dialogCaption("System"));
            String[] names = r.kind == Marks.Run.DUCT ? Mep.DUCT_NAMES : Mep.PIPE_NAMES;
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chipRow(names, r.clampSys(), v -> sys[0] = v));
            box.addView(hs);
        }

        box.addView(dialogCaption(isRun ? (r.kind == Marks.Run.DUCT ? "Size" : "Diameter")
                : "Size / capacity"));
        final EditText size = new EditText(this);
        size.setSingleLine(true);
        size.setText(isRun ? r.size : s.size);
        size.setHint(isRun ? (r.kind == Marks.Run.DUCT ? "e.g. 600x400" : "e.g. \u00D850") : "e.g. 2 TR, 600x600");
        box.addView(size);
        if (isRun) {
            final String[] chips = r.kind == Marks.Run.DUCT ? Mep.DUCT_SIZES : Mep.PIPE_SIZES;
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chipRow(chips, -1, v -> {
                size.setText(chips[v]);
                size.setSelection(size.length());
            }));
            box.addView(hs);
        }

        box.addView(dialogCaption("Tag"));
        final EditText tag = new EditText(this);
        tag.setSingleLine(true);
        tag.setText(isRun ? r.tag : s.tag);
        tag.setHint(isRun ? "e.g. P-12" : "e.g. FCU-01");
        box.addView(tag);

        box.addView(dialogCaption("Note"));
        final EditText note = new EditText(this);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        note.setMaxLines(6);
        note.setText(isRun ? r.note : s.note);
        note.setHint("e.g. runs above false ceiling, 300 mm lower than design");
        box.addView(note);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        String title = isRun ? r.describe() : Mep.SYM_NAMES[Math.max(0, Math.min(Mep.SYM_NAMES.length - 1, s.sym))];
        new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setPositiveButton("Done", (d, w) -> {
                    if (isRun) {
                        r.sys = sys[0];
                        r.size = size.getText().toString().trim();
                        r.tag = tag.getText().toString().trim();
                        r.note = note.getText().toString().trim();
                    } else {
                        s.size = size.getText().toString().trim();
                        s.tag = tag.getText().toString().trim();
                        s.note = note.getText().toString().trim();
                    }
                    page.refresh();
                    marksChangedUi();
                })
                .setNeutralButton("Delete", (d, w) -> {
                    marks.remove(pg, it);
                    page.refresh();
                    marksChangedUi();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Every pipe, duct, fixture and piece of equipment in the file, with its details. */
    private void siteList() {
        final ArrayList<Object[]> items = marks.siteItems();
        if (items.isEmpty()) {
            Ui.toast(this, "No site items yet");
            return;
        }
        String[] rows = new String[items.size()];
        for (int i = 0; i < rows.length; i++) {
            Object[] row = items.get(i);
            Marks.Item it = (Marks.Item) row[1];
            int num = (Integer) row[2];
            StringBuilder sb = new StringBuilder();
            if (num > 0) sb.append('(').append(num).append(")  ");
            sb.append(Mep.describe(it));
            String sz = Mep.sizeOf(it).trim(), tg = Mep.tagOf(it).trim(), nt = Mep.noteOf(it).trim();
            if (!sz.isEmpty()) sb.append("  ").append(sz);
            if (!tg.isEmpty()) sb.append("  \u00B7  ").append(tg);
            if (pageCount > 1) sb.append("  \u00B7  p.").append((Integer) row[0] + 1);
            if (!nt.isEmpty()) sb.append("\n").append(nt);
            rows[i] = sb.toString();
        }
        new AlertDialog.Builder(this).setTitle("Site items")
                .setItems(rows, (d, w) -> {
                    Object[] row = items.get(w);
                    int pg = (Integer) row[0];
                    if (pg != pageIndex) go(pg);
                    siteDialog((Marks.Item) row[1], pg);
                })
                .setNegativeButton("Close", null).show();
    }

    @Override
    public void onEditStamp(final Marks.Stamp s) {
        if (s.kind == Marks.Stamp.RECT || s.kind == Marks.Stamp.OVAL) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int pp = Ui.dp(this, 20);
            box.setPadding(pp, pp, pp, 0);
            final int[] color = {s.color};
            box.addView(dialogCaption("Color"));
            box.addView(colorRow(s.color, c -> color[0] = c, false));
            final int[] level = {levelOf(s)};
            box.addView(dialogCaption("Line thickness"));
            box.addView(chipRow(THICK_NAMES, level[0], v -> level[0] = v));
            new AlertDialog.Builder(this).setTitle("Edit shape").setView(box)
                    .setPositiveButton("Done", (d, w) -> {
                        s.color = color[0];
                        s.lw = thickness(level[0]);
                        Store.setShapeLevel(this, level[0]);
                        page.refresh();
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        final EditText text = new EditText(this);
        text.setText(s.text);
        text.setMinLines(1);
        text.setMaxLines(6);
        box.addView(text);

        final int[] font = {s.font};
        box.addView(dialogCaption("Font"));
        box.addView(chipRow(new String[]{"Simple", "Serif", "Typewriter", "Handwriting"}, s.font,
                v -> font[0] = v));

        box.addView(dialogCaption("Color"));
        final int[] color = {s.color};
        box.addView(colorRow(s.color, c -> color[0] = c, false));

        final int[] tick = {s.tick};
        box.addView(dialogCaption("Mark beside the text"));
        box.addView(chipRow(new String[]{"None", "✓ Green", "✓ Red", "✕ Red"}, s.tick,
                v -> tick[0] = v));

        final CheckBox boxed = new CheckBox(this);
        boxed.setText("Draw a frame around it");
        boxed.setChecked(s.boxed);
        box.addView(boxed);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Edit").setView(sv)
                .setPositiveButton("Done", (d, w) -> {
                    String t = text.getText().toString().trim();
                    if (!t.isEmpty()) s.text = t;
                    s.font = font[0];
                    s.color = color[0];
                    s.tick = tick[0];
                    s.boxed = boxed.isChecked();
                    page.stampReshaped(s);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void go(int index) {
        if (pageCount <= 0) return;
        if (page.drawingRun()) cancelRunMode();
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

    /** Color dots that share the row's width evenly, so they always fit the dialog. */
    private LinearLayout colorRow(int current, final ColorPick cb, boolean hi) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] colors = hi ? new int[]{0xFFFFEB3B, 0xFF69F0AE, 0xFF40C4FF, 0xFFFF8A80}
                : COLORS;
        int size = Ui.dp(this, 30);
        for (int i = 0; i < colors.length; i++) {
            final int c = colors[i];
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            View v = new View(this);
            v.setBackground(Ui.dot(c, c == current ? Ui.dp(this, 3) : Ui.dp(this, 1),
                    c == current ? Ui.ACCENT : 0x33000000));
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(size, size);
            dlp.gravity = Gravity.CENTER;
            cell.addView(v, dlp);
            row.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));
            cell.setOnClickListener(x -> {
                cb.pick(c);
                for (int j = 0; j < row.getChildCount(); j++) {
                    View ch = ((FrameLayout) row.getChildAt(j)).getChildAt(0);
                    boolean sel = j == idx;
                    ch.setBackground(Ui.dot(colors[j], sel ? Ui.dp(this, 3) : Ui.dp(this, 1),
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
        placeText(s, 0.42f);
    }

    private void placeText(Marks.Stamp s, float fracW) {
        float[] nat = Marks.StampArt.natural(s);
        page.place(s, nat[0] / nat[1], fracW, 0.35f);
        setTool(PageView.HAND);
        bHand.setOn(true);
    }

    // ---- ticks ----

    private void tickMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        final CheckBox sq = new CheckBox(this);
        sq.setText("Inside a square");
        sq.setChecked(Store.tickBoxed(this));
        box.addView(sq);

        final AlertDialog[] dlg = new AlertDialog[1];
        String[] names = {"✓   Green tick", "✓   Red tick", "✕   Red cross"};
        int[] colors = {Marks.StampArt.GREEN, Marks.StampArt.RED, Marks.StampArt.RED};
        for (int i = 0; i < 3; i++) {
            final int code = i + 1;
            TextView row = Ui.label(this, names[i], 17, colors[i]);
            row.setTypeface(Typeface.DEFAULT_BOLD);
            row.setPadding(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14));
            box.addView(row);
            row.setOnClickListener(v -> {
                Store.setTickBoxed(this, sq.isChecked());
                Marks.Stamp s = new Marks.Stamp();
                s.kind = Marks.Stamp.TICK;
                s.tick = code;
                s.boxed = sq.isChecked();
                page.place(s, 1f, 0.1f, 0.12f);
                setTool(PageView.HAND);
                bHand.setOn(true);
                if (dlg[0] != null) dlg[0].dismiss();
            });
        }
        dlg[0] = new AlertDialog.Builder(this).setTitle("Place a mark").setView(box)
                .setNegativeButton("Cancel", null).show();
    }

    // ---- shapes ----

    private static final String[] THICK_NAMES = {"Thin", "Medium", "Thick", "Extra"};
    private static final float[] THICK = {0.0015f, 0.003f, 0.006f, 0.011f};

    private float thickness(int level) {
        return Math.max(0.5f, page.pageMin() * THICK[Math.max(0, Math.min(3, level))]);
    }

    /** The thickness level closest to a shape's current line, for showing in the dialog. */
    private int levelOf(Marks.Stamp s) {
        float w = s.shapeWidth();
        int best = 1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < THICK.length; i++) {
            float d = Math.abs(thickness(i) - w);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void shapeMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 4), p, 0);

        final int[] level = {Store.shapeLevel(this)};
        box.addView(dialogCaption("Line thickness"));
        box.addView(chipRow(THICK_NAMES, level[0], v -> level[0] = v));
        box.addView(dialogCaption("Shape (uses the pen color)"));

        final AlertDialog[] dlg = new AlertDialog[1];
        String[] kinds = {"\u25AD   Rectangle", "\u25A1   Square", "\u2B2D   Oval", "\u25CB   Circle"};
        for (int i = 0; i < kinds.length; i++) {
            final int w = i;
            TextView row = Ui.label(this, kinds[i], 17, Ui.TEXT);
            row.setPadding(Ui.dp(this, 6), Ui.dp(this, 13), Ui.dp(this, 6), Ui.dp(this, 13));
            box.addView(row);
            row.setOnClickListener(v -> {
                Store.setShapeLevel(this, level[0]);
                Marks.Stamp s = new Marks.Stamp();
                s.kind = (w <= 1) ? Marks.Stamp.RECT : Marks.Stamp.OVAL;
                s.color = page.penColor;
                s.lw = thickness(level[0]);
                boolean even = (w == 1 || w == 3);
                page.place(s, even ? 1f : 1.6f, even ? 0.18f : 0.3f, 0.35f);
                setTool(PageView.HAND);
                bHand.setOn(true);
                if (dlg[0] != null) dlg[0].dismiss();
            });
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Place a shape").setView(sv)
                .setNegativeButton("Cancel", null).show();
    }

    // ---- comments (free text) ----

    private interface IntPick {
        void pick(int v);
    }

    /** A row of choice chips; the chosen one is highlighted. */
    private LinearLayout chipRow(final String[] names, int selected, final IntPick cb) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.label(this, names[i], 13, i == selected ? Ui.ACCENT : Ui.TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            if (i == selected) t.setBackground(Ui.round(0x33FFB020, Ui.dp(this, 10)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            row.addView(t, lp);
            t.setOnClickListener(v -> {
                cb.pick(idx);
                for (int j = 0; j < row.getChildCount(); j++) {
                    TextView ch = (TextView) row.getChildAt(j);
                    boolean sel = ch == v;
                    ch.setBackground(sel ? Ui.round(0x33FFB020, Ui.dp(this, 10)) : null);
                    ch.setTextColor(sel ? Ui.ACCENT : Ui.TEXT);
                }
            });
        }
        return row;
    }

    private TextView dialogCaption(String s) {
        TextView t = Ui.label(this, s, 12, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        return t;
    }

    private void textDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        final EditText text = new EditText(this);
        text.setHint("Write your comment…");
        text.setMinLines(1);
        text.setMaxLines(6);
        box.addView(text);

        final int[] font = {0};
        box.addView(dialogCaption("Font"));
        box.addView(chipRow(new String[]{"Simple", "Serif", "Typewriter", "Handwriting"}, 0,
                v -> font[0] = v));

        final int[] size = {1};
        box.addView(dialogCaption("Starting size (drag the corner later for any size)"));
        box.addView(chipRow(new String[]{"Small", "Medium", "Large"}, 1, v -> size[0] = v));

        box.addView(dialogCaption("Color"));
        final int[] color = {Store.stampColor(this)};
        box.addView(colorRow(color[0], c -> color[0] = c, false));

        final CheckBox boxed = new CheckBox(this);
        boxed.setText("Draw a frame around it");
        boxed.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(boxed);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Comment").setView(sv)
                .setPositiveButton("Place", (d, w) -> {
                    String t = text.getText().toString().trim();
                    if (t.isEmpty()) return;
                    Marks.Stamp s = new Marks.Stamp();
                    s.kind = Marks.Stamp.TEXT;
                    s.text = t;
                    s.color = color[0];
                    s.font = font[0];
                    s.boxed = boxed.isChecked();
                    placeText(s, size[0] == 0 ? 0.22f : size[0] == 1 ? 0.4f : 0.6f);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void stampMenu() {
        final ArrayList<Store.SavedStamp> saved = Store.stamps(this);
        String[] names = new String[saved.size() + 1];
        names[0] = "+  New stamp…";
        for (int i = 0; i < saved.size(); i++) {
            Store.SavedStamp st = saved.get(i);
            names[i + 1] = st.text.replace('\n', ' ') + (st.date ? "  (+ date)" : "")
                    + (st.tick == 1 ? "  ✓" : st.tick == 2 ? "  ✓" : st.tick == 3 ? "  ✕" : "");
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
                    s.tick = st.tick;
                    placeText(s, 0.42f);
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

        final int[] tick = {0};
        box.addView(dialogCaption("Mark beside the text"));
        box.addView(chipRow(new String[]{"None", "✓ Green", "✓ Red", "✕ Red"}, 0,
                v -> tick[0] = v));

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

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("New stamp").setView(scroll)
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
                        st.tick = tick[0];
                        saved.add(0, st);
                        Store.saveStamps(this, saved);
                    }
                    Marks.Stamp s = new Marks.Stamp();
                    s.kind = Marks.Stamp.TEXT;
                    s.text = withDate.isChecked() ? t + "\n" + today() : t;
                    s.color = color[0];
                    s.boxed = boxed.isChecked();
                    s.tick = tick[0];
                    placeText(s, 0.42f);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- saving the PDF ----

    private void askSave() {
        if (cad != null) {
            // A CAD drawing is always saved as a new PDF (the drawing plus any marks).
            new AlertDialog.Builder(this).setTitle("Save")
                    .setItems(new String[]{"Save as PDF"}, (d, w) -> saveCopy())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
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
        String low = name.toLowerCase(Locale.ROOT);
        String base = low.endsWith(".pdf") || low.endsWith(".dxf")
                ? name.substring(0, name.length() - 4) : name;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, base + (cad != null && marks.isEmpty() ? ".pdf" : " - marked.pdf"));
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
                File tmp = cad != null ? Exporter.buildCad(this, cad, marks, name)
                        : Exporter.build(this, uri, marks, dst != null, name);
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
        if (req == REQ_CROP) {
            if (res == RESULT_OK) {
                if (pendingSig) {
                    pendingSig = false;
                    placeSig();
                } else {
                    Ui.toast(this, "Signature saved");
                }
            } else {
                pendingSig = false;
            }
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_SAVE_COPY) {
            doSave(data.getData());
        } else if (req == REQ_SIG) {
            // Let the user crop out just the signature before it is cleaned and saved.
            Intent ci = new Intent(this, CropActivity.class);
            ci.setData(data.getData());
            ci.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(ci, REQ_CROP);
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
        if (page.drawingRun()) {
            cancelRunMode();
            return;
        }
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
