package com.sheet.viewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Small things the app remembers: recent files, saved stamps, last page of each file. */
final class Store {
    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("sheet", Context.MODE_PRIVATE);
    }

    // ---- recent files: each entry is {uri, name} ----

    static ArrayList<String[]> recents(Context c) {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("recents", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.getString("u"), o.getString("n")});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveRecents(Context c, ArrayList<String[]> list) {
        JSONArray a = new JSONArray();
        try {
            for (String[] r : list) {
                JSONObject o = new JSONObject();
                o.put("u", r[0]);
                o.put("n", r[1]);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        p(c).edit().putString("recents", a.toString()).apply();
    }

    static void addRecent(Context c, String uri, String name) {
        ArrayList<String[]> list = recents(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(uri)) list.remove(i);
        list.add(0, new String[]{uri, name});
        while (list.size() > 15) list.remove(list.size() - 1);
        saveRecents(c, list);
    }

    static void removeRecent(Context c, String uri) {
        ArrayList<String[]> list = recents(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(uri)) list.remove(i);
        saveRecents(c, list);
    }

    // ---- saved stamps ----

    static final class SavedStamp {
        String text;
        int color;
        boolean boxed;
        boolean date;
        int tick; // 0 none, 1 green check, 2 red check, 3 red cross
    }

    static ArrayList<SavedStamp> stamps(Context c) {
        ArrayList<SavedStamp> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("stamps", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                SavedStamp s = new SavedStamp();
                s.text = o.getString("t");
                s.color = o.getInt("c");
                s.boxed = o.optBoolean("b", true);
                s.date = o.optBoolean("d", false);
                s.tick = o.optInt("tk", 0);
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveStamps(Context c, ArrayList<SavedStamp> list) {
        JSONArray a = new JSONArray();
        try {
            for (SavedStamp s : list) {
                JSONObject o = new JSONObject();
                o.put("t", s.text);
                o.put("c", s.color);
                o.put("b", s.boxed);
                o.put("d", s.date);
                o.put("tk", s.tick);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        p(c).edit().putString("stamps", a.toString()).apply();
    }

    // ---- last page and last stamp colour ----

    static int lastPage(Context c, String key) {
        return p(c).getInt("page_" + key, 0);
    }

    static void setLastPage(Context c, String key, int page) {
        p(c).edit().putInt("page_" + key, page).apply();
    }

    static int stampColor(Context c) {
        return p(c).getInt("stampColor", 0xFFE53935);
    }

    static void setStampColor(Context c, int color) {
        p(c).edit().putInt("stampColor", color).apply();
    }
}
