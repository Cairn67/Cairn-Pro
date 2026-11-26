// Status indicator that morphs between state shapes.
package com.cairn.manager;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.view.View;

final class StatusMark extends View {
    enum Glyph { CHECKING, CHECK, NOTICE, UNAVAILABLE }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ink = new Path();
    private final Path revealed = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final ArgbEvaluator colors = new ArgbEvaluator();
    private Glyph glyph;
    private int color = Color.WHITE;
    private int targetColor = Color.WHITE;
    private float progress = 1f;
    private ValueAnimator animator;
    
    StatusMark(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStrokeWidth(1.9f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void show(Glyph next, int tint) {
        if (glyph == next && targetColor == tint) return;
        if (animator != null) { animator.cancel(); animator = null; }
        boolean animate = glyph != null && isLaidOut() && isShown()
                && getWindowVisibility() == VISIBLE && UiMotion.enabled();
        glyph = next;
        targetColor = tint;
        ink.reset();
        switch (next) {
            case CHECK:
                ink.moveTo(7f, 12f); ink.lineTo(10.3f, 15.4f); ink.lineTo(17.6f, 8f);
                break;
            case NOTICE:
                ink.moveTo(12f, 6.8f); ink.lineTo(12f, 12.6f);
                break;
            case UNAVAILABLE:
                ink.moveTo(8f, 8f); ink.lineTo(16f, 16f);
                ink.moveTo(16f, 8f); ink.lineTo(8f, 16f);
                break;
            case CHECKING:
                break;
        }
        if (!animate) {
            color = tint;
            progress = 1f;
            invalidate();
            return;
        }
        int startColor = color;
        progress = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(310L);
        animator.setInterpolator(UiMotion.SETTLE);
        animator.addUpdateListener(a -> {
            progress = a.getAnimatedFraction();
            color = (Integer) colors.evaluate(progress, startColor, tint);
            invalidate();
        });
        animator.start();
        UiMotion.settleMark(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = Math.min(getWidth(), getHeight()) / 44f;
        if (unit <= 0f || glyph == null) return;
        int save = canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(unit, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((color & 0x00ffffff) | 0x26000000);
        canvas.drawRoundRect(-22f, -22f, 22f, 22f, 15f, 15f, paint);
        paint.setColor(color);
        canvas.translate(-12f, -12f);
        if (glyph == Glyph.CHECKING) {
            canvas.drawCircle(7f, 12f, 1.2f, paint);
            canvas.drawCircle(12f, 12f, 1.2f, paint);
            canvas.drawCircle(17f, 12f, 1.2f, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            if (progress == 1f) {
                canvas.drawPath(ink, paint);
            } else {
                revealed.rewind();
                measure.setPath(ink, false);
                do {
                    measure.getSegment(0f, measure.getLength() * progress, revealed, true);
                } while (measure.nextContour());
                canvas.drawPath(revealed, paint);
            }
            if (glyph == Glyph.NOTICE && progress > .72f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(Math.round(255f * Math.min(1f, (progress - .72f) / .28f)));
                canvas.drawCircle(12f, 16.4f, 1.05f, paint);
            }
        }
        canvas.restoreToCount(save);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) { animator.cancel(); animator = null; }
        progress = 1f;
        color = targetColor;
        UiMotion.reset(this);
        super.onDetachedFromWindow();
    }
}
