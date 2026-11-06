package com.jake.duolauncher.applib;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An iPhone-style "App Library" page: a search field on top, then a two-column
 * grid of category folders (Suggestions, Recently Added, Social, ...). Each folder
 * shows three large icons that launch directly plus a cluster of small icons that
 * opens the whole category. Searching shows an alphabetical list of every app.
 */
public final class AppLibraryView extends FrameLayout {

    static final String SUGGESTIONS = "Suggestions";
    static final String RECENTLY_ADDED = "Recently Added";
    static final String OTHER = "Other";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Map<String, Integer> CATEGORY_CACHE = new HashMap<>();
    private static final Map<String, long[]> INSTALL_CACHE = new HashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final boolean dark;
    private final SharedPreferences prefs;

    private final LinearLayout content;
    private final EditText search;
    private final TextView cancel;
    private final ScrollView gridScroll;
    private final LinearLayout grid;
    private final ScrollView listScroll;
    private final LinearLayout list;
    private final FrameLayout overlay;

    private List<?> sourceApps;
    private List<Entry> entries = new ArrayList<>();
    private List<Category> categories = new ArrayList<>();
    private int generation;
    private boolean searching;
    private boolean overlayOpen;

    static final class Entry {
        final G1.a app;
        final String label;
        final String sortKey;
        final String pkg;
        Entry(G1.a app) {
            this.app = app;
            this.label = app.b != null ? app.b : "";
            this.sortKey = normalize(this.label);
            this.pkg = app.d != null ? app.d.getPackageName() : "";
        }
        Bitmap icon() {
            return app.c;
        }
        boolean available() {
            return app.i;
        }
        String id() {
            return app.a != null ? app.a : pkg;
        }
    }

    static final class Category {
        final String name;
        final List<Entry> apps;
        final boolean fourLarge;
        Category(String name, List<Entry> apps, boolean fourLarge) {
            this.name = name;
            this.apps = apps;
            this.fourLarge = fourLarge;
        }
    }

