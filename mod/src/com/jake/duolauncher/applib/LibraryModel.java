package com.jake.duolauncher.applib;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user's App Library customisations, stored as JSON in SharedPreferences:
 * <ul>
 *   <li>custom folders (ordered),</li>
 *   <li>per-app folder assignments (app id → folder name),</li>
 *   <li>hidden folders (deleted automatic categories, or "Recently Added").</li>
 * </ul>
 * Anything not customised falls back to the automatic category.
 */
final class LibraryModel {

    final List<String> custom = new ArrayList<>();
    final Map<String, String> assign = new HashMap<>();
    final Set<String> hidden = new HashSet<>();

    private final SharedPreferences prefs;

    LibraryModel(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    synchronized void load() {
        custom.clear();
        assign.clear();
        hidden.clear();
        try {
            JSONArray c = new JSONArray(prefs.getString("model.custom", "[]"));
            for (int i = 0; i < c.length(); i++) custom.add(c.getString(i));
            JSONObject a = new JSONObject(prefs.getString("model.assign", "{}"));
            for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                String k = it.next();
                assign.put(k, a.getString(k));
            }
            JSONArray h = new JSONArray(prefs.getString("model.hidden", "[]"));
            for (int i = 0; i < h.length(); i++) hidden.add(h.getString(i));
        } catch (Exception ignored) {
        }
    }

    synchronized void save() {
        prefs.edit()
                .putString("model.custom", new JSONArray(custom).toString())
                .putString("model.assign", new JSONObject(assign).toString())
                .putString("model.hidden", new JSONArray(new ArrayList<>(hidden)).toString())
                .apply();
    }

    synchronized void reset() {
        custom.clear();
        assign.clear();
        hidden.clear();
        save();
    }

    /** True when {@code name} is already used by a folder the user can see. */
    synchronized boolean nameTaken(String name) {
        if (name.equalsIgnoreCase(AppLibraryView.RECENTLY_ADDED)
                || name.equalsIgnoreCase(AppLibraryView.OTHER)) {
            return true;
        }
        for (String c : custom) if (c.equalsIgnoreCase(name)) return true;
        for (String a : AppLibraryView.AUTO_NAMES) {
            if (a.equalsIgnoreCase(name) && !hidden.contains(a)) return true;
        }
        return false;
    }

    synchronized void createFolder(String name) {
        // Re-creating a deleted automatic category simply un-hides it.
        for (String a : AppLibraryView.AUTO_NAMES) {
            if (a.equalsIgnoreCase(name)) {
                hidden.remove(a);
                save();
                return;
            }
        }
        custom.add(0, name);
        save();
    }

    /** Deletes a folder. Its apps go back to their automatic category (or Other). */
    synchronized void deleteFolder(String name, boolean isCustom) {
        if (isCustom) custom.remove(name);
        else hidden.add(name);
        assign.values().removeIf(name::equals);
        save();
    }

    synchronized void renameCustom(String from, String to) {
        int i = custom.indexOf(from);
        if (i < 0) return;
        custom.set(i, to);
        for (Map.Entry<String, String> e : assign.entrySet()) {
            if (from.equals(e.getValue())) e.setValue(to);
        }
        save();
    }

    /**
     * Turns an automatic category into a custom folder with a new name, keeping
     * the apps it currently shows.
     */
    synchronized void renameAuto(String from, String to, List<String> appIds) {
        custom.add(0, to);
        hidden.add(from);
        for (String id : appIds) assign.put(id, to);
        for (Map.Entry<String, String> e : assign.entrySet()) {
            if (from.equals(e.getValue())) e.setValue(to);
        }
        save();
    }

    synchronized void move(String appId, String folder) {
        if (folder == null) assign.remove(appId);
        else assign.put(appId, folder);
        save();
    }
}
