// Reusable glass-styled controls.
package com.cairn.manager.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class GlassComponents {

    private GlassComponents() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GlassCard card(Context ctx, float radius, int tint, boolean spread) {
        
        GlassCard p = new GlassCard(ctx);
        p.setCornerRadius(radius);
        p.setTint(tint == 0 ? 0x14FFFFFF : tint);
        return p;
    }

    public static GlassCard button(Context ctx, String label, float radius,
                                   int tint, int textColor) {
        GlassCard p = new GlassCard(ctx);
        p.setCornerRadius(radius);
        p.setTint(tint);
        p.setClickable(true);
        p.setFocusable(true);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(15f);
        tv.setIncludeFontPadding(false);
        tv.setPadding(dp(ctx,12),0,dp(ctx,12),0);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        
        tv.setClickable(false);
        p.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        p.setMinimumHeight(dp(ctx, 52));
        
        com.cairn.manager.redesign.RedesignPress.install(p);
        return p;
    }

    public static GlassCard statusCard(Context ctx, String title, String value,
                                        int dotColor, float radius) {
        GlassCard p = card(ctx, radius, 0, false);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16));

        View dot = new View(ctx);
        int d = dp(ctx, 10);
        dot.setBackground(GlowDot.create(dotColor, d));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(d, d);
        dotLp.rightMargin = dp(ctx, 14);
        row.addView(dot, dotLp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextSize(13f);
        t1.setTextColor(Color.parseColor("#B3C8D2E0"));
        col.addView(t1);

        TextView t2 = new TextView(ctx);
        t2.setText(value);
        t2.setTextSize(17f);
        t2.setTextColor(Color.parseColor("#FFF2F5FA"));
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t2);

        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        p.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    public static GlassCard floatingButton(Context ctx, String glyph, int size) {
        GlassCard p = new GlassCard(ctx);
        int s = dp(ctx, size);
        
        p.setCornerRadius(size / 2f);
        p.setTint(Color.parseColor("#22FFFFFF"));
        p.setRefractionHeight(10f);
        p.setRefractionAmount(14f);
        p.setChromaticAberration(1f);
        p.setDepthEffect(true);
        p.setClickable(true);

        TextView tv = new TextView(ctx);
        tv.setText(glyph);
        tv.setTextSize(22f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        p.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(ctx, 20);
        lp.bottomMargin = dp(ctx, 96);
        p.setLayoutParams(lp);
        return p;
    }

    public static class GlowDot extends View {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

        private GlowDot(Context c, int color) {
            super(c);
            core.setColor(color);
            
            int a = Color.alpha(color);
            glow.setColor(Color.argb(a / 3, Color.red(color),
                    Color.green(color), Color.blue(color)));
        }

        public static android.graphics.drawable.Drawable create(int color, int sizePx) {
            
            return new DotDrawable(color, sizePx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            canvas.drawCircle(cx, cy, r, glow);
            canvas.drawCircle(cx, cy, r * 0.5f, core);
        }
    }

    private static class DotDrawable extends android.graphics.drawable.Drawable {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        DotDrawable(int color, int sizePx) {
            this.size = sizePx;
            core.setColor(color);
            int a = Color.alpha(color);
            glow.setColor(Color.argb(a / 3, Color.red(color),
                    Color.green(color), Color.blue(color)));
        }

        @Override
        public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            float cx = b.centerX();
            float cy = b.centerY();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(cx, cy, r, glow);
            canvas.drawCircle(cx, cy, r * 0.5f, core);
        }

        @Override
        public void setAlpha(int alpha) {
            core.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            core.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
