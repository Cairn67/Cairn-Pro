// The iOS-style switch control, drawn by hand.
package com.cairn.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.CompoundButton;

import com.cairn.manager.redesign.JellySpring;

/**
 * iOS 26 style glass switch.
 *
 * Geometry, colours and springs are taken from liquid_design 0.4.3
 * {@code lib/src/liquid_glass_controls.dart} (class {@code LiquidGlassSwitch}), MIT,
 * Copyright (c) 2026 Mohammed Al-Jaf. Upstream is Flutter/Dart, so this is a
 * translation of its layout and motion, not a dependency. Spring integration lives in
 * {@code redesign/JellySpring.kt} (from {@code lib/src/glass_spring.dart}).
 *
 * <p>Upstream values, all in dp:
 * <ul>
 *   <li>track 64x28, thumb 38x24, travel = 64-38-4 = 22</li>
 *   <li>thumb centre x = 2 + 38/2 + pos*travel, y = 28/2</li>
 *   <li>active colour 0xFF34C759; inactive 0x5C787880 (dark) / 0x29787880 (light)</li>
 *   <li>thumb shadows 0x26000000 (blur 8, dy 2) and 0x0F000000 (blur 1, dy 1)</li>
 *   <li>springs: move 380/24, follow 4000/120, lift 500/30</li>
 *   <li>lifted thumb w*(1.1+0.4*l)*(1+stretch), h*(1.1+0.5*l)</li>
 *   <li>stretch clamp(|velocity|*0.02, 0, 0.3)</li>
 *   <li>drag overshoot rubber band, limit 0.18</li>
 * </ul>
 *
 * <p>The thumb keeps the Cairn Pro AGSL refraction: it samples the track that was just
 * rendered, so the lens bends the real pixels behind it. Below API 33 it degrades to a
 * scaled BitmapShader sample.
 */
final class CairnSwitch extends CompoundButton {

    private static final float TRACK_W = 64f, TRACK_H = 28f;
    private static final float THUMB_W = 38f, THUMB_H = 24f;
    private static final float TRAVEL = TRACK_W - THUMB_W - 4f;   
    private static final float INSET = 2f;
    
    private static final float MOVE_NUDGE_DP = 3f;
    
    private static final float VIEW_W = 80f, VIEW_H = 44f;

    private static final int ACTIVE = 0xFF34C759;
    private static final int INACTIVE_DARK = 0x5C787880;
    private static final int INACTIVE_LIGHT = 0x29787880;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float progress;
    private final JellySpring move = new JellySpring(380, 24);
    private final JellySpring lift = new JellySpring(500, 30);

    private float downX, downY, startPos, dragTarget;
    private boolean dragging, holding, cancelled, moved, rtl;
    private boolean running;
    private long lastFrameMs;

    private Bitmap glassSource;
    private Canvas glassCanvas;
    private Shader lensShader;

