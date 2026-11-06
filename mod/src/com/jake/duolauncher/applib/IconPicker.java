package com.jake.duolauncher.applib;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dialogs for choosing an icon pack and for changing a single app's icon. */
final class IconPicker {

    /** Called after icons changed; {@code pkg} is the only package affected, or null for all. */
    interface OnChanged {
        void changed(String pkg);
    }

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private IconPicker() {
    }

    private static AlertDialog.Builder builder(Context ctx, boolean dark) {
        return new AlertDialog.Builder(ctx, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static void noPacks(Context ctx, boolean dark) {
        builder(ctx, dark).setTitle("No icon packs found")
                .setMessage("Install an icon pack that supports Nova, ADW or Apex launchers, "
                        + "then come back here.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------ whole launcher

    static void choosePack(final Context ctx, final boolean dark, final OnChanged onChanged) {
        final List<IconPack.Info> packs = IconPack.installed(ctx);
        if (packs.isEmpty()) {
            noPacks(ctx, dark);
            return;
        }
        String current = AppIcons.currentPack(ctx);
        final CharSequence[] items = new CharSequence[packs.size() + 1];
        items[0] = "System icons (default)";
        int checked = 0;
        for (int i = 0; i < packs.size(); i++) {
            items[i + 1] = packs.get(i).label;
            if (packs.get(i).pkg.equals(current)) checked = i + 1;
        }
        final boolean themeAll = AppIcons.themeUnsupported(ctx);
        builder(ctx, dark).setTitle("Icon pack")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    String pkg = which == 0 ? "" : packs.get(which - 1).pkg;
                    AppIcons.setPack(ctx, pkg);
                    Toast.makeText(ctx, which == 0 ? "Restoring system icons…"
                            : "Applying " + items[which] + "…", Toast.LENGTH_SHORT).show();
                    onChanged.changed(null);
                })
                .setNeutralButton(themeAll ? "Unsupported icons: styled" : "Unsupported icons: original",
                        (d, w) -> {
                            AppIcons.setThemeUnsupported(ctx, !themeAll);
                            Toast.makeText(ctx, !themeAll
                                    ? "Icons missing from the pack are now styled to match it."
                                    : "Icons missing from the pack now keep their original look.",
                                    Toast.LENGTH_LONG).show();
                            onChanged.changed(null);
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ one app

    static void changeAppIcon(final Context ctx, final boolean dark, final ComponentName cn,
            final String label, final OnChanged onChanged) {
        final List<IconPack.Info> packs = IconPack.installed(ctx);
        final List<CharSequence> items = new ArrayList<>();
        for (IconPack.Info p : packs) items.add("Choose from " + p.label + "…");
        items.add("Use the app's original icon");
        items.add("Reset (follow icon pack)");
        builder(ctx, dark).setTitle("Icon for “" + label + "”")
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which < packs.size()) {
                        iconGrid(ctx, dark, packs.get(which), cn, label, onChanged);
                    } else {
                        AppIcons.setOverride(ctx, cn, which == packs.size() ? "orig" : null);
                        onChanged.changed(cn.getPackageName());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void iconGrid(final Context ctx, final boolean dark, final IconPack.Info info,
            final ComponentName cn, final String label, final OnChanged onChanged) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), 0);
        final EditText search = new EditText(ctx);
        search.setSingleLine(true);
        search.setHint("Search icons");
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar progress = new ProgressBar(ctx);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 64)));
        final GridView grid = new GridView(ctx);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(ctx, 72));
        grid.setVerticalSpacing(dp(ctx, 6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 420)));

        final AlertDialog dialog = builder(ctx, dark).setTitle(info.label)
                .setView(root)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        LOADER.execute(() -> {
            final IconPack pack = AppIcons.packFor(ctx, info.pkg);
            final List<String> names = new ArrayList<>(pack != null ? pack.allDrawables() : new ArrayList<>());
            String suggested = pack != null ? pack.drawableFor(cn) : null;
            if (suggested != null) {
                names.remove(suggested);
                names.add(0, suggested);
            }
            MAIN.post(() -> {
                progress.setVisibility(View.GONE);
                if (pack == null || names.isEmpty()) {
                    Toast.makeText(ctx, "This icon pack has no icons to choose from.", Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    return;
                }
                final Adapter adapter = new Adapter(ctx, pack, names);
                grid.setAdapter(adapter);
                grid.setOnItemClickListener((parent, view, position, id) -> {
                    String name = adapter.getItem(position);
                    AppIcons.setOverride(ctx, cn, pack.pkg + "/" + name);
                    onChanged.changed(cn.getPackageName());
                    dialog.dismiss();
                });
                search.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(Editable s) {
                        adapter.filter(s.toString());
                    }
                });
                String guess = label.toLowerCase(Locale.ROOT);
                if (suggested == null && !guess.isEmpty()) {
                    search.setText(guess.split(" ")[0]);
                    search.setSelection(search.getText().length());
                }
            });
        });
    }

    private static final class Adapter extends BaseAdapter {
        private final Context ctx;
        private final IconPack pack;
        private final List<String> all;
        private List<String> shown;
        private final LruCache<String, Bitmap> cache = new LruCache<>(240);
        private final int px;

        Adapter(Context ctx, IconPack pack, List<String> names) {
            this.ctx = ctx;
            this.pack = pack;
            this.all = names;
            this.shown = names;
            this.px = dp(ctx, 52);
        }

        void filter(String q) {
            String n = q.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (n.isEmpty()) {
                shown = all;
            } else {
                List<String> out = new ArrayList<>();
                for (String s : all) if (s.toLowerCase(Locale.ROOT).contains(n)) out.add(s);
                shown = out;
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public String getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout cell;
            ImageView iv;
            TextView tv;
            if (convert instanceof LinearLayout) {
                cell = (LinearLayout) convert;
                iv = (ImageView) cell.getChildAt(0);
                tv = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));
                iv = new ImageView(ctx);
                cell.addView(iv, new LinearLayout.LayoutParams(px, px));
                tv = new TextView(ctx);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String name = shown.get(position);
            tv.setText(IconPack.prettyName(name));
            iv.setTag(name);
            Bitmap b = cache.get(name);
            iv.setImageBitmap(b);
            if (b == null) {
                final ImageView target = iv;
                LOADER.execute(() -> {
                    Drawable d = pack.drawable(name);
                    final Bitmap bmp = d != null ? AppIcons.render(d, px) : null;
                    MAIN.post(() -> {
                        if (bmp != null) cache.put(name, bmp);
                        if (name.equals(target.getTag())) target.setImageBitmap(bmp);
                    });
                });
            }
            return cell;
        }
    }
}