    public AppLibraryView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        prefs = context.getSharedPreferences("duo_app_library", Context.MODE_PRIVATE);
        setClipChildren(false);
        setFocusable(true);
        setFocusableInTouchMode(true);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Search row
        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(4), dp(6), dp(4), dp(14));

        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint("App Library");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0xCCFFFFFF);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(10), 0, dp(12), 0);
        search.setCompoundDrawablePadding(dp(8));
        MagnifierDrawable glass = new MagnifierDrawable(0xCCFFFFFF, density);
        glass.setBounds(0, 0, dp(18), dp(18));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setBackground(roundRect(tileColor(), dp(12)));
        search.setShadowLayer(0, 0, 0, 0);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(42), 1f));

        cancel = new TextView(context);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        cancel.setPadding(dp(14), dp(8), dp(4), dp(8));
        cancel.setShadowLayer(4 * density, 0, density, 0x66000000);
        cancel.setVisibility(GONE);
        cancel.setOnClickListener(v -> exitSearch());
        searchRow.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Category grid
        gridScroll = new ScrollView(context);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        gridScroll.setClipToPadding(false);
        gridScroll.setPadding(0, 0, 0, dp(24));
        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(4), 0, dp(4), 0);
        gridScroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(gridScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Alphabetical search list
        listScroll = new ScrollView(context);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(0, 0, 0, dp(24));
        listScroll.setVisibility(GONE);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(4));
        listScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Expanded category overlay
        overlay = new FrameLayout(context);
        overlay.setVisibility(GONE);
        overlay.setClickable(true);
        overlay.setBackgroundColor(dark ? 0x99000000 : 0x66000000);
        overlay.setOnClickListener(v -> closeOverlay());
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        search.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) enterSearch();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (s.length() > 0 && !searching) enterSearch();
                if (searching) rebuildList();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                List<Entry> matches = filtered(search.getText().toString());
                if (!matches.isEmpty()) launch(matches.get(0), v, null);
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- data

    void setApps(List<?> apps) {
        if (apps == null || apps == sourceApps) return;
        sourceApps = apps;
        final List<Entry> next = new ArrayList<>(apps.size());
        for (Object o : apps) {
            if (o instanceof G1.a) next.add(new Entry((G1.a) o));
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(next, (x, y) -> collator.compare(x.label, y.label));
        entries = next;
        if (searching) rebuildList();

        final int gen = ++generation;
        final PackageManager pm = getContext().getPackageManager();
        WORKER.execute(() -> {
            final List<Category> built = buildCategories(pm, next);
            main.post(() -> {
                if (gen != generation) return;
                categories = built;
                rebuildGrid();
            });
        });
    }

    private List<Category> buildCategories(PackageManager pm, List<Entry> all) {
        List<Category> out = new ArrayList<>();

        // Suggestions: apps launched most often / most recently from this page.
        List<Entry> suggestions = new ArrayList<>();
        final Map<Entry, Double> scores = new HashMap<>();
        for (Entry e : all) {
            String v = prefs.getString("use:" + e.id(), null);
            if (v == null) continue;
            try {
                String[] p = v.split(",");
                double count = Double.parseDouble(p[0]);
                double hours = Long.parseLong(p[1]) / 3_600_000d;
                scores.put(e, hours + 6 * Math.log1p(count));
            } catch (RuntimeException ignored) {
            }
        }
        List<Entry> used = new ArrayList<>(scores.keySet());
        Collections.sort(used, (x, y) -> Double.compare(scores.get(y), scores.get(x)));
        for (Entry e : used) {
            if (suggestions.size() < 4) suggestions.add(e);
        }

        // Recently Added: newest user-installed apps.
        List<Entry> recent = new ArrayList<>();
        final Map<Entry, Long> installed = new HashMap<>();
        for (Entry e : all) {
            long[] info = installInfo(pm, e.pkg);
            if (info[1] == 0) installed.put(e, info[0]);
        }
        recent.addAll(installed.keySet());
        Collections.sort(recent, (x, y) -> Long.compare(installed.get(y), installed.get(x)));
        if (recent.size() > 16) recent = new ArrayList<>(recent.subList(0, 16));

        for (Entry e : recent) {
            if (suggestions.size() >= 4) break;
            if (!suggestions.contains(e)) suggestions.add(e);
        }
        for (Entry e : all) {
            if (suggestions.size() >= 4) break;
            if (!suggestions.contains(e)) suggestions.add(e);
        }
        if (!suggestions.isEmpty()) out.add(new Category(SUGGESTIONS, suggestions, true));
        if (!recent.isEmpty()) out.add(new Category(RECENTLY_ADDED, recent, false));

        // Categories
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : all) {
            String name = categoryName(categoryOf(pm, e.pkg), e.pkg, e.label);
            List<Entry> g = groups.get(name);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(name, g);
            }
            g.add(e);
        }
        List<Map.Entry<String, List<Entry>>> sorted = new ArrayList<>(groups.entrySet());
        Collections.sort(sorted, (x, y) -> {
            boolean xo = OTHER.equals(x.getKey()), yo = OTHER.equals(y.getKey());
            if (xo != yo) return xo ? 1 : -1;
            double sx = 0, sy = 0;
            for (Entry e : x.getValue()) if (scores.containsKey(e)) sx += 1;
            for (Entry e : y.getValue()) if (scores.containsKey(e)) sy += 1;
            if (sx != sy) return Double.compare(sy, sx);
            int c = Integer.compare(y.getValue().size(), x.getValue().size());
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        for (Map.Entry<String, List<Entry>> g : sorted) {
            List<Entry> apps = g.getValue();
            // Most-used apps first inside a folder, like iOS.
            final List<Entry> ordered = new ArrayList<>(apps);
            Collections.sort(ordered, (x, y) -> {
                Double a = scores.get(x), b = scores.get(y);
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                return Double.compare(b, a);
            });
            out.add(new Category(g.getKey(), ordered, false));
        }
        return out;
    }

    private static int categoryOf(PackageManager pm, String pkg) {
        synchronized (CATEGORY_CACHE) {
            Integer c = CATEGORY_CACHE.get(pkg);
            if (c != null) return c;
        }
        int cat = ApplicationInfo.CATEGORY_UNDEFINED;
        try {
            cat = pm.getApplicationInfo(pkg, 0).category;
        } catch (Exception ignored) {
        }
        synchronized (CATEGORY_CACHE) {
            CATEGORY_CACHE.put(pkg, cat);
        }
        return cat;
    }

    /** Returns {firstInstallTime, isSystem(1/0)}. */
    private static long[] installInfo(PackageManager pm, String pkg) {
        synchronized (INSTALL_CACHE) {
            long[] c = INSTALL_CACHE.get(pkg);
            if (c != null) return c;
        }
        long[] info = {0, 1};
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            boolean system = pi.applicationInfo != null
                    && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            info = new long[]{pi.firstInstallTime, system ? 1 : 0};
        } catch (Exception ignored) {
        }
        synchronized (INSTALL_CACHE) {
            INSTALL_CACHE.put(pkg, info);
        }
        return info;
    }

    static String categoryName(int category, String pkg, String label) {
        String s = (pkg + " " + label).toLowerCase(Locale.ROOT);
        // Strong keyword matches first: many apps don't declare a category.
        if (has(s, "game", "games", "mobilelegends", "pubg", "freefire", "roblox", "minecraft",
                "supercell", "clashof", "candycrush", "genshin", "hoyoverse", "moonton", "garena", "chess")) {
            return "Games";
        }
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO: return "Entertainment";
            case ApplicationInfo.CATEGORY_IMAGE: return "Creativity";
            case ApplicationInfo.CATEGORY_NEWS: return "Information & Reading";
            case ApplicationInfo.CATEGORY_MAPS: return "Travel";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Productivity & Finance";
            case ApplicationInfo.CATEGORY_ACCESSIBILITY: return "Utilities";
            default: break;
        }
        if (has(s, "whatsapp", "telegram", "facebook", "instagram", "twitter", "messenger", "discord",
                "snapchat", "signal", "wechat", "messaging", "mms", "contacts", "dialer", "phone",
                "threads", "linkedin", "reddit", "jp.naver.line", "viber", "skype", "tinder", "bumble",
                "bluesky", "mastodon", "michat", "kakao")) {
            return "Social";
        }
        if (has(s, "youtube", "netflix", "spotify", "music", "video", "tiktok", "musically", "twitch",
                "primevideo", "disney", "hotstar", "player", "podcast", "vidio", "joox", "soundcloud",
                "deezer", "radio", "iqiyi", "wetv", "viu", "hbo", "tv", "anime", "webtoon", "movie")) {
            return "Entertainment";
        }
        if (has(s, "camera", "photo", "gallery", "snapseed", "lightroom", "capcut", "canva", "picsart",
                "editor", "vsco", "inshot", "remini", "draw", "sketch", "paint")) {
            return "Creativity";
        }
        if (has(s, "docs", "sheets", "slides", "drive", "office", "word", "excel", "powerpoint", "gmail",
                "mail", "outlook", "calendar", "notes", "keep", "bank", "pay", "wallet", "finance",
                "bca", "mandiri", "bri", "bni", "dana", "ovo", "gopay", "jenius", "notion", "todo",
                "task", "zoom", "meet", "teams", "slack", "trello", "pdf", "scanner", "money", "invest",
                "bibit", "ajaib", "stock", "crypto", "binance", "indodax", "paypal", "authenticator")) {
            return "Productivity & Finance";
        }
        if (has(s, "shop", "tokopedia", "shopee", "lazada", "amazon", "blibli", "bukalapak", "food",
                "alfamart", "indomaret", "supermarket", "alfagift", "store.", "ikea", "zalora", "temu", "aliexpress", "mcdonald",
                "kfc", "starbucks", "restaurant", "grocery")) {
            return "Shopping & Food";
        }
        if (has(s, "maps", "waze", "grab", "gojek", "uber", "traveloka", "tiket", "airbnb", "booking",
                "krl", "transport", "flight", "airline", "garuda", "lionair", "kai.", "trip", "travel",
                "agoda", "maxim", "indrive")) {
            return "Travel";
        }
        if (has(s, "health", "fit", "strava", "halodoc", "alodokter", "workout", "sleep", "medit",
                "calm", "headspace", "step", "peduli", "satusehat", "bpjs")) {
            return "Health & Fitness";
        }
        if (has(s, "news", "kindle", "books", "wiki", "reader", "detik", "kompas", "medium", "quora",
                "dictionary", "translate", "duolingo", "learn", "quizlet", "brainly",
                "ruangguru", "weather", "flipboard")) {
            return "Information & Reading";
        }
        if (has(s, "settings", "calculator", "clock", "files", "filemanager", "browser", "chrome",
                "firefox", "opera", "launcher", "vpn", "recorder", "compass", "flashlight", "torch",
                "vending", "googlequicksearchbox", "assistant", "gms", "security", "cleaner", "backup",
                "bluetooth", "wifi", "clipboard", "keyboard", "inputmethod", "android.", "samsung",
                "miui", "xiaomi", "oppo", "coloros", "vivo", "huawei", "sec.", "nothing",
                "motorola", "oneplus", "google.android")) {
            return "Utilities";
        }
        return OTHER;
    }

    private static boolean has(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private List<Entry> filtered(String query) {
        String q = normalize(query);
        if (q.isEmpty()) return entries;
        List<Entry> starts = new ArrayList<>();
        List<Entry> contains = new ArrayList<>();
        for (Entry e : entries) {
            if (e.sortKey.startsWith(q)) starts.add(e);
            else if (e.sortKey.contains(q) || e.pkg.toLowerCase(Locale.ROOT).contains(q)) contains.add(e);
        }
        starts.addAll(contains);
        return starts;
    }

    // ---------------------------------------------------------------- grid

    private void rebuildGrid() {
        grid.removeAllViews();
        Context ctx = getContext();
        for (int i = 0; i < categories.size(); i += 2) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(j == 0 ? 6 : 12);
                lp.rightMargin = dp(j == 0 ? 12 : 6);
                if (i + j < categories.size()) {
                    row.addView(folder(categories.get(i + j)), lp);
                } else {
                    row.addView(new View(ctx), lp);
                }
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(14);
            grid.addView(row, rlp);
        }
    }

    private View folder(Category cat) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        TileView tile = new TileView(ctx, cat);
        box.addView(tile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = new TextView(ctx);
        label.setText(cat.name);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(4 * density, 0, density, 0x80000000);
        label.setPadding(0, dp(6), 0, 0);
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private int tileColor() {
        return dark ? 0x59303034 : 0x47FFFFFF;
    }

    /** A category folder: 3 large icons + a 2x2 cluster of small icons. */
    final class TileView extends View {
        private final Category cat;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF tmp = new RectF();
        private final GestureDetector gestures;
        private int pressed = -1;

        TileView(Context ctx, Category cat) {
            super(ctx);
            this.cat = cat;
            bg.setColor(tileColor());
            setContentDescription(cat.name);
            gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) {
                    pressed = quadrant(e.getX(), e.getY());
                    invalidate();
                    return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    tap(quadrant(e.getX(), e.getY()));
                    return true;
                }
                @Override public void onLongPress(MotionEvent e) {
                    int q = quadrant(e.getX(), e.getY());
                    Entry target = single(q);
                    pressed = -1;
                    invalidate();
                    if (target != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        showAppMenu(TileView.this, target);
                    } else if (q >= 0) {
                        openCategory(cat);
                    }
                }
            });
        }

        private boolean cluster() {
            return !cat.fourLarge && cat.apps.size() > 4;
        }

        /** The app a quadrant launches directly, or null if it opens the folder. */
        private Entry single(int q) {
            if (q < 0) return null;
            if (q == 3 && cluster()) return null;
            return q < cat.apps.size() ? cat.apps.get(q) : null;
        }

        private void tap(int q) {
            if (q < 0) return;
            Entry e = single(q);
            if (e != null) {
                launch(e, this, cell(q));
            } else if (q == 3) {
                openCategory(cat);
            }
        }

        private int quadrant(float x, float y) {
            for (int q = 0; q < 4; q++) {
                RectF r = cell(q);
                float slop = getWidth() * 0.04f;
                if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) {
                    return q;
                }
            }
            return -1;
        }

        private RectF cell(int q) {
            float w = getWidth();
            float pad = w * 0.09f;
            float gap = w * 0.08f;
            float size = (w - pad * 2 - gap) / 2f;
            float left = pad + (q % 2) * (size + gap);
            float top = pad + (q / 2) * (size + gap);
            return new RectF(left, top, left + size, top + size);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean handled = gestures.onTouchEvent(event);
            int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (pressed != -1) {
                    pressed = -1;
                    invalidate();
                }
            }
            return handled || super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            setMeasuredDimension(w, w);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float radius = w * 0.2f;
            c.drawRoundRect(0, 0, w, getHeight(), radius, radius, bg);
            int n = cat.apps.size();
            for (int q = 0; q < 4; q++) {
                RectF r = cell(q);
                if (pressed == q) inset(r, r.width() * 0.06f);
                if (q == 3 && cluster()) {
                    float mg = r.width() * 0.12f;
                    float ms = (r.width() - mg) / 2f;
                    for (int k = 0; k < 4 && 3 + k < n; k++) {
                        float l = r.left + (k % 2) * (ms + mg);
                        float t = r.top + (k / 2) * (ms + mg);
                        tmp.set(l, t, l + ms, t + ms);
                        drawIcon(c, cat.apps.get(3 + k), tmp);
                    }
                } else if (q < n) {
                    drawIcon(c, cat.apps.get(q), r);
                }
            }
        }

        private void drawIcon(Canvas c, Entry e, RectF r) {
            Bitmap b = e.icon();
            if (b == null || b.isRecycled()) return;
            icon.setAlpha(e.available() ? 255 : 110);
            c.drawBitmap(b, null, r, icon);
        }
    }

    private static void inset(RectF r, float d) {
        r.left += d;
        r.top += d;
        r.right -= d;
        r.bottom -= d;
    }

    // ---------------------------------------------------------------- category overlay

    private void openCategory(Category cat) {
        Context ctx = getContext();
        overlay.removeAllViews();

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(28), dp(16), dp(16));

        TextView title = new TextView(ctx);
        title.setText(cat.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setShadowLayer(6 * density, 0, density, 0x66000000);
        title.setPadding(0, 0, 0, dp(22));
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        final int cols = 4;
        for (int i = 0; i < cat.apps.size(); i += cols) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i + j < cat.apps.size()) {
                    row.addView(appCell(cat.apps.get(i + j)), lp);
                } else {
                    row.addView(new View(ctx), lp);
                }
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(18);
            rows.addView(row, rlp);
        }
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Taps on empty space close the folder; taps on the panel itself don't.
        panel.setClickable(false);
        overlay.addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        overlayOpen = true;
        overlay.setVisibility(VISIBLE);
        overlay.setAlpha(0f);
        panel.setScaleX(0.88f);
        panel.setScaleY(0.88f);
        overlay.animate().alpha(1f).setDuration(180).setListener(null).start();
        panel.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        content.setRenderEffect(RenderEffect.createBlurEffect(dp(18), dp(18), Shader.TileMode.CLAMP));
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.requestFocus();
    }

    private View appCell(final Entry e) {
        Context ctx = getContext();
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(e.icon());
        iv.setAlpha(e.available() ? 1f : 0.45f);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView tv = new TextView(ctx);
        tv.setText(e.label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setShadowLayer(4 * density, 0, density, 0x80000000);
        tv.setPadding(dp(2), dp(5), dp(2), 0);
        cell.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(v -> launch(e, iv, null));
        cell.setOnLongClickListener(v -> {
            showAppMenu(iv, e);
            return true;
        });
        return cell;
    }

    private void closeOverlay() {
        if (!overlayOpen) return;
        overlayOpen = false;
        content.setRenderEffect(null);
        overlay.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!overlayOpen) {
                    overlay.setVisibility(GONE);
                    overlay.removeAllViews();
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- search

    private void enterSearch() {
        if (searching) return;
        searching = true;
        cancel.setVisibility(VISIBLE);
        gridScroll.setVisibility(GONE);
        listScroll.setVisibility(VISIBLE);
        rebuildList();
    }

    private void exitSearch() {
        searching = false;
        search.setText("");
        search.clearFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        cancel.setVisibility(GONE);
        listScroll.setVisibility(GONE);
        gridScroll.setVisibility(VISIBLE);
        list.removeAllViews();
        requestFocus();
    }

    private void rebuildList() {
        list.removeAllViews();
        String q = search.getText().toString();
        List<Entry> items = filtered(q);
        Context ctx = getContext();
        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No Results");
            empty.setTextColor(0xCCFFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(0, dp(40), 0, 0);
            empty.setShadowLayer(4 * density, 0, density, 0x80000000);
            list.addView(empty);
            return;
        }
        boolean showHeaders = normalize(q).isEmpty();
        String lastHeader = null;
        LinearLayout section = null;
        for (final Entry e : items) {
            String h = header(e);
            if (section == null || (showHeaders && !h.equals(lastHeader))) {
                if (showHeaders) {
                    TextView head = new TextView(ctx);
                    head.setText(h);
                    head.setTextColor(0xFFFFFFFF);
                    head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    head.setTypeface(Typeface.DEFAULT_BOLD);
                    head.setShadowLayer(4 * density, 0, density, 0x80000000);
                    head.setPadding(dp(6), dp(14), 0, dp(6));
                    list.addView(head);
                }
                section = new LinearLayout(ctx);
                section.setOrientation(LinearLayout.VERTICAL);
                section.setBackground(roundRect(tileColor(), dp(14)));
                section.setPadding(dp(4), dp(2), dp(4), dp(2));
                list.addView(section, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                lastHeader = h;
            }
            if (section.getChildCount() > 0) {
                View divider = new View(ctx);
                divider.setBackgroundColor(0x33FFFFFF);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
                dlp.leftMargin = dp(58);
                section.addView(divider, dlp);
            }
            section.addView(listRow(e));
        }
    }

    private View listRow(final Entry e) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(e.icon());
        iv.setAlpha(e.available() ? 1f : 0.45f);
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = new TextView(ctx);
        tv.setText(e.app.h ? e.label + "  ·  Work" : e.label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(14), 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> launch(e, iv, null));
        row.setOnLongClickListener(v -> {
            showAppMenu(iv, e);
            return true;
        });
        return row;
    }

    private static String header(Entry e) {
        if (e.sortKey.isEmpty()) return "#";
        char c = Character.toUpperCase(e.sortKey.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    // ---------------------------------------------------------------- actions

    private void launch(Entry e, View from, RectF local) {
        Rect bounds = new Rect();
        int[] loc = new int[2];
        from.getLocationInWindow(loc);
        if (local != null) {
            bounds.set(loc[0] + (int) local.left, loc[1] + (int) local.top,
                    loc[0] + (int) local.right, loc[1] + (int) local.bottom);
        } else {
            bounds.set(loc[0], loc[1], loc[0] + from.getWidth(), loc[1] + from.getHeight());
        }
        recordUse(e);
        Activity act = activity();
        if (act instanceof com.jake.duolauncher.MainActivity) {
            ((com.jake.duolauncher.MainActivity) act).o(e.app, bounds);
        } else {
            try {
                LauncherApps la = getContext().getSystemService(LauncherApps.class);
                la.startMainActivity(e.app.d, e.app.e, bounds, null);
            } catch (Exception ignored) {
            }
        }
        if (searching) main.postDelayed(this::exitSearch, 400);
        if (overlayOpen) main.postDelayed(this::closeOverlay, 400);
    }

    private void recordUse(Entry e) {
        String key = "use:" + e.id();
        int count = 0;
        String v = prefs.getString(key, null);
        if (v != null) {
            try {
                count = Integer.parseInt(v.split(",")[0]);
            } catch (RuntimeException ignored) {
            }
        }
        prefs.edit().putString(key, (count + 1) + "," + System.currentTimeMillis()).apply();
        // Refresh Suggestions next time the list is rebuilt.
        final List<?> apps = sourceApps;
        main.postDelayed(() -> {
            if (apps == sourceApps && apps != null) {
                sourceApps = null;
                setApps(apps);
            }
        }, 1200);
    }

    private void showAppMenu(View anchor, final Entry e) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 2, 1, "App info");
        long[] info = installInfo(getContext().getPackageManager(), e.pkg);
        if (info[1] == 0) menu.getMenu().add(0, 3, 2, "Uninstall");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    launch(e, anchor, null);
                    return true;
                case 2:
                    try {
                        LauncherApps la = getContext().getSystemService(LauncherApps.class);
                        la.startAppDetailsActivity(e.app.d, e.app.e, null, null);
                    } catch (Exception ignored) {
                    }
                    return true;
                case 3:
                    try {
                        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(i);
                    } catch (Exception ignored) {
                    }
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private Activity activity() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && (overlayOpen || searching)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (overlayOpen) closeOverlay();
                else exitSearch();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (overlayOpen) {
            overlayOpen = false;
            content.setRenderEffect(null);
            overlay.setVisibility(GONE);
            overlay.removeAllViews();
        }
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Small magnifying-glass glyph for the search field. */
    static final class MagnifierDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        MagnifierDrawable(int color, float density) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * density);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float r = s * 0.33f;
            float cx = b.left + s * 0.42f, cy = b.top + s * 0.42f;
            c.drawCircle(cx, cy, r, p);
            float d = (float) (r / Math.sqrt(2));
            c.drawLine(cx + d, cy + d, b.left + s * 0.92f, b.top + s * 0.92f, p);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
