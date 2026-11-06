package com.jake.duolauncher.applib;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Invisible helper that lets the user pick a photo and applies it as the launcher
 * background, using the same file, preferences and live-update hook as Duo
 * Launcher's own "Wallpaper & appearance" screen.
 */
public final class WallpaperActivity extends Activity {

    private static final int PICK = 41;
    static WeakReference<com.jake.duolauncher.MainActivity> launcher = new WeakReference<>(null);

    static void start(Context ctx) {
        Intent i = new Intent(ctx, WallpaperActivity.class);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
            pick.setType("image/*");
        } else {
            pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(pick, PICK);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("image/*");
                startActivityForResult(fallback, PICK);
            } catch (Exception e2) {
                Toast.makeText(this, "The photo picker is unavailable.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK) return;
        final Uri uri = data != null ? data.getData() : null;
        if (resultCode != RESULT_OK || uri == null) {
            finish();
            return;
        }
        final Context app = getApplicationContext();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int maxSide = Math.min(3000, Math.max(dm.widthPixels, dm.heightPixels) * 3 / 2);
        Toast.makeText(this, "Applying wallpaper…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            Bitmap bitmap = null;
            String id = null;
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(app.getContentResolver(), uri);
                bitmap = ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float s = Math.min(1f, maxSide / (float) Math.max(w, h));
                    if (s < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                });
                id = "applib-" + UUID.randomUUID();
                File target = new File(app.getFilesDir(), "launcher-background.jpg");
                File tmp = new File(app.getFilesDir(), "applib-wallpaper-" + id + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        throw new IllegalStateException("That photo could not be saved.");
                    }
                    out.getFD().sync();
                }
                try {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                app.getSharedPreferences("launcher_background", MODE_PRIVATE).edit()
                        .putBoolean("photoEnabled", true).putString("photoId", id).commit();
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : "That photo could not be used.";
            }
            final Bitmap result = bitmap;
            final String photoId = id;
            final String err = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (err == null) {
                    publish(result, photoId, true);
                    Toast.makeText(app, "Wallpaper updated.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(app, err, Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }).start();
    }

    /** Restores the launcher's built-in "Duo dunes" background. Main thread only. */
    static void resetToDefault(Context ctx) {
        Context app = ctx.getApplicationContext();
        new File(app.getFilesDir(), "launcher-background.jpg").delete();
        app.getSharedPreferences("launcher_background", MODE_PRIVATE).edit()
                .putBoolean("photoEnabled", false).remove("photoId").apply();
        publish(null, null, false);
        Toast.makeText(app, "Using Duo dunes.", Toast.LENGTH_SHORT).show();
    }

    /** Pushes the new background into the running launcher (G1.N2 + Y2 state). */
    private static void publish(Bitmap bitmap, String id, boolean photo) {
        try {
            G1.N2.a(bitmap, id);
        } catch (Throwable ignored) {
        }
        com.jake.duolauncher.MainActivity act = launcher.get();
        if (act != null) {
            try {
                G1.Y2 backgrounds = act.l();
                backgrounds.f.setValue(Boolean.valueOf(photo));
            } catch (Throwable ignored) {
            }
        }
    }
}
