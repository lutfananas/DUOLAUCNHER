package com.jake.duolauncher.applib;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.io.File;

/** Frosted-glass helpers: a blurred copy of the launcher background plus glass panels. */
final class Glass {

    private Glass() {
    }

    // ------------------------------------------------------------ blurred background

    private static Bitmap cached;
    private static long cachedStamp = -1;

    /**
     * Returns a small, blurred copy of the photo the user picked as launcher
     * background (files/launcher-background.jpg), or null when no photo is set.
     */
    static synchronized Bitmap blurredBackground(Context ctx) {
        File f = new File(ctx.getFilesDir(), "launcher-background.jpg");
        long stamp = f.exists() ? f.lastModified() ^ f.length() : 0;
        if (stamp == cachedStamp) return cached;
        cachedStamp = stamp;
        cached = null;
        if (stamp == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 240 && o.outHeight / (sample * 2) >= 240) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) return null;
            // Scale to ~180px on the short side, then blur strongly.
            float s = 180f / Math.min(src.getWidth(), src.getHeight());
            int w = Math.max(1, Math.round(src.getWidth() * s));
            int h = Math.max(1, Math.round(src.getHeight() * s));
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            if (small != src) src.recycle();
            Bitmap mutable = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
            boxBlur(mutable, 7, 3);
            cached = mutable;
        } catch (Throwable t) {
            cached = null;
        }
        return cached;
    }

    /** Separable box blur, repeated {@code passes} times (≈ gaussian). */
    static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blurPass(a, b, w, h, radius, true);
            blurPass(b, a, w, h, radius, false);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int ra = 0, rr = 0, rg = 0, rb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(line, clamp(i, len), w, horizontal)];
                ra += c >>> 24; rr += (c >> 16) & 0xff; rg += (c >> 8) & 0xff; rb += c & 0xff;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] =
                        ((ra / div) << 24) | ((rr / div) << 16) | ((rg / div) << 8) | (rb / div);
                int add = in[idx(line, clamp(i + r + 1, len), w, horizontal)];
                int rem = in[idx(line, clamp(i - r, len), w, horizontal)];
                ra += (add >>> 24) - (rem >>> 24);
                rr += ((add >> 16) & 0xff) - ((rem >> 16) & 0xff);
                rg += ((add >> 8) & 0xff) - ((rem >> 8) & 0xff);
                rb += (add & 0xff) - (rem & 0xff);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** Paint that draws the blurred background with a little extra saturation (iOS "vibrancy"). */
    static Paint backdropPaint() {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.35f);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        return p;
    }

    /** Matrix that maps the background bitmap onto a window of the given size (center-crop). */
    static Matrix centerCrop(Bitmap bmp, int windowW, int windowH, Matrix out) {
        float scale = Math.max(windowW / (float) bmp.getWidth(), windowH / (float) bmp.getHeight());
        float dx = (windowW - bmp.getWidth() * scale) / 2f;
        float dy = (windowH - bmp.getHeight() * scale) / 2f;
        out.reset();
        out.setScale(scale, scale);
        out.postTranslate(dx, dy);
        return out;
    }

    // ------------------------------------------------------------ glass panel

    /** Draws a frosted panel: translucent fill, soft top highlight and a hairline border. */
    static final class Panel {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean dark;
        private float lastH = -1;

        Panel(boolean dark, float density) {
            this.dark = dark;
            fill.setColor(dark ? 0x4D2C2C30 : 0x40FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, 0.8f * density));
            stroke.setColor(dark ? 0x26FFFFFF : 0x4DFFFFFF);
        }

        void draw(Canvas c, RectF r, float radius) {
            if (r.height() != lastH) {
                lastH = r.height();
                shine.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f,
                        dark ? 0x1FFFFFFF : 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(r, radius, radius, fill);
            c.drawRoundRect(r, radius, radius, shine);
            float inset = stroke.getStrokeWidth() / 2f;
            c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset,
                    radius, radius, stroke);
        }
    }

    /** Drawable version of {@link Panel} for the search field and list sections. */
    static final class PanelDrawable extends Drawable {
        private final Panel panel;
        private final float radius;
        private final RectF rect = new RectF();

        PanelDrawable(boolean dark, float density, float radius) {
            this.panel = new Panel(dark, density);
            this.radius = radius;
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            rect.set(b);
            panel.draw(c, rect, radius);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