    private final Runnable hold = () -> {
        holding = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            running = false;
            if (step()) schedule();
            invalidate();
        }
    };

    CairnSwitch(Context c) {
        super(c);
        setButtonDrawable(null);
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setClickable(true);
        setFocusable(true);
        setContentDescription(Language.text(getContext(), "ROOT authorization", "ROOT 授权"));
        rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        progress = isChecked() ? 1f : 0f;
        move.snap(progress);
        lift.snap(0f);
    }

    float dp(float n) { return n * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) dp(VIEW_W), w), resolveSize((int) dp(VIEW_H), h));
    }

    private boolean step() {
        long now = SystemClock.uptimeMillis();
        double dt = lastFrameMs == 0L ? JellySpring.MAX_STEP
                : Math.min((now - lastFrameMs) / 1000.0, JellySpring.MAX_STEP);
        lastFrameMs = now;
        boolean moving = move.advance(dt);
        moving = lift.advance(dt) || moving;
        progress = move.position();
        return moving;
    }

    private void start() {
        lastFrameMs = 0L;
        schedule();
    }

    private void schedule() {
        if (running) return;
        running = true;
        postOnAnimation(tick);
    }

    private static float rubber(float raw) {
        if (raw >= 0f && raw <= 1f) return raw;
        float over = raw < 0f ? -raw : raw - 1f;
        final float limit = 0.18f;
        float band = (1f - 1f / (over * 0.55f / limit + 1f)) * limit;
        return raw < 0f ? -band : 1f + band;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float visualPosition() {
        return clamp(progress, -0.2f, 1.2f);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                startPos = progress;
                dragTarget = clamp(startPos, 0f, 1f);
                dragging = holding = cancelled = moved = false;
                setPressed(true);
                lift.moveTo(1f);
                start();
                postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (!dragging && !holding && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    
                    cancelled = true;
                    removeCallbacks(hold);
                    setPressed(false);
                    lift.moveTo(0f);
                    move.moveTo(isChecked() ? 1f : 0f);
                    start();
                    return true;
                }
                if (!cancelled && (dragging || (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)))) {
                    dragging = true;
                    removeCallbacks(hold);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    float travel = rtl ? -dp(TRAVEL) : dp(TRAVEL);
                    float raw = startPos + dx / travel;
                    dragTarget = clamp(raw, 0f, 1f);
                    
                    move.moveTo(UiMotion.enabled() ? rubber(raw) : dragTarget);
                    start();
                } else if (!cancelled && Math.abs(dx) > dp(MOVE_NUDGE_DP)) {
                    
                    moved = true;
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                removeCallbacks(hold);
                lift.moveTo(0f);
                if (cancelled) {
                    move.moveTo(isChecked() ? 1f : 0f);
                } else if (dragging || moved) {
                    
                    commit(dragTarget > 0.5f);
                } else if (!holding) {
                    boolean before = isChecked();
                    performClick();
                    if (isChecked() != before) move.moveTo(isChecked() ? 1f : 0f);
                } else {
                    move.moveTo(isChecked() ? 1f : 0f);
                }
                finishGesture();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                lift.moveTo(0f);
                move.moveTo(isChecked() ? 1f : 0f);
                finishGesture();
                return true;

            default:
                return true;
        }
    }

    private void finishGesture() {
        setPressed(false);
        holding = dragging = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        start();
    }

    private void commit(boolean value) {
        move.moveTo(value ? 1f : 0f);
        if (value != isChecked()) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            setChecked(value);
        }
        start();
    }

    @Override public void setChecked(boolean value) {
        super.setChecked(value);
        
        if (move == null) return;
        
        if (!dragging) { move.moveTo(value ? 1f : 0f); start(); }
    }

    public void setCheckedNow(boolean value) {
        super.setChecked(value);
        if (move == null) return;
        progress = value ? 1f : 0f;
        move.snap(progress);
        lift.snap(0f);
        invalidate();
    }

    @Override public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if (!pressed) { lift.moveTo(0f); start(); }
    }

    private void prepareGlass() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (glassSource != null && glassSource.getWidth() == w && glassSource.getHeight() == h) return;
        glassSource = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        glassCanvas = new Canvas(glassSource);
        if (Build.VERSION.SDK_INT >= 33) {
            
            android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(
                    "uniform shader source;"
                            + "uniform float2 center;"
                            + "uniform float2 radius;"
                            + "uniform float bend;"
                            + "half4 main(float2 p){"
                            + "  float2 d=(p-center)/radius;"
                            + "  float q=clamp(length(d),0.0,1.0);"
                            + "  float k=1.0+bend*q*q*q;"
                            + "  half4 col=source.eval(center+(p-center)*k);"
                            + "  float rim=smoothstep(0.72,1.0,q);"
                            + "  return half4(mix(col.rgb,half3(0.96,1.0,0.99),0.08+rim*0.24),0.94);"
                            + "}");
            shader.setInputShader("source",
                    new BitmapShader(glassSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            lensShader = shader;
        } else {
            lensShader = new BitmapShader(glassSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    @Override protected void onDraw(Canvas c) {
        prepareGlass();
        if (glassCanvas == null) return;

        final float trackLeft = (getWidth() - dp(TRACK_W)) / 2f;
        final float trackTop = (getHeight() - dp(TRACK_H)) / 2f;
        final float trackRight = trackLeft + dp(TRACK_W);
        final float trackBottom = trackTop + dp(TRACK_H);
        final float trackRadius = dp(TRACK_H) / 2f;

        boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
        float p = clamp(progress, 0f, 1f);
        int trackColor = blend(dark ? INACTIVE_DARK : INACTIVE_LIGHT, ACTIVE, p);

        glassCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        trackPaint.setShader(null);
        trackPaint.setColor(trackColor);
        glassCanvas.drawRoundRect(new RectF(trackLeft, trackTop, trackRight, trackBottom),
                trackRadius, trackRadius, trackPaint);
        
        trackPaint.setShader(new LinearGradient(trackLeft, trackTop, trackLeft, trackBottom,
                new int[]{0x22000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        glassCanvas.drawRoundRect(new RectF(trackLeft, trackTop, trackRight, trackBottom),
                trackRadius, trackRadius, trackPaint);
        trackPaint.setShader(null);

        paint.setShader(null);
        paint.setAlpha(isEnabled() ? 255 : 110);
        c.drawBitmap(glassSource, 0f, 0f, paint);
        paint.setAlpha(255);

        float visual = rtl ? 1f - visualPosition() : visualPosition();
        float cx = trackLeft + dp(INSET + THUMB_W / 2f + TRAVEL * visual);
        float cy = trackTop + dp(TRACK_H / 2f);

        float l = clamp(lift.position(), 0f, 1f);
        float stretch = UiMotion.enabled()
                ? clamp(Math.abs(move.speed()) * 0.02f, 0f, 0.3f) : 0f;
        float tw = dp(THUMB_W) * (1.1f + 0.4f * l) * (1f + stretch);
        float th = dp(THUMB_H) * (1.1f + 0.5f * l);
        float tr = th / 2f;
        RectF thumb = new RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f);

        drawShadow(c, thumb, tr, l);

        final int enabledAlpha = isEnabled() ? 255 : 110;
        if (l < 0.99f) {
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (enabledAlpha * (1f - l)));
            c.drawRoundRect(thumb, tr, tr, paint);
            paint.setAlpha(255);
        }
        if (l > 0.01f) {
            if (lensShader instanceof android.graphics.RuntimeShader) {
                android.graphics.RuntimeShader sh = (android.graphics.RuntimeShader) lensShader;
                sh.setFloatUniform("center", cx, cy);
                sh.setFloatUniform("radius", tw / 2f, th / 2f);
                sh.setFloatUniform("bend", 0.25f + 0.45f * l + stretch * 0.6f);
                paint.setShader(lensShader);
            } else {
                Matrix m = new Matrix();
                m.setScale(1.16f, 1.16f, cx, cy);
                lensShader.setLocalMatrix(m);
                paint.setShader(lensShader);
            }
            paint.setAlpha((int) (enabledAlpha * l));
            c.drawRoundRect(thumb, tr, tr, paint);
            paint.setShader(null);
            paint.setAlpha(255);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        RectF rim = new RectF(thumb.left + dp(0.4f), thumb.top + dp(0.4f),
                thumb.right - dp(0.4f), thumb.bottom - dp(0.4f));
        if (l > 0.01f) {
            paint.setShader(new LinearGradient(thumb.left, thumb.top, thumb.right, thumb.bottom,
                    new int[]{0xF2FFFFFF, 0x3389BCB4, 0x8CFFFFFF}, null, Shader.TileMode.CLAMP));
            paint.setAlpha((int) (255 * l));
            c.drawRoundRect(rim, tr, tr, paint);
            paint.setShader(null);
        } else {
            paint.setColor(0x14000000);
            paint.setAlpha(255);
            c.drawRoundRect(rim, tr, tr, paint);
        }
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawShadow(Canvas c, RectF thumb, float radius, float lift) {
        
        float alpha = 0.15f + 0.05f * lift;
        int passes = 4;
        for (int i = passes; i >= 1; i--) {
            float grow = dp(1.2f) * i;
            shadowPaint.setColor(Color.BLACK);
            shadowPaint.setAlpha((int) (255f * alpha / (passes * 1.6f)));
            RectF r = new RectF(thumb.left - grow, thumb.top - grow + dp(2f),
                    thumb.right + grow, thumb.bottom + grow + dp(2f));
            float rr = radius + grow;
            c.drawRoundRect(r, rr, rr, shadowPaint);
        }
        
        shadowPaint.setAlpha((int) (255f * 0.06f));
        RectF contact = new RectF(thumb.left, thumb.top + dp(1f), thumb.right, thumb.bottom + dp(1f));
        c.drawRoundRect(contact, radius, radius, shadowPaint);
    }

    private static int blend(int from, int to, float t) {
        float r = Color.red(from) + (Color.red(to) - Color.red(from)) * t;
        float g = Color.green(from) + (Color.green(to) - Color.green(from)) * t;
        float b = Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t;
        float a = Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t;
        return Color.argb((int) a, (int) r, (int) g, (int) b);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        
        com.cairn.manager.redesign.JellyPress.openOverdraw(this);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(hold);
        glassSource = null;
        glassCanvas = null;
        lensShader = null;
        move.snap(isChecked() ? 1f : 0f);
        lift.snap(0f);
        holding = dragging = cancelled = false;
        super.onDetachedFromWindow();
    }

    @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }
}
