package com.vexto.reflect;

import android.content.ComponentCallbacks2;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Decodes local artwork only when it is drawn and keeps bitmap memory bounded. */
final class ReflectArtwork {
    private static final int PHOTO_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int ICON_CACHE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PHOTO_PIXELS = PHOTO_CACHE_BYTES / 8;

    final Typeface display;
    final Typeface medium;
    final Typeface regular;
    final Typeface semibold;
    final Typeface latinSemibold;
    final Typeface duration;

    private final AssetManager assets;
    private final Map<String, Source> sources = new HashMap<>();
    private final LruCache<String, Photo> photos = new LruCache<>(PHOTO_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Photo value) { return value.bitmap.getAllocationByteCount(); }
    };
    private final LruCache<String, Icon> icons = new LruCache<>(ICON_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Icon value) { return value.bitmap.getAllocationByteCount(); }
    };
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private float scale = 1;
    private int iconScale = 1;

    ReflectArtwork(AssetManager assets) {
        this.assets = assets;
        display = Typeface.createFromAsset(assets, "fonts/murs_gothic.otf");
        medium = cyrillicFallback(assets, 500);
        regular = cyrillicFallback(assets, 400);
        semibold = cyrillicFallback(assets, 600);
        latinSemibold = dmSans(assets, 600);
        duration = dmSans(assets, 400);
        try {
            for (String file : assets.list("reflect")) {
                int dot = file.lastIndexOf('.');
                if (dot > 0) sources.put(file.substring(0, dot), new Source("reflect/" + file));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not index Reflect artwork", error);
        }
    }

    void setScale(float scale) {
        if (scale <= 0) return;
        this.scale = scale;
        iconScale = Math.min(4, (int) Math.ceil(scale));
    }

    @SuppressWarnings("deprecation")
    void trimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            photos.evictAll();
            icons.evictAll();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            photos.trimToSize(PHOTO_CACHE_BYTES / 2);
            icons.trimToSize(ICON_CACHE_BYTES / 2);
        }
    }

    // Figma renders the Cyrillic glyphs missing in DM Sans with Noto.
    private static Typeface cyrillicFallback(AssetManager assets, int weight) {
        return new Typeface.Builder(assets, "fonts/noto_sans.ttf")
                .setFontVariationSettings("'wdth' 100, 'wght' " + weight)
                .setWeight(weight)
                .build();
    }

    private static Typeface dmSans(AssetManager assets, int weight) {
        return new Typeface.Builder(assets, "fonts/dm_sans.ttf")
                .setFontVariationSettings("'opsz' 14, 'wght' " + weight)
                .setWeight(weight)
                .build();
    }

    void image(Canvas canvas, String name, float x, float y, float width, float height) {
        drawBitmap(canvas, photo(name, width * scale, height * scale), x, y, width, height);
    }

    void recordingOrb(Canvas canvas, float[] vertices) {
        Bitmap bitmap = photo("orb_active", RecordingOrb.WIDTH * scale, RecordingOrb.HEIGHT * scale);
        if (vertices == null) {
            drawBitmap(canvas, bitmap, RecordingOrb.LEFT, RecordingOrb.TOP,
                    RecordingOrb.WIDTH, RecordingOrb.HEIGHT);
        } else {
            canvas.drawBitmapMesh(bitmap, RecordingOrb.GRID, RecordingOrb.GRID, vertices, 0, null, 0, bitmapPaint);
        }
    }

    void imagePixels(Canvas canvas, String name, float x, float y, float width, float height) {
        drawBitmap(canvas, photo(name, width, height), x, y, width, height);
    }

    void icon(Canvas canvas, String name, float left, float top) {
        Icon icon = loadIcon(name);
        drawBitmap(canvas, icon.bitmap, left, top, icon.width, icon.height);
    }

    void centeredIcon(Canvas canvas, String name, float x, float y) {
        Icon icon = loadIcon(name);
        drawBitmap(canvas, icon.bitmap, x - icon.width / 2, y - icon.height / 2, icon.width, icon.height);
    }

    private void drawBitmap(Canvas canvas, Bitmap bitmap, float x, float y, float width, float height) {
        destination.set(x, y, x + width, y + height);
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
    }

    private Bitmap photo(String name, float width, float height) {
        Source source = source(name);
        try {
            if (source.width == 0) readDimensions(source);
            double requestedScale = Math.max(width / source.width, height / source.height);
            // Leave room for a second large image during a screen transition.
            double pixelBudgetScale = Math.sqrt(MAX_PHOTO_PIXELS / ((double) source.width * source.height));
            double ratio = Math.min(1, Math.min(requestedScale, pixelBudgetScale));
            int sourceSpan = Math.max(source.width, source.height);
            int targetSpan = Math.max(1, (int) Math.ceil(sourceSpan * ratio));
            Photo cached = photos.get(name);
            if (cached != null && cached.targetSpan == targetSpan) return cached.bitmap;

            int sample = 1;
            while (sourceSpan / (sample * 2) >= targetSpan) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            // Sample first, then shrink the remaining difference while decoding.
            int sampledSpan = sourceSpan / sample;
            if (sampledSpan > targetSpan) {
                options.inDensity = sampledSpan;
                options.inTargetDensity = targetSpan;
                options.inScaled = true;
            }
            try (InputStream input = assets.open(source.path)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                if (bitmap == null) throw new IOException("Could not decode " + source.path);
                photos.put(name, new Photo(bitmap, targetSpan));
                return bitmap;
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not load Reflect artwork: " + name, error);
        }
    }

    private void readDimensions(Source source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = assets.open(source.path)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image " + source.path);
        source.width = bounds.outWidth;
        source.height = bounds.outHeight;
    }

    private Icon loadIcon(String name) {
        Icon cached = icons.get(name);
        if (cached != null && cached.scale == iconScale) return cached;
        try {
            SVG svg = SVG.getFromAsset(assets, source(name).path);
            float width = svg.getDocumentWidth();
            float height = svg.getDocumentHeight();
            Bitmap bitmap = Bitmap.createBitmap((int) Math.ceil(width * iconScale),
                    (int) Math.ceil(height * iconScale), Bitmap.Config.ARGB_8888);
            Canvas raster = new Canvas(bitmap);
            raster.scale(bitmap.getWidth() / width, bitmap.getHeight() / height);
            svg.renderToCanvas(raster, new RectF(0, 0, width, height));
            Icon icon = new Icon(bitmap, width, height, iconScale);
            icons.put(name, icon);
            return icon;
        } catch (IOException | SVGParseException error) {
            throw new IllegalStateException("Could not load Reflect icon: " + name, error);
        }
    }

    private Source source(String name) {
        Source source = sources.get(name);
        if (source == null) throw new IllegalArgumentException("Unknown artwork: " + name);
        return source;
    }

    private static final class Source {
        final String path;
        int width;
        int height;

        Source(String path) { this.path = path; }
    }

    private static final class Photo {
        final Bitmap bitmap;
        final int targetSpan;

        Photo(Bitmap bitmap, int targetSpan) {
            this.bitmap = bitmap;
            this.targetSpan = targetSpan;
        }
    }

    private static final class Icon {
        final Bitmap bitmap;
        final float width;
        final float height;
        final int scale;

        Icon(Bitmap bitmap, float width, float height, int scale) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.scale = scale;
        }
    }
}
