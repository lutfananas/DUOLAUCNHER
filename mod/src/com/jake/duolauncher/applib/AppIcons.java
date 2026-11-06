package com.jake.duolauncher.applib;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Icon-pack theming for every app icon in the launcher. Patched into the
 * constructor of the launcher's app entry (G1.a), so home screen, dock, folders
 * and the App Library all get the themed icon.
 */
public final class AppIcons {

    private static volatile Context app;
    private static IconPack pack;
    private static String packPkg;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Map<String, IconPack> OTHER_PACKS = new HashMap<>();

    private AppIcons() {
    }

    /** Called from DuoApplication.onCreate (smali patch). */
    public static void init(Context context) {
        app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("duo_icon_pack", Context.MODE_PRIVATE);
    }

    static String currentPack(Context ctx) {
        return prefs(ctx).getString("pack", "");
    }

    static boolean themeUnsupported(Context ctx) {
        return prefs(ctx).getBoolean("themeAll", true);
    }

    static synchronized void setPack(Context ctx, String pkg) {
        prefs(ctx).edit().putString("pack", pkg == null ? "" : pkg).commit();
        resetCaches();
    }

    static synchronized void setThemeUnsupported(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("themeAll", on).commit();
        resetCaches();
    }

    /** Per-app icon: "pkg/drawable" from any pack, "orig" for the app's own icon, null to clear. */
    static synchronized void setOverride(Context ctx, ComponentName cn, String value) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (value == null) e.remove("ov:" + cn.flattenToString());
        else e.putString("ov:" + cn.flattenToString(), value);
        e.commit();
        resetCaches();
    }

    static String override(Context ctx, ComponentName cn) {
        return prefs(ctx).getString("ov:" + cn.flattenToString(), null);
    }

    private static void resetCaches() {
        CACHE.clear();
        pack = null;
        packPkg = null;
    }

    static synchronized IconPack packFor(Context ctx, String pkg) {
        if (pkg.equals(packPkg) && pack != null) return pack;
        IconPack p = OTHER_PACKS.get(pkg);
        if (p == null) {
            p = IconPack.load(ctx, pkg);
            if (p != null) OTHER_PACKS.put(pkg, p);
        }
        return p;
    }

    private static IconPack activePack(Context ctx) {
        String want = currentPack(ctx);
        if (want.isEmpty()) return null;
        if (!want.equals(packPkg)) {
            packPkg = want;
            pack = IconPack.load(ctx, want);
        }
        return pack;
    }

    /** Called from the patched G1.a constructor. Never throws; falls back to the original. */
    public static Bitmap themed(Bitmap original, ComponentName cn, UserHandle user) {
        Context ctx = app;
        if (ctx == null || original == null || cn == null) return original;
        try {
            synchronized (AppIcons.class) {
                String key = cn.flattenToString() + "#" + (user != null ? user.hashCode() : 0)
                        + "#" + original.getWidth();
                Bitmap cached = CACHE.get(key);
                if (cached != null && !cached.isRecycled()) return cached;
                Bitmap out = compute(ctx, original, cn, user);
                if (out == null || out == original) return original;
                CACHE.put(key, out);
                return out;
            }
        } catch (Throwable t) {
            return original;
        }
    }

    private static Bitmap compute(Context ctx, Bitmap original, ComponentName cn, UserHandle user) {
        int size = Math.max(48, Math.max(original.getWidth(), original.getHeight()));
        String ov = override(ctx, cn);
        if ("orig".equals(ov)) return original;
        if (ov != null) {
            int slash = ov.indexOf('/');
            if (slash > 0) {
                IconPack p = packFor(ctx, ov.substring(0, slash));
                Drawable d = p != null ? p.drawable(ov.substring(slash + 1)) : null;
                if (d != null) return badge(ctx, render(d, size), user);
            }
        }
        IconPack p = activePack(ctx);
        if (p == null) return original;
        Drawable d = p.drawable(p.drawableFor(cn));
        if (d != null) return badge(ctx, render(d, size), user);
        if (themeUnsupported(ctx) && p.canThemeUnsupported()) return masked(p, original, cn, size);
        return original;
    }

    static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static Bitmap badge(Context ctx, Bitmap icon, UserHandle user) {
        if (user == null || user.equals(Process.myUserHandle())) return icon;
        try {
            Drawable badged = ctx.getPackageManager().getUserBadgedIcon(
                    new BitmapDrawable(ctx.getResources(), icon), user);
            return render(badged, icon.getWidth());
        } catch (Exception e) {
            return icon;
        }
    }

    /** Pack-styled version of an icon the pack doesn't cover: back + scaled/masked icon + overlay. */
    private static Bitmap masked(IconPack p, Bitmap original, ComponentName cn, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Rect full = new Rect(0, 0, size, size);
        if (!p.backs.isEmpty()) {
            String back = p.backs.get(Math.abs(cn.hashCode()) % p.backs.size());
            Drawable bd = p.drawable(back);
            if (bd != null) {
                bd.setBounds(full);
                bd.draw(c);
            }
        }
        Bitmap fg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(fg);
        float s = size * (p.scale > 0 ? p.scale : 1f);
        float off = (size - s) / 2f;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        fc.drawBitmap(original, null, new RectF(off, off, off + s, off + s), paint);
        Drawable md = p.drawable(p.mask);
        if (md != null) {
            Bitmap mask = render(md, size);
            Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            fc.drawBitmap(mask, 0, 0, mp);
        }
        c.drawBitmap(fg, 0, 0, null);
        Drawable ud = p.drawable(p.upon);
        if (ud != null) {
            ud.setBounds(full);
            ud.draw(c);
        }
        return out;
    }

    /**
     * Asks the launcher to reload the given apps so their entries (and icons) are
     * rebuilt. {@code apps} is the launcher's app list (G1.a entries).
     */
    static void reload(com.jake.duolauncher.MainActivity act, List<?> apps, String onlyPackage) {
        if (act == null || apps == null) return;
        try {
            G1.f3 model = act.n();
            Set<String> done = new HashSet<>();
            for (Object o : apps) {
                if (!(o instanceof G1.a)) continue;
                G1.a a = (G1.a) o;
                if (a.d == null || a.e == null) continue;
                String p = a.d.getPackageName();
                if (onlyPackage != null && !onlyPackage.equals(p)) continue;
                if (done.add(p + "#" + a.e.hashCode())) model.s(p, a.e);
            }
        } catch (Throwable ignored) {
        }
    }
}
