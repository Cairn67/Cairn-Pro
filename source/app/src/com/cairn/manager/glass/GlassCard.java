// Rounded card surface with the glass treatment.
package com.cairn.manager.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class GlassCard extends FrameLayout {

    private float radiusPx;
    
    private int fillColor = 0x1AFFFFFF;
    
    private int strokeColor = 0x16FFFFFF;
    
    private float highlightAlpha = 0.045f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public GlassCard(Context c) {
        super(c);
        init();
    }

    public GlassCard(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        radiusPx = dp(20);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, dp(1) * 0.75f));
        hiPaint.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public GlassCard setCornerRadius(float dpValue) {
        radiusPx = dp(dpValue);
        invalidate();
        return this;
    }

    public GlassCard setTint(int color) {
        fillColor = color;
        invalidate();
        return this;
    }

    public GlassCard setStrokeColor(int color) {
        strokeColor = color;
        invalidate();
        return this;
    }

    public GlassCard setHighlightAlpha(float a) {
        highlightAlpha = Math.max(0f, Math.min(1f, a));
        invalidate();
        return this;
    }

    public GlassCard setRefractionHeight(float v) { return this; }
    public GlassCard setRefractionAmount(float v) { return this; }
    public GlassCard setChromaticAberration(float v) { return this; }
    public GlassCard setDepthEffect(boolean v) { return this; }
    public GlassCard setBlurRadius(float v) { return this; }
    public GlassCard setHighlightColor(int c) { return this; }
    public GlassCard setHighlightAngle(float r) { return this; }
    public GlassCard setBackgroundSource(Object s) { return this; }
    public GlassCard setContentSource(android.view.View v) { return this; }
    public int getGlassTier() { return 1; }

    @Override public void setPressed(boolean pressed) {
        
        super.setPressed(pressed);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rect.set(0, 0, w, h);
        path.reset();
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);
        
        hiPaint.setShader(new LinearGradient(
                0, 0, 0, h * 0.22f,
                new int[]{argb(highlightAlpha), 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
    }

    private static int argb(float a) {
        int al = (int) (Math.max(0f, Math.min(1f, a)) * 255f);
        return (al << 24) | 0x00FFFFFF;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        
        fillPaint.setColor(fillColor);
        canvas.drawPath(path, fillPaint);

        if (highlightAlpha > 0.001f) {
            int save = canvas.save();
            canvas.clipPath(path);
            canvas.drawRect(0, 0, getWidth(), getHeight() * 0.22f, hiPaint);
            canvas.restoreToCount(save);
        }

        float half = strokePaint.getStrokeWidth() / 2f;
        rect.set(half, half, getWidth() - half, getHeight() - half);
        Path sp = new Path();
        sp.addRoundRect(rect, radiusPx - half, radiusPx - half, Path.Direction.CW);
        strokePaint.setColor(strokeColor);
        canvas.drawPath(sp, strokePaint);

        super.onDraw(canvas);
    }
}
