package com.sheet.viewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/** Home screen: open a PDF, or pick one opened before. */
public final class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(p, Ui.dp(this, 36), p, 0);

        TextView title = Ui.label(this, "Sheet", 30, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = Ui.label(this, "Drawings · markup · signature · stamps", 14, Ui.DIM);
        root.addView(sub);

        TextView open = Ui.label(this, "Open PDF", 17, 0xFF15161A);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setGravity(Gravity.CENTER);
        open.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54));
        lp.topMargin = Ui.dp(this, 24);
        root.addView(open, lp);
        open.setOnClickListener(v -> pick());

        TextView recent = Ui.label(this, "RECENT", 12, Ui.DIM);
        recent.setTypeface(Typeface.DEFAULT_BOLD);
        recent.setLetterSpacing(0.12f);
        recent.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 8));
        root.addView(recent);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        list.removeAllViews();
        ArrayList<String[]> recents = Store.recents(this);
        if (recents.isEmpty()) {
            list.addView(Ui.label(this, "Files you open will show here.", 14, Ui.DIM));
            return;
        }
        for (final String[] r : recents) {
            TextView row = Ui.label(this, r[1], 15, Ui.TEXT);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 12)));
            row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
            lp.bottomMargin = Ui.dp(this, 8);
            list.addView(row, lp);
            row.setOnClickListener(v -> view(Uri.parse(r[0])));
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Store.removeRecent(MainActivity.this, r[0]);
                    fill();
                    Ui.toast(MainActivity.this, "Removed from the list (the file is not deleted)");
                    return true;
                }
            });
        }
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Ui.toast(this, "No file picker found on this device");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_OPEN || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        // Keep the right to read (and, where allowed, overwrite) this file later.
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        Store.addRecent(this, u.toString(), Ui.displayName(this, u));
        view(u);
    }

    private void view(Uri u) {
        Intent i = new Intent(this, ViewerActivity.class);
        i.setData(u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivity(i);
    }
}
