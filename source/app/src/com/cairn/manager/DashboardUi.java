// Shared view factory: columns, rows, cards, and text styles.
package com.cairn.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.cairn.manager.glass.GlassCard;
import com.cairn.manager.glass.GlassComponents;

final class DashboardUi {
    static final int BG = 0xFF101318;
    static final int SURFACE = 0xFF1A2029;
    static final int RAISED = 0xFF242C37;
    static final int LINE = 0xFF35404D;
    static final int INK = 0xFFF2F4F7;
    static final int MUTED = 0xFFAAB4C0;
    static final int ACCENT = 0xFFD5DCE5;
    static final int ON_ACCENT = 0xFF18202A;
    static final int DOCK_CLEARANCE = 100;
private final Context context;

    DashboardUi(Context context) { this.context = context; }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    int gutter() { return Math.max(20, (context.getResources().getConfiguration().screenWidthDp - 560) / 2); }

    boolean stack(float maxFontScale) {
        var config = context.getResources().getConfiguration();
        return config.screenWidthDp < 355 || config.fontScale > maxFontScale;
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    void append(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    void split(LinearLayout parent, View first, View second, boolean stack) {
        parent.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        parent.setGravity(Gravity.TOP);
        int buttonHeight = dp(stack ? 54 : 52);
        if (stack) {
            LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(-1, buttonHeight);
            parent.addView(first, firstParams);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-1, buttonHeight);
            secondParams.topMargin = dp(9);
            parent.addView(second, secondParams);
        } else {
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, buttonHeight, 1f);
            left.setMarginEnd(dp(10));
            parent.addView(first, left);
            parent.addView(second, new LinearLayout.LayoutParams(0, buttonHeight, 1f));
        }
    }

    ScrollView scroller(LinearLayout body) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(DOCK_CLEARANCE));
        body.setPadding(dp(gutter()), dp(20), dp(gutter()), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    TextView text(CharSequence copy, float size, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setText(copy);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    TextView heading(CharSequence copy, float size) {
        TextView view = text(copy, size, INK, true);
        view.setLetterSpacing(-.026f);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    LinearLayout header(String title, String subtitle) {
        LinearLayout header = column();
        header.addView(heading(title, 32));
        if (subtitle != null && !subtitle.isEmpty()) append(header, text(subtitle, 13, MUTED, false), 3);
        return header;
    }

    GradientDrawable surface(int radius, int base, int border) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(base);
        surface.setCornerRadius(dp(radius));
        if (border != 0) surface.setStroke(Math.max(1, dp(1)), border);
        return surface;
    }

    LinearLayout panel(int radius, int fill, int padding) {
        LinearLayout panel = column();
        panel.setBackground(surface(radius, fill, LINE));
        panel.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return panel;
    }

    void jellyOnHold(View target) {
        if (target == null) return;
        com.cairn.manager.redesign.RedesignPress.install(target);
    }

    void actionTile(View tile, int fill, Runnable action) {
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25BDF2D2),
                surface(20, fill, LINE), surface(20, 0xFFFFFFFF, 0)));
        tile.setFocusable(true);
        jellyOnHold(tile);
        tile.setOnClickListener(view -> action.run());
        tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    GlassCard button(String title, boolean primary, View.OnClickListener action) {
        GlassCard button = GlassComponents.button(context, title, 15,
                primary ? ACCENT : RAISED, primary ? ON_ACCENT : INK);
        button.setHighlightAlpha(0f);
        button.setOnClickListener(action);
        return button;
    }
}
