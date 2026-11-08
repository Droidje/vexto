package com.vexto.reflect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;

/** Uses the supplied sparkle SVG as a mask for the gradient. */
final class ReflectSummaryIcon extends View {
    private static final long CYCLE_MS = 2_600;
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final float scale;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unavailablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix gradientMatrix = new Matrix();
    private final LinearGradient gradient = new LinearGradient(-15, -15, 15, 15,
            new int[]{0xff6deaff, 0xff88a5ff, 0xffd58bff, 0xffffbbd9}, null, Shader.TileMode.CLAMP);

    ReflectSummaryIcon(Context context, ReflectArtwork artwork, ReflectPreferences preferences, float scale) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        this.scale = scale;
        paint.setShader(gradient);
        var mask = new PorterDuffXfermode(PorterDuff.Mode.SRC_IN);
        paint.setXfermode(mask);
        unavailablePaint.setXfermode(mask);
        unavailablePaint.setColor(0xff888888);
    }

    void setPanelOpen(boolean open) {
        setSelected(open);
        setContentDescription(!isEnabled() ? "ИИ-резюме доступно после расшифровки"
                : open ? "Закрыть ИИ-резюме" : "Открыть ИИ-резюме");
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean animated = isEnabled() && preferences.motionEnabled();
        int outer = canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        int layer = canvas.saveLayer(-13, -13, 13, 13, null);
        artwork.centeredIcon(canvas, "message-2-sparkle", 0, 0);
        if (isEnabled()) {
            gradientMatrix.setRotate(animated ? (SystemClock.uptimeMillis() % CYCLE_MS) * 360f / CYCLE_MS : 0);
            gradient.setLocalMatrix(gradientMatrix);
        }
        canvas.drawRect(-13, -13, 13, 13, isEnabled() ? paint : unavailablePaint);
        canvas.restoreToCount(layer);
        canvas.restoreToCount(outer);
        if (animated && canvas.isHardwareAccelerated() && isShown() && getWindowVisibility() == VISIBLE) {
            postInvalidateDelayed(32);
        }
    }

    @Override public CharSequence getAccessibilityClassName() {
        return Button.class.getName();
    }
}
