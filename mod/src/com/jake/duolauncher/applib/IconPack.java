package com.jake.duolauncher.applib;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads a standard (ADW / Nova / Apex compatible) icon pack: appfilter.xml + drawables. */
final class IconPack {

    /** Intent actions icon packs register so launchers can find them. */
    static final String[] ACTIONS = {
            "org.adw.launcher.THEMES",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "com.fede.launcher.THEME_ICONPACK",
            "com.dlto.atom.launcher.THEME",
            "ch.deletescape.lawnchair.ICONPACK",
            "app.lawnchair.icons.THEMED_ICON",
    };

    static final class Info {
        final String pkg;
        final String label;
        Info(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    final String pkg;
    final Resources res;
    /** flattened ComponentName → drawable name */
    final Map<String, String> components = new HashMap<>();
    /** package → first drawable mapped for it (fallback when the activity changed) */
    final Map<String, String> packages = new HashMap<>();
    final List<String> backs = new ArrayList<>();
    String mask;
    String upon;
    float scale = 1f;
    private List<String> all;

    private IconPack(String pkg, Resources res) {
        this.pkg = pkg;
        this.res = res;
    }

    static List<Info> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Info> found = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            List<ResolveInfo> list;
            try {
                list = pm.queryIntentActivities(new Intent(action), PackageManager.GET_META_DATA);
            } catch (Exception e) {
                continue;
            }
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                if (found.containsKey(p) || p.equals(ctx.getPackageName())) continue;
                String label = p;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    label = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Exception ignored) {
                }
                found.put(p, new Info(p, label));
            }
        }
        List<Info> out = new ArrayList<>(found.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static IconPack load(Context ctx, String pkg) {
        try {
            Resources res = ctx.getPackageManager().getResourcesForApplication(pkg);
            IconPack pack = new IconPack(pkg, res);
            pack.parseAppFilter();
            return pack;
        } catch (Exception e) {
            return null;
        }
    }

    private XmlPullParser open(String name) throws Exception {
        int id = res.getIdentifier(name, "xml", pkg);
        if (id != 0) return res.getXml(id);
        try {
            InputStream in = res.getAssets().open(name + ".xml");
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(in, "utf-8");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private void parseAppFilter() throws Exception {
        XmlPullParser p = open("appfilter");
        if (p == null) return;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev != XmlPullParser.START_TAG) continue;
            String tag = p.getName();
            if ("item".equals(tag)) {
                String comp = p.getAttributeValue(null, "component");
                String drawable = p.getAttributeValue(null, "drawable");
                if (comp == null || drawable == null || drawable.isEmpty()) continue;
                ComponentName cn = parseComponent(comp);
                if (cn == null) continue;
                String key = cn.flattenToString();
                if (!components.containsKey(key)) components.put(key, drawable);
                if (!packages.containsKey(cn.getPackageName())) packages.put(cn.getPackageName(), drawable);
            } else if ("iconback".equals(tag)) {
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    if (p.getAttributeName(i).startsWith("img")) backs.add(p.getAttributeValue(i));
                }
            } else if ("iconmask".equals(tag)) {
                mask = p.getAttributeValue(null, "img1");
            } else if ("iconupon".equals(tag)) {
                upon = p.getAttributeValue(null, "img1");
            } else if ("scale".equals(tag)) {
                try {
                    scale = Float.parseFloat(p.getAttributeValue(null, "factor"));
                } catch (Exception ignored) {
                }
            }
        }
    }

    static ComponentName parseComponent(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        String inner = s.substring(a + 1, b);
        int slash = inner.indexOf('/');
        if (slash <= 0 || slash == inner.length() - 1) return null;
        String p = inner.substring(0, slash).trim();
        String c = inner.substring(slash + 1).trim();
        if (c.startsWith(".")) c = p + c;
        return new ComponentName(p, c);
    }

    String drawableFor(ComponentName cn) {
        String d = components.get(cn.flattenToString());
        if (d == null) d = packages.get(cn.getPackageName());
        return d;
    }

    boolean canThemeUnsupported() {
        return !backs.isEmpty() || mask != null || upon != null;
    }

    Drawable drawable(String name) {
        if (name == null) return null;
        try {
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
            if (id == 0) return null;
            return res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Every icon the pack offers, for the per-app picker (drawable.xml, else appfilter). */
    synchronized List<String> allDrawables() {
        if (all != null) return all;
        Set<String> names = new LinkedHashSet<>();
        try {
            XmlPullParser p = open("drawable");
            if (p != null) {
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG && "item".equals(p.getName())) {
                        String d = p.getAttributeValue(null, "drawable");
                        if (d != null && !d.isEmpty()) names.add(d);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (names.isEmpty()) {
            List<String> sorted = new ArrayList<>(new LinkedHashSet<>(components.values()));
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            names.addAll(sorted);
        }
        all = new ArrayList<>(names);
        return all;
    }

    static String prettyName(String drawable) {
        String s = drawable.replace('_', ' ').trim();
        if (s.isEmpty()) return drawable;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
