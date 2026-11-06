package com.jake.duolauncher.applib;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An iPhone-style "App Library" page: a search field on top, then a two-column
 * grid of folders (Recently Added, Social, ... plus the user's own folders). Each
 * folder shows three large icons that launch directly plus a cluster of small
 * icons that opens the whole folder. Searching shows an alphabetical list.
 *
 * Edit mode (Edit button, or long-press on empty space) lets the user create,
 * rename and delete folders, move apps between folders and change the wallpaper.
 */
public final class AppLibraryView extends FrameLayout {

    static final String RECENTLY_ADDED = "Recently Added";
    static final String OTHER = "Other";
    static final String[] AUTO_NAMES = {
            "Social", "Entertainment", "Creativity", "Productivity & Finance", "Utilities",
            "Games", "Travel", "Shopping & Food", "Health & Fitness", "Information & Reading",
    };

    static final int KIND_RECENT = 0, KIND_CUSTOM = 1, KIND_AUTO = 2, KIND_OTHER = 3;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final boolean dark;
    private final SharedPreferences prefs;
    private final LibraryModel model;

    private final LinearLayout content;
    private final LinearLayout searchRow;
    private final LinearLayout editBar;
    private final EditText search;
    private final TextView editButton;
    private final TextView cancel;
    private final ScrollView gridScroll;
    private final LinearLayout grid;
    private final ScrollView listScroll;
    private final LinearLayout list;
    private final FrameLayout overlay;

    private List<?> sourceApps;
    private List<Entry> entries = new ArrayList<>();
    private List<Category> categories = new ArrayList<>();
    private Map<String, String> folderOf = new HashMap<>();
    private Map<String, String> autoOf = new HashMap<>();
    private int generation;
    private boolean searching;
    private boolean editing;
    private boolean overlayOpen;
    private String overlayName;
    private final List<ValueAnimator> jiggles = new ArrayList<>();

    // Frosted backdrop: the launcher's background photo, blurred, pinned to the window.
    private Bitmap backdrop;
    private final Paint backdropPaint = Glass.backdropPaint();
    private final Paint tintPaint = new Paint();
    private final Matrix backdropMatrix = new Matrix();
    private final int[] windowPos = new int[2];
    private int drawnX = Integer.MIN_VALUE, drawnY = Integer.MIN_VALUE;
    private final ViewTreeObserver.OnPreDrawListener tracker = () -> {
        getLocationInWindow(windowPos);
        if (windowPos[0] != drawnX || windowPos[1] != drawnY) invalidate();
        return true;
    };

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
        String id() {
            return app.a != null ? app.a : pkg;
        }
        String title() {
            return app.h ? label + "  ·  Work" : label;
        }
    }

    static final class Category {
        final String name;
        final List<Entry> apps;
        final int kind;
        Category(String name, List<Entry> apps, int kind) {
            this.name = name;
            this.apps = apps;
            this.kind = kind;
        }
        boolean deletable() {
            return kind != KIND_OTHER;
        }
        boolean renamable() {
            return kind == KIND_CUSTOM || kind == KIND_AUTO;
        }
        boolean acceptsApps() {
            return kind != KIND_RECENT;
        }
    }

    public AppLibraryView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        prefs = context.getSharedPreferences("duo_app_library", Context.MODE_PRIVATE);
        model = new LibraryModel(prefs);
        Activity act = activity();
        if (act instanceof com.jake.duolauncher.MainActivity) {
            WallpaperActivity.launcher = new java.lang.ref.WeakReference<>((com.jake.duolauncher.MainActivity) act);
        }
        setClipChildren(false);
        setWillNotDraw(false);
        tintPaint.setColor(dark ? 0x40000000 : 0x14000000);
        setFocusable(true);
        setFocusableInTouchMode(true);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- Search row: [search field] [Edit | Cancel]
        searchRow = new LinearLayout(context);
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
        search.setBackground(new Glass.PanelDrawable(dark, density, dp(12)));
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(42), 1f));

        editButton = textButton("Edit");
        editButton.setOnClickListener(v -> setEditing(true));
        searchRow.addView(editButton, wrap());

        cancel = textButton("Cancel");
        cancel.setVisibility(GONE);
        cancel.setOnClickListener(v -> exitSearch());
        searchRow.addView(cancel, wrap());
        content.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Edit bar: [+ Folder] [Wallpaper] [Reset] ... [Done]
        editBar = new LinearLayout(context);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        editBar.setGravity(Gravity.CENTER_VERTICAL);
        editBar.setPadding(dp(4), dp(6), dp(4), dp(14));
        editBar.setVisibility(GONE);
        android.widget.HorizontalScrollView pillScroll = new android.widget.HorizontalScrollView(context);
        pillScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pills = new LinearLayout(context);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        View newFolder = pill("＋ Folder", false);
        newFolder.setOnClickListener(v -> newFolderDialog(null));
        pills.addView(newFolder, pillParams());
        View wallpaper = pill("Wallpaper", false);
        wallpaper.setOnClickListener(v -> wallpaperDialog());
        pills.addView(wallpaper, pillParams());
        View icons = pill("Icons", false);
        icons.setOnClickListener(v -> iconPackDialog());
        pills.addView(icons, pillParams());
        View reset = pill("Reset", false);
        reset.setOnClickListener(v -> resetDialog());
        pills.addView(reset, pillParams());
        pillScroll.addView(pills, new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        editBar.addView(pillScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View done = pill("Done", true);
        done.setOnClickListener(v -> setEditing(false));
        editBar.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        content.addView(editBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Folder grid
        gridScroll = new ScrollView(context);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        gridScroll.setClipToPadding(false);
        gridScroll.setPadding(0, 0, 0, dp(24));
        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(4), dp(4), dp(4), dp(40));
        grid.setOnLongClickListener(v -> {
            pageMenu(v);
            return true;
        });
        gridScroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(gridScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Alphabetical search list
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

        // ---- Expanded folder overlay
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

    private float bottomInsetDp = -1;

    /** Page insets: the backdrop fills the whole page, content stays clear of the dock. */
    void setBottomInset(float dockDp) {
        if (dockDp == bottomInsetDp) return;
        bottomInsetDp = dockDp;
        int bottom = dp(Math.max(0f, dockDp));
        content.setPadding(dp(12), dp(16), dp(12), bottom);
        overlay.setPadding(dp(4), dp(16), dp(4), bottom);
    }

    // ================================================================= data

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
        recompute(null);
    }

    /** Rebuilds the folders from the current app list and the user's model. */
    private void recompute(final Runnable then) {
        final int gen = ++generation;
        final List<Entry> all = entries;
        final PackageManager pm = getContext().getPackageManager();
        final List<String> custom;
        final Map<String, String> assign;
        final Set<String> hidden;
        synchronized (model) {
            custom = new ArrayList<>(model.custom);
            assign = new HashMap<>(model.assign);
            hidden = new HashSet<>(model.hidden);
        }
        WORKER.execute(() -> {
            final Map<String, String> folders = new HashMap<>();
            final Map<String, String> autos = new HashMap<>();
            final List<Category> built = buildCategories(pm, all, custom, assign, hidden, folders, autos);
            main.post(() -> {
                if (gen != generation) return;
                categories = built;
                folderOf = folders;
                autoOf = autos;
                rebuildGrid();
                if (overlayOpen && overlayName != null) {
                    Category c = find(overlayName);
                    if (c != null) renderOverlay(c, false);
                    else closeOverlay();
                }
                if (then != null) then.run();
            });
        });
    }

    private List<Category> buildCategories(PackageManager pm, List<Entry> all, List<String> custom,
            Map<String, String> assign, Set<String> hidden,
            Map<String, String> folderOut, Map<String, String> autoOut) {
        List<Category> out = new ArrayList<>();

        // Usage scores: most-used apps first inside a folder, like iOS.
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

        // Recently Added: newest user-installed apps (shown in addition to their folder).
        if (!hidden.contains(RECENTLY_ADDED)) {
            final Map<Entry, Long> installed = new HashMap<>();
            for (Entry e : all) {
                long[] info = Categories.installInfo(pm, e.pkg);
                if (info[1] == 0) installed.put(e, info[0]);
            }
            List<Entry> recent = new ArrayList<>(installed.keySet());
            Collections.sort(recent, (x, y) -> Long.compare(installed.get(y), installed.get(x)));
            if (recent.size() > 16) recent = new ArrayList<>(recent.subList(0, 16));
            if (!recent.isEmpty()) out.add(new Category(RECENTLY_ADDED, recent, KIND_RECENT));
        }

        Set<String> autoNames = new HashSet<>();
        Collections.addAll(autoNames, AUTO_NAMES);

        Map<String, List<Entry>> customGroups = new LinkedHashMap<>();
        for (String c : custom) customGroups.put(c, new ArrayList<>());
        Map<String, List<Entry>> autoGroups = new LinkedHashMap<>();
        List<Entry> other = new ArrayList<>();

        for (Entry e : all) {
            String auto = Categories.categoryName(Categories.categoryOf(pm, e.pkg), e.pkg, e.label);
            autoOut.put(e.id(), auto);
            String target = assign.get(e.id());
            if (target != null) {
                boolean valid = customGroups.containsKey(target) || OTHER.equals(target)
                        || (autoNames.contains(target) && !hidden.contains(target));
                if (!valid) target = null;
            }
            if (target == null) {
                target = (OTHER.equals(auto) || hidden.contains(auto)) ? OTHER : auto;
            }
            folderOut.put(e.id(), target);
            if (customGroups.containsKey(target)) {
                customGroups.get(target).add(e);
            } else if (OTHER.equals(target)) {
                other.add(e);
            } else {
                List<Entry> g = autoGroups.get(target);
                if (g == null) {
                    g = new ArrayList<>();
                    autoGroups.put(target, g);
                }
                g.add(e);
            }
        }

        for (Map.Entry<String, List<Entry>> g : customGroups.entrySet()) {
            out.add(new Category(g.getKey(), byUse(g.getValue(), scores), KIND_CUSTOM));
        }
        List<Map.Entry<String, List<Entry>>> sorted = new ArrayList<>(autoGroups.entrySet());
        Collections.sort(sorted, (x, y) -> {
            int sx = 0, sy = 0;
            for (Entry e : x.getValue()) if (scores.containsKey(e)) sx++;
            for (Entry e : y.getValue()) if (scores.containsKey(e)) sy++;
            if (sx != sy) return Integer.compare(sy, sx);
            int c = Integer.compare(y.getValue().size(), x.getValue().size());
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        for (Map.Entry<String, List<Entry>> g : sorted) {
            out.add(new Category(g.getKey(), byUse(g.getValue(), scores), KIND_AUTO));
        }
        if (!other.isEmpty()) out.add(new Category(OTHER, byUse(other, scores), KIND_OTHER));
        return out;
    }

    private static List<Entry> byUse(List<Entry> apps, final Map<Entry, Double> scores) {
        List<Entry> ordered = new ArrayList<>(apps);
        Collections.sort(ordered, (x, y) -> {
            Double a = scores.get(x), b = scores.get(y);
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return Double.compare(b, a);
        });
        return ordered;
    }

    private Category find(String name) {
        for (Category c : categories) if (c.name.equals(name)) return c;
        return null;
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

    // ================================================================= grid

    private void rebuildGrid() {
        stopJiggle();
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
        if (editing) startJiggle();
    }

    private View folder(final Category cat) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setTag("folder");
        TileView tile = new TileView(ctx, cat);
        box.addView(tile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = new TextView(ctx);
        label.setText(editing && cat.renamable() ? cat.name + " ✎" : cat.name);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(4 * density, 0, density, 0x80000000);
        label.setPadding(0, dp(6), 0, dp(2));
        label.setOnClickListener(v -> {
            if (editing && cat.renamable()) renameDialog(cat);
            else openCategory(cat);
        });
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** A folder tile: 3 large icons + a 2x2 cluster of small icons. */
    final class TileView extends View {
        private final Category cat;
        private final Glass.Panel glass = new Glass.Panel(dark, density);
        private final RectF bounds = new RectF();
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmp = new RectF();
        private final GestureDetector gestures;
        private int pressed = -1;

        TileView(Context ctx, Category cat) {
            super(ctx);
            this.cat = cat;
            badge.setColor(0xF0E5E5EA);
            badgeLine.setColor(0xFF1C1C1E);
            badgeLine.setStrokeWidth(2.2f * density);
            badgeLine.setStrokeCap(Paint.Cap.ROUND);
            plus.setColor(0x99FFFFFF);
            plus.setStrokeWidth(3f * density);
            plus.setStrokeCap(Paint.Cap.ROUND);
            setContentDescription(cat.name);
            gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) {
                    pressed = editing ? -1 : quadrant(e.getX(), e.getY());
                    invalidate();
                    return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    if (editing) {
                        if (cat.deletable() && inBadge(e.getX(), e.getY())) deleteDialog(cat);
                        else openCategory(cat);
                    } else {
                        tap(quadrant(e.getX(), e.getY()));
                    }
                    return true;
                }
                @Override public void onLongPress(MotionEvent e) {
                    pressed = -1;
                    invalidate();
                    if (editing) return;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    Entry target = single(quadrant(e.getX(), e.getY()));
                    if (target != null) showAppMenu(TileView.this, target);
                    else setEditing(true);
                }
            });
        }

        private boolean cluster() {
            return cat.apps.size() > 4;
        }

        /** The app a quadrant launches directly, or null if it opens the folder. */
        private Entry single(int q) {
            if (q < 0) return null;
            if (q == 3 && cluster()) return null;
            return q < cat.apps.size() ? cat.apps.get(q) : null;
        }

        private void tap(int q) {
            Entry e = single(q);
            if (e != null) launch(e, this, cell(q));
            else if (q == 3 || cat.apps.isEmpty() || q >= cat.apps.size()) openCategory(cat);
        }

        private boolean inBadge(float x, float y) {
            float r = getWidth() * 0.16f;
            return x < r && y < r;
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
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && pressed != -1) {
                pressed = -1;
                invalidate();
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
            bounds.set(0, 0, w, getHeight());
            glass.draw(c, bounds, radius);
            int n = cat.apps.size();
            if (n == 0) {
                float cx = w / 2f, cy = getHeight() / 2f, s = w * 0.12f;
                c.drawLine(cx - s, cy, cx + s, cy, plus);
                c.drawLine(cx, cy - s, cx, cy + s, plus);
            }
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
            if (editing && cat.deletable()) {
                float br = w * 0.075f, bc = w * 0.085f;
                c.drawCircle(bc, bc, br, badge);
                c.drawLine(bc - br * 0.5f, bc, bc + br * 0.5f, bc, badgeLine);
            }
        }

        private void drawIcon(Canvas c, Entry e, RectF r) {
            Bitmap b = e.icon();
            if (b == null || b.isRecycled()) return;
            c.drawBitmap(b, null, r, icon);
        }
    }

    private static void inset(RectF r, float d) {
        r.left += d;
        r.top += d;
        r.right -= d;
        r.bottom -= d;
    }

    // ================================================================= edit mode

    private void setEditing(boolean on) {
        if (on && searching) exitSearch();
        if (editing == on) return;
        editing = on;
        searchRow.setVisibility(on ? GONE : VISIBLE);
        editBar.setVisibility(on ? VISIBLE : GONE);
        if (on) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        rebuildGrid();
        if (overlayOpen && overlayName != null) {
            Category c = find(overlayName);
            if (c != null) renderOverlay(c, false);
        }
        requestFocus();
    }

    private void startJiggle() {
        int i = 0;
        for (int r = 0; r < grid.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) grid.getChildAt(r);
            for (int k = 0; k < row.getChildCount(); k++) {
                View box = row.getChildAt(k);
                if (!"folder".equals(box.getTag())) continue;
                float amp = (i++ % 2 == 0) ? 1.4f : -1.4f;
                ObjectAnimator a = ObjectAnimator.ofFloat(box, "rotation", -amp, amp);
                a.setDuration(130 + (i % 3) * 12);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.setStartDelay((i * 37) % 120);
                a.start();
                jiggles.add(a);
            }
        }
    }

    private void stopJiggle() {
        for (ValueAnimator a : jiggles) a.cancel();
        jiggles.clear();
        for (int r = 0; r < grid.getChildCount(); r++) {
            View row = grid.getChildAt(r);
            if (!(row instanceof ViewGroup)) continue;
            for (int k = 0; k < ((ViewGroup) row).getChildCount(); k++) {
                ((ViewGroup) row).getChildAt(k).setRotation(0f);
            }
        }
    }

    private void pageMenu(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor, Gravity.CENTER);
        menu.getMenu().add(0, 1, 0, editing ? "Done" : "Edit App Library");
        menu.getMenu().add(0, 2, 1, "New Folder");
        menu.getMenu().add(0, 3, 2, "Change Wallpaper");
        menu.getMenu().add(0, 5, 3, "Icon Pack");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: setEditing(!editing); return true;
                case 2: newFolderDialog(null); return true;
                case 3: wallpaperDialog(); return true;
                case 5: iconPackDialog(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(getContext(), dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private EditText nameField(String initial) {
        EditText field = new EditText(getContext());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setHint("Folder name");
        if (initial != null) {
            field.setText(initial);
            field.setSelection(initial.length());
        }
        return field;
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(getContext());
        f.setPadding(dp(22), dp(8), dp(22), 0);
        f.addView(v);
        return f;
    }

    private void showKeyboard(final EditText field) {
        field.requestFocus();
        field.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, 0);
        }, 200);
    }

    /** Creates a folder; if {@code moveApp} is given it is moved into the new folder. */
    private void newFolderDialog(final Entry moveApp) {
        final EditText field = nameField(null);
        AlertDialog d = dialog()
                .setTitle("New Folder")
                .setView(padded(field))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = field.getText().toString().trim();
            if (name.isEmpty()) {
                field.setError("Enter a name");
                return;
            }
            if (model.nameTaken(name)) {
                field.setError("A folder with this name already exists");
                return;
            }
            model.createFolder(name);
            if (moveApp != null) model.move(moveApp.id(), canonical(name));
            d.dismiss();
            recompute(() -> {
                Category c = find(canonical(name));
                if (c != null && moveApp == null) openCategory(c);
            });
        }));
        d.show();
        showKeyboard(field);
    }

    /** Returns the automatic-category spelling if {@code name} matches one. */
    private static String canonical(String name) {
        for (String a : AUTO_NAMES) if (a.equalsIgnoreCase(name)) return a;
        return name;
    }

    private void renameDialog(final Category cat) {
        final EditText field = nameField(cat.name);
        AlertDialog d = dialog()
                .setTitle("Rename Folder")
                .setView(padded(field))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            if (name.isEmpty()) {
                field.setError("Enter a name");
                return;
            }
            if (name.equals(cat.name)) {
                d.dismiss();
                return;
            }
            if (model.nameTaken(name) && !name.equalsIgnoreCase(cat.name)) {
                field.setError("A folder with this name already exists");
                return;
            }
            if (cat.kind == KIND_CUSTOM) {
                model.renameCustom(cat.name, name);
            } else {
                List<String> ids = new ArrayList<>();
                for (Entry e : cat.apps) ids.add(e.id());
                model.renameAuto(cat.name, name, ids);
            }
            if (cat.name.equals(overlayName)) overlayName = name;
            d.dismiss();
            recompute(null);
        }));
        d.show();
        showKeyboard(field);
    }

    private void deleteDialog(final Category cat) {
        String msg;
        if (cat.kind == KIND_RECENT) {
            msg = "The Recently Added folder will be hidden. Apps stay in their other folders.";
        } else if (cat.apps.isEmpty()) {
            msg = "This folder is empty.";
        } else {
            msg = cat.apps.size() + (cat.apps.size() == 1 ? " app" : " apps")
                    + " in this folder will move back to their automatic folder (or Other). "
                    + "No apps are uninstalled.";
        }
        dialog().setTitle("Delete “" + cat.name + "”?")
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    model.deleteFolder(cat.name, cat.kind == KIND_CUSTOM);
                    recompute(null);
                })
                .show();
    }

    private void resetDialog() {
        dialog().setTitle("Reset App Library?")
                .setMessage("Your folders and app placements will be removed, and deleted folders "
                        + "come back. Apps are sorted automatically again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> {
                    model.reset();
                    recompute(null);
                })
                .show();
    }

    /** Lets the user pick the folder an app lives in. */
    private void moveDialog(final Entry e) {
        final List<String> names = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        String current = folderOf.get(e.id());
        String auto = autoOf.get(e.id());
        for (Category c : categories) {
            if (!c.acceptsApps()) continue;
            names.add((c.name.equals(current) ? "✓  " : "     ") + c.name);
            targets.add(c.name);
        }
        if (auto != null && !names.isEmpty()) {
            boolean exists = false;
            for (String t : targets) if (t.equals(auto)) exists = true;
            if (!exists && !OTHER.equals(auto) && !model.hidden.contains(auto)) {
                names.add("     " + auto);
                targets.add(auto);
            }
        }
        names.add("     ＋ New Folder…");
        targets.add(null);
        dialog().setTitle("Move “" + e.label + "” to")
                .setItems(names.toArray(new CharSequence[0]), (d, which) -> {
                    String target = targets.get(which);
                    if (target == null) {
                        newFolderDialog(e);
                        return;
                    }
                    String auto2 = autoOf.get(e.id());
                    // Moving an app back to its automatic folder clears the override.
                    model.move(e.id(), target.equals(auto2) ? null : target);
                    recompute(null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Multi-select list of all apps to put into {@code cat}. */
    private void addAppsDialog(final Category cat) {
        final List<Entry> all = new ArrayList<>(entries);
        final CharSequence[] labels = new CharSequence[all.size()];
        final boolean[] checked = new boolean[all.size()];
        final boolean[] before = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            String where = folderOf.get(e.id());
            labels[i] = e.title() + (where != null && !where.equals(cat.name) ? "   (" + where + ")" : "");
            checked[i] = before[i] = cat.name.equals(where);
        }
        dialog().setTitle("Apps in “" + cat.name + "”")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    synchronized (model) {
                        for (int i = 0; i < all.size(); i++) {
                            if (checked[i] == before[i]) continue;
                            Entry e = all.get(i);
                            String auto = autoOf.get(e.id());
                            if (checked[i]) {
                                if (cat.name.equals(auto) && !model.hidden.contains(auto)) model.assign.remove(e.id());
                                else model.assign.put(e.id(), cat.name);
                            } else {
                                model.assign.remove(e.id());
                                boolean stillHere = cat.name.equals(auto) && !model.hidden.contains(auto);
                                if (stillHere || (OTHER.equals(cat.name) && (auto == null
                                        || OTHER.equals(auto) || model.hidden.contains(auto)))) {
                                    // It would land here again automatically: send it to Other instead.
                                    if (!OTHER.equals(cat.name)) model.assign.put(e.id(), OTHER);
                                }
                            }
                        }
                        model.save();
                    }
                    recompute(null);
                })
                .show();
    }

    private void iconPackDialog() {
        IconPicker.choosePack(getContext(), dark, this::iconsChanged);
    }

    /** Asks the launcher to rebuild app entries so the new icons show everywhere. */
    private void iconsChanged(String pkg) {
        Activity act = activity();
        if (act instanceof com.jake.duolauncher.MainActivity) {
            AppIcons.reload((com.jake.duolauncher.MainActivity) act, sourceApps, pkg);
        }
    }

    private void wallpaperDialog() {
        CharSequence[] items = {"Choose photo…", "Reset to Duo dunes"};
        dialog().setTitle("Wallpaper")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        WallpaperActivity.start(getContext());
                    } else {
                        WallpaperActivity.resetToDefault(getContext());
                        refreshBackdrop();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================================================= folder overlay

    private void openCategory(Category cat) {
        overlayName = cat.name;
        renderOverlay(cat, true);
    }

    private void renderOverlay(final Category cat, boolean animate) {
        Context ctx = getContext();
        overlay.removeAllViews();

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(28), dp(16), dp(16));

        TextView title = new TextView(ctx);
        title.setText(editing && cat.renamable() ? cat.name + "  ✎" : cat.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setShadowLayer(6 * density, 0, density, 0x66000000);
        title.setPadding(0, 0, 0, dp(editing ? 4 : 22));
        if (cat.renamable()) {
            title.setOnClickListener(v -> {
                if (editing) renameDialog(cat);
            });
        }
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (editing) {
            TextView hint = new TextView(ctx);
            hint.setText(cat.acceptsApps() ? "Tap an app to move it to another folder"
                    : "Tap an app to move it to a folder");
            hint.setTextColor(0xCCFFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, 0, 0, dp(18));
            panel.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        List<View> cells = new ArrayList<>();
        for (Entry e : cat.apps) cells.add(appCell(e));
        if (cat.acceptsApps() && (editing || cat.kind == KIND_CUSTOM || cat.apps.isEmpty())) {
            cells.add(addCell(cat));
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        final int cols = 4;
        for (int i = 0; i < cells.size(); i += cols) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(i + j < cells.size() ? cells.get(i + j) : new View(ctx), lp);
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

        overlay.addView(panel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        overlayOpen = true;
        overlay.setVisibility(VISIBLE);
        if (animate) {
            overlay.setAlpha(0f);
            panel.setScaleX(0.88f);
            panel.setScaleY(0.88f);
            overlay.animate().alpha(1f).setDuration(180).setListener(null).start();
            panel.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        } else {
            overlay.setAlpha(1f);
        }
        content.setRenderEffect(RenderEffect.createBlurEffect(dp(18), dp(18), Shader.TileMode.CLAMP));
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.requestFocus();
    }

    private ImageView iconView(Bitmap b) {
        ImageView iv = new ImageView(getContext());
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private TextView cellLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setShadowLayer(4 * density, 0, density, 0x80000000);
        tv.setPadding(dp(2), dp(5), dp(2), 0);
        return tv;
    }

    private View appCell(final Entry e) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView iv = iconView(e.icon());
        cell.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(58)));
        cell.addView(cellLabel(e.label), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(v -> {
            if (editing) moveDialog(e);
            else launch(e, iv, null);
        });
        cell.setOnLongClickListener(v -> {
            showAppMenu(iv, e);
            return true;
        });
        if (editing) {
            ObjectAnimator a = ObjectAnimator.ofFloat(iv, "rotation", -2f, 2f);
            a.setDuration(140);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.start();
        }
        return cell;
    }

    private View addCell(final Category cat) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        View plus = new View(getContext()) {
            final Glass.Panel panel = new Glass.Panel(dark, density);
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF r = new RectF();
            @Override protected void onDraw(Canvas c) {
                r.set(0, 0, getWidth(), getHeight());
                panel.draw(c, r, getWidth() * 0.24f);
                p.setColor(0xE6FFFFFF);
                p.setStrokeWidth(3f * density);
                p.setStrokeCap(Paint.Cap.ROUND);
                float cx = getWidth() / 2f, cy = getHeight() / 2f, s = getWidth() * 0.2f;
                c.drawLine(cx - s, cy, cx + s, cy, p);
                c.drawLine(cx, cy - s, cx, cy + s, p);
            }
        };
        cell.addView(plus, new LinearLayout.LayoutParams(dp(58), dp(58)));
        cell.addView(cellLabel("Add Apps"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(v -> addAppsDialog(cat));
        return cell;
    }

    private void closeOverlay() {
        if (!overlayOpen) return;
        overlayOpen = false;
        overlayName = null;
        content.setRenderEffect(null);
        overlay.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!overlayOpen) {
                    overlay.setVisibility(GONE);
                    overlay.removeAllViews();
                }
            }
        }).start();
        requestFocus();
    }

    // ================================================================= search

    private void enterSearch() {
        if (searching) return;
        if (editing) setEditing(false);
        searching = true;
        editButton.setVisibility(GONE);
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
        editButton.setVisibility(VISIBLE);
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
                section.setBackground(new Glass.PanelDrawable(dark, density, dp(14)));
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
        final ImageView iv = iconView(e.icon());
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = new TextView(ctx);
        tv.setText(e.title());
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

    // ================================================================= actions

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
        main.postDelayed(() -> {
            if (!overlayOpen && !editing) recompute(null);
        }, 1500);
    }

    private void showAppMenu(final View anchor, final Entry e) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 4, 1, "Move to Folder…");
        menu.getMenu().add(0, 5, 2, "Change Icon…");
        menu.getMenu().add(0, 2, 3, "App info");
        long[] info = Categories.installInfo(getContext().getPackageManager(), e.pkg);
        if (info[1] == 0) menu.getMenu().add(0, 3, 3, "Uninstall");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    launch(e, anchor, null);
                    return true;
                case 4:
                    moveDialog(e);
                    return true;
                case 5:
                    if (e.app.d != null) {
                        IconPicker.changeAppIcon(getContext(), dark, e.app.d, e.label, this::iconsChanged);
                    }
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
                    } catch (Exception ex) {
                        Toast.makeText(getContext(), "Can't uninstall this app.", Toast.LENGTH_SHORT).show();
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
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && (overlayOpen || searching || editing)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (overlayOpen) closeOverlay();
                else if (searching) exitSearch();
                else setEditing(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ================================================================= frosted backdrop

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(tracker);
        refreshBackdrop();
    }

    private void refreshBackdrop() {
        final Context ctx = getContext();
        WORKER.execute(() -> {
            final Bitmap b = Glass.blurredBackground(ctx);
            main.post(() -> {
                if (b != backdrop) {
                    backdrop = b;
                    invalidate();
                }
            });
        });
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) refreshBackdrop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) refreshBackdrop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        getLocationInWindow(windowPos);
        drawnX = windowPos[0];
        drawnY = windowPos[1];
        Bitmap b = backdrop;
        View root = getRootView();
        if (b != null && !b.isRecycled() && root != null && root.getWidth() > 0) {
            // Draw the blurred background as if it covered the whole window, so it
            // lines up with the real background while the page slides in.
            canvas.save();
            canvas.translate(-drawnX, -drawnY);
            Glass.centerCrop(b, root.getWidth(), root.getHeight(), backdropMatrix);
            canvas.drawBitmap(b, backdropMatrix, backdropPaint);
            canvas.restore();
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), tintPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(tracker);
        stopJiggle();
        if (overlayOpen) {
            overlayOpen = false;
            overlayName = null;
            content.setRenderEffect(null);
            overlay.setVisibility(GONE);
            overlay.removeAllViews();
        }
    }

    // ================================================================= helpers

    private int dp(float v) {
        return Math.round(v * density);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.rightMargin = dp(8);
        return lp;
    }

    private TextView textButton(String text) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setPadding(dp(14), dp(8), dp(4), dp(8));
        b.setShadowLayer(4 * density, 0, density, 0x66000000);
        return b;
    }

    private TextView pill(String text, boolean primary) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(primary ? 0xFF000000 : 0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        if (primary) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF2FFFFFF);
            bg.setCornerRadius(dp(19));
            b.setBackground(bg);
        } else {
            b.setBackground(new Glass.PanelDrawable(dark, density, dp(19)));
        }
        return b;
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
