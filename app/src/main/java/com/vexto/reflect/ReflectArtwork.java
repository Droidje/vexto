package com.vexto.reflect;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Local Figma exports, rendered at their original document sizes. */
final class ReflectArtwork {
    final Typeface display;
    final Typeface medium;
    final Typeface regular;
    final Typeface semibold;
    final Typeface latinSemibold;
    final Typeface duration;

    private final Map<String, Bitmap> photos = new HashMap<>();
    private final Map<String, Icon> icons = new HashMap<>();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    ReflectArtwork(AssetManager assets) {
        display = Typeface.createFromAsset(assets, "fonts/murs_gothic.otf");
        medium = cyrillicFallback(assets, 500);
        regular = cyrillicFallback(assets, 400);
        semibold = cyrillicFallback(assets, 600);
        latinSemibold = dmSans(assets, 600);
        duration = dmSans(assets, 400);
        try {
            for (String file : assets.list("reflect")) {
                String name = file.substring(0, file.lastIndexOf('.'));
                String path = "reflect/" + file;
                if (file.endsWith(".svg")) {
                    SVG svg = SVG.getFromAsset(assets, path);
                    float width = svg.getDocumentWidth();
                    float height = svg.getDocumentHeight();
                    Bitmap image = Bitmap.createBitmap(
                            (int) Math.ceil(width * 4), (int) Math.ceil(height * 4),
                            Bitmap.Config.ARGB_8888);
                    Canvas raster = new Canvas(image);
                    raster.scale(image.getWidth() / width, image.getHeight() / height);
                    svg.renderToCanvas(raster, new RectF(0, 0, width, height));
                    icons.put(name, new Icon(image, width, height));
                } else {
                    try (InputStream in = assets.open(path)) {
                        Bitmap bitmap = BitmapFactory.decodeStream(in);
                        if (bitmap == null) throw new IOException("Could not decode " + path);
                        photos.put(name, bitmap);
                    }
                }
            }
        } catch (IOException | SVGParseException error) {
            throw new IllegalStateException("Could not load Reflect artwork", error);
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
        canvas.drawBitmap(photos.get(name), null, new RectF(x, y, x + width, y + height), bitmapPaint);
    }

    void icon(Canvas canvas, String name, float left, float top) {
        Icon icon = icons.get(name);
        canvas.drawBitmap(icon.bitmap, null, new RectF(left, top, left + icon.width, top + icon.height), bitmapPaint);
    }

    void centeredIcon(Canvas canvas, String name, float x, float y) {
        Icon icon = icons.get(name);
        icon(canvas, name, x - icon.width / 2, y - icon.height / 2);
    }

    private static final class Icon {
        final Bitmap bitmap;
        final float width;
        final float height;

        Icon(Bitmap bitmap, float width, float height) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
        }
    }
}
