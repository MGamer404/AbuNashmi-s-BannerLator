package com.winlator.star.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.IntRange;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class ImageUtils {
    /**
     * Longest side, in pixels, a game's cover or tile icon is kept at. Tiles are drawn far smaller;
     * full-size store art (1440x2160 Xbox posters) overflowed the GPU image cache and made the Games
     * screen and side menu stutter.
     */
    public static final int COVER_ART_MAX = 1200;

    /** {@code bitmap} scaled down so its longer side is at most {@link #COVER_ART_MAX}; unchanged when it fits. */
    public static Bitmap fitCoverArt(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= COVER_ART_MAX) return bitmap;
        float scale = (float) COVER_ART_MAX / longest;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true);
    }

    /** A cover or icon file decoded with its longer side at most {@link #COVER_ART_MAX}; null when it cannot be read. */
    public static Bitmap decodeCoverArt(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / sample > COVER_ART_MAX) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int maxSize) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        int reqWidth = width >= height ? maxSize : 0;
        int reqHeight = height >= width ? maxSize : 0;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static Bitmap getBitmapFromUri(Context context, Uri uri, BitmapFactory.Options options) {
        InputStream is = null;
        Bitmap bitmap = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (options != null) {
                bitmap = BitmapFactory.decodeStream(is, null, options);
            }
            else bitmap = BitmapFactory.decodeStream(is);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            try {
                if (is != null) is.close();
            }
            catch (IOException e) {}
        }
        return bitmap;
    }

    public static Bitmap getBitmapFromUri(Context context, Uri uri) {
        return getBitmapFromUri(context, uri, null);
    }

    public static Bitmap getBitmapFromUri(Context context, Uri uri, int maxSize) {
        InputStream is = null;
        BitmapFactory.Options options = new BitmapFactory.Options();

        try {
            is = context.getContentResolver().openInputStream(uri);
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);
            int inSampleSize = calculateInSampleSize(options, maxSize);
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            try {
                if (is != null) is.close();
            }
            catch (IOException e) {}
        }

        return getBitmapFromUri(context, uri, options);
    }

    public static boolean save(Bitmap bitmap, File output, Bitmap.CompressFormat compressFormat, @IntRange(from = 0, to = 100) int quality) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(output);
            return bitmap.compress(compressFormat, quality, fos);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            try {
                if (fos != null) {
                    fos.flush();
                    fos.close();
                }
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }
}
