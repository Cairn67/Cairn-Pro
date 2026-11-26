// Background layer that other glass surfaces refract.
package com.cairn.manager.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

public class GlassBackdrop extends FrameLayout {
    private static final int TOP = 0xFF172622;
    private static final int BASE = 0xFF0D1211;
    private final Paint paint = new Paint();
    private int baseColor = BASE;

    public GlassBackdrop(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
    }

    public GlassBackdrop setBaseColor(int color) {
        baseColor = color == 0 ? BASE : color;
        paint.setShader(gradient(getHeight(), baseColor));
        invalidate();
        return this;
    }

    private static Shader gradient(int height, int color) {
        return new LinearGradient(0, 0, 0, Math.max(1, height),
                new int[]{TOP, color, color, 0xFF25392E}, new float[]{0f, .47f, .76f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        paint.setShader(gradient(h, baseColor));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }

    public static void drawBackdrop(Canvas canvas, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        Paint paint = new Paint();
        paint.setShader(gradient(h, color == 0 ? BASE : color));
        canvas.drawRect(0, 0, w, h, paint);
    }

    public void draw(Canvas canvas, int w, int h) { drawBackdrop(canvas, w, h, baseColor); }
    public View asContentSource() { return this; }
}
