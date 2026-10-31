package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Landscape tiles for apps that ship no banner: the app's icon on a plate tinted from that icon.
 * Rendered once per app version and cached, so a new install gets a banner the first time Home
 * shows it and later reloads only decode the file.
 */
final class Banners {
    private static final String TAG = "TvHome.Banners";
    private static final int WIDTH = 480;
    private static final int HEIGHT = 270;
    private static final int ICON_PX = 256;
    /** Icon height over the banner height: the same share the icon card gave the mark. */
    private static final float ICON_SHARE = 0.48f;
    private static final int MAX_FILES = 160;
    /** Bump when the rendered look changes: older files are pruned instead of shown. */
    private static final int VERSION = 5;

    private Banners() { }

    static Drawable generated(Context ctx, PackageManager pm, String pkg, Drawable icon) {
        if (icon == null) return null;
        long updated = 0;
        try {
            updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        File dir = new File(ctx.getCacheDir(), "banners");
        File file = new File(dir, pkg + "-" + updated + "-v" + VERSION + ".png");
        if (file.isFile()) {
            Bitmap cached = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (cached != null) return new BitmapDrawable(ctx.getResources(), cached);
        }
        Bitmap banner = render(icon);
        if (banner == null) return null;
        save(dir, pkg, file, banner);
        return new BitmapDrawable(ctx.getResources(), banner);
    }

    private static Bitmap render(Drawable icon) {
        Bitmap mark;
        try {
            mark = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, ICON_PX, ICON_PX);
            icon.draw(new Canvas(mark));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not draw an app icon", e);
            return null;
        }
        int side = Math.round(HEIGHT * ICON_SHARE);
        int left = (WIDTH - side) / 2;
        int top = (HEIGHT - side) / 2;
        int background = edgeColor(mark);
        Bitmap banner = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(banner);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // One flat plate colour: the dominant edge colour of the icon, so the artwork simply
        // continues into the tile instead of sitting on a gradient.
        paint.setColor(background != 0 ? background : 0xFF15202B);
        canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
        // Square artwork runs edge to edge; draw it through a rounded rect so the tile carries the
        // same corner shape as the rest of Home instead of a hard rectangular block.
        BitmapShader shader = new BitmapShader(mark, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setRectToRect(new RectF(0, 0, ICON_PX, ICON_PX),
                new RectF(left, top, left + side, top + side), Matrix.ScaleToFit.FILL);
        shader.setLocalMatrix(matrix);
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        iconPaint.setShader(shader);
        float corner = side * 0.22f;
        canvas.drawRoundRect(left, top, left + side, top + side, corner, corner, iconPaint);
        return banner;
    }

    /** The dominant colour along the icon's four edges; 0 when the edges are mostly transparent. */
    private static int edgeColor(Bitmap icon) {
        int[] pixels = new int[ICON_PX * ICON_PX];
        icon.getPixels(pixels, 0, ICON_PX, 0, 0, ICON_PX, ICON_PX);
        int minX = ICON_PX, minY = ICON_PX, maxX = -1, maxY = -1;
        for (int y = 0; y < ICON_PX; y += 2) {
            for (int x = 0; x < ICON_PX; x += 2) {
                if (Color.alpha(pixels[y * ICON_PX + x]) < 40) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) return 0;
        int band = Math.max(2, Math.min(maxX - minX + 1, maxY - minY + 1) / 16);
        int[] counts = new int[4096];
        long[] red = new long[4096], green = new long[4096], blue = new long[4096];
        int[] tally = new int[3];
        for (int y = minY; y <= maxY; y++) {
            boolean edgeRow = y < minY + band || y > maxY - band;
            for (int x = minX; x <= maxX; x++) {
                boolean edge = edgeRow || x < minX + band || x > maxX - band;
                if (!edge) {
                    if (!edgeRow) x = maxX - band;   // jump over the middle of the icon
                    continue;
                }
                int c = pixels[y * ICON_PX + x];
                tally[0]++;
                if (Color.alpha(c) < 40) continue;
                tally[1]++;
                int key = ((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4) | (Color.blue(c) >> 4);
                counts[key]++;
                red[key] += Color.red(c);
                green[key] += Color.green(c);
                blue[key] += Color.blue(c);
            }
        }
        if (tally[1] == 0 || tally[1] * 5 < tally[0] * 2) return 0;   // a bare mark / a circular logo
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        int n = counts[best];
        return Color.rgb((int) (red[best] / n), (int) (green[best] / n), (int) (blue[best] / n));
    }

    private static void save(File dir, String pkg, File file, Bitmap banner) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(dir, file.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            banner.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            out = null;
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not cache a generated banner", e);
            tmp.delete();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        prune(dir, pkg, file);
    }

    /** Drops the package's older versions, then the least recently used files over the cap. */
    private static void prune(File dir, String pkg, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        String prefix = pkg + "-";
        int count = files.length;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(prefix) && !f.equals(keep) && f.delete()) count--;
        }
        if (count <= MAX_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (count <= MAX_FILES) break;
            if (f.isFile() && f.delete()) count--;
        }
    }
}
