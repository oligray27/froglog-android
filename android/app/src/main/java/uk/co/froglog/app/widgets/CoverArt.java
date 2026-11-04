package uk.co.froglog.app.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Game cover art for the widgets. A widget can't load an image from a URL, so covers are downloaded
 * to the cache folder as small rounded 3:4 pictures, and the widgets draw from there. Downloads
 * happen with the widget's other network work (ensure), never while drawing, which only reads what
 * is already saved (cached).
 */
final class CoverArt {
    private static final String TAG = "FrogLogWidgets";
    // Big enough for the largest cover (the cover screen's, about 132dp wide on a dense screen), small enough to be light in a RemoteViews.
    private static final int WIDTH = 300;
    private static final int HEIGHT = 400;
    // The cover screen draws the picture 176dp tall, so 24px of 400 is about 10dp, close to the control buttons' 8dp corners.
    private static final int RADIUS = 24;
    private static final int KEEP = 12;
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private CoverArt() {}

    private static File file(Context context, String url) throws Exception {
        File dir = new File(context.getCacheDir(), "widget_covers");
        if (!dir.exists()) dir.mkdirs();
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) name.append(String.format("%02x", b));
        return new File(dir, name + "_" + WIDTH + "r" + RADIUS + ".png");
    }

    // The saved cover for this url, or null if it hasn't been downloaded.
    static Bitmap cached(Context context, String url) {
        if (url == null) return null;
        try {
            File saved = file(context, url);
            return saved.exists() ? BitmapFactory.decodeFile(saved.getPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Downloads the cover if it isn't saved yet. Runs on the widgets' worker thread, and reports nothing: a missing cover just means the logo shows.
    static void ensure(Context context, String url) {
        if (url == null || !url.startsWith("https://")) return;
        try {
            File saved = file(context, url);
            if (saved.exists()) {
                saved.setLastModified(System.currentTimeMillis());
                return;
            }
            // A slow CDN is common (SteamGridDB especially), so one more try before giving up until the next refresh.
            Bitmap cover;
            try {
                cover = download(url);
            } catch (java.io.IOException slow) {
                Log.w(TAG, "cover: retrying after " + slow);
                cover = download(url);
            }
            if (cover == null) {
                Log.w(TAG, "cover: could not read the picture from " + url);
                return;
            }
            try (FileOutputStream out = new FileOutputStream(saved)) {
                cover.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Log.i(TAG, "cover: saved " + url);
            prune(saved.getParentFile());
        } catch (Exception e) {
            Log.w(TAG, "cover: " + e);
        }
    }

    private static Bitmap download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_BYTES) return null;
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.min(bounds.outWidth / WIDTH, bounds.outHeight / HEIGHT));
            Bitmap source = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            return source == null ? null : crop(source);
        } finally {
            connection.disconnect();
        }
    }

    // Fills a 3:4 rounded picture from the middle of the original, so a landscape image is cropped rather than squashed.
    private static Bitmap crop(Bitmap source) {
        Bitmap out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float scale = Math.max(WIDTH / (float) source.getWidth(), HEIGHT / (float) source.getHeight());
        float drawnWidth = source.getWidth() * scale;
        float drawnHeight = source.getHeight() * scale;
        RectF target = new RectF((WIDTH - drawnWidth) / 2, (HEIGHT - drawnHeight) / 2, (WIDTH + drawnWidth) / 2, (HEIGHT + drawnHeight) / 2);
        // The rounded shape is drawn first and the picture is drawn only where that shape already is.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawRoundRect(new RectF(0, 0, WIDTH, HEIGHT), RADIUS, RADIUS, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, null, target, paint);
        return out;
    }

    // Keeps the folder to the most recent few covers.
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - KEEP; i++) files[i].delete();
    }
}
