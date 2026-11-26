// Full-page console that shows module installation output.
package com.cairn.manager;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cairn.manager.redesign.RedesignFrost;
import com.cairn.manager.redesign.RedesignTokens;

public final class InstallConsole extends Activity {

    private static final int STEPS = 4;

    private static final String[] STEP_LABELS_EN = {"Extract", "Prepare", "Install", "Queue"};
    private static final String[] STEP_LABELS_ZH = {"解压", "准备", "安装", "排队"};

    private static String transcript = "";
    static java.io.File liveLog;
    static boolean running;

    private TextView out;
    private TextView heading;
    private TextView sub;
    private ScrollView scroll;
    private LinearLayout steps;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    private int renderedLength = -1;

    static synchronized void append(String s) {
        transcript += s + "\n";
        if (transcript.length() > 180000) {
            transcript = transcript.substring(transcript.length() - 180000);
        }
    }

    static void start(Activity a) {
        liveLog = null;
        transcript = "";
        running = true;
        a.startActivity(new Intent(a, InstallConsole.class));
    }

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        RedesignTokens.Palette p = RedesignTokens.palette(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(46), dp(22), dp(26));
        root.setBackgroundColor(p.background);

        heading = new TextView(this);
        heading.setTextSize(28f);
        heading.setIncludeFontPadding(false);
        heading.setTextColor(p.primary);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(heading);

        sub = new TextView(this);
        sub.setTextSize(13f);
        sub.setTextColor(p.secondary);
        sub.setPadding(0, dp(8), 0, dp(18));
        root.addView(sub);

        steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        steps.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(steps, new LinearLayout.LayoutParams(-1, -2));
        buildSteps(p);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(RedesignFrost.INSTANCE.drawable(card, 20));
        card.setClipToOutline(true);

        scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTextSize(12.5f);
        out.setTextColor(p.secondary);
        out.setTextDirection(View.TEXT_DIRECTION_LTR);
        out.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        out.setTextIsSelectable(true);
        out.setLineSpacing(dp(2), 1f);
        scroll.addView(out);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardLp.bottomMargin = dp(16);
        root.addView(card, cardLp);

        TextView back = new TextView(this);
        back.setText(Language.text(this, "Back to modules", "返回模块"));
        back.setGravity(Gravity.CENTER);
        back.setTextSize(14f);
        back.setIncludeFontPadding(false);
        back.setTextColor(p.primary);
        back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.surfaceRaised);
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), p.line);
        back.setBackground(bg);
        back.setFocusable(true);
        com.cairn.manager.redesign.RedesignPress.install(back);
        back.setOnClickListener(v -> {
            if (running) {
                CairnDialog.tip(this, Language.text(this, "Installation in progress; please wait", "安装进行中,请稍候"));
            } else {
                finish();
            }
        });
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(50)));

        UiMotion.revealSections(heading, sub, steps, card);
        setContentView(root);
        h.post(update);
    }

    private void buildSteps(RedesignTokens.Palette p) {
        steps.removeAllViews();
        String[] labels = Language.text(this, "x", "y").equals("y") ? STEP_LABELS_ZH : STEP_LABELS_EN;
        for (int i = 0; i < STEPS; i++) {
            TextView chip = new TextView(this);
            chip.setText((i + 1) + " · " + labels[i]);
            chip.setTextSize(11.5f);
            chip.setIncludeFontPadding(false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(7), dp(10), dp(7));
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setCornerRadius(dp(11));
            chip.setBackground(chipBg);
            chip.setTag(chipBg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            steps.addView(chip, lp);
        }
        paintSteps(0);
    }

    private void paintSteps(int current) {
        RedesignTokens.Palette p = RedesignTokens.palette(this);
        for (int i = 0; i < steps.getChildCount(); i++) {
            TextView chip = (TextView) steps.getChildAt(i);
            GradientDrawable bg = (GradientDrawable) chip.getTag();
            boolean done = current > i + 1;
            boolean active = current == i + 1;
            if (done) {
                bg.setColor(p.surfaceRaised);
                chip.setTextColor(p.secondary);
            } else if (active) {
                bg.setColor(p.accent);
                chip.setTextColor(p.surface);
            } else {
                bg.setColor(Color.TRANSPARENT);
                bg.setStroke(dp(1), p.line);
                chip.setTextColor(p.secondary);
            }
        }
    }

    private static int currentStep(String text) {
        int step = 0;
        int idx = 0;
        while (true) {
            int open = text.indexOf('[', idx);
            if (open < 0) break;
            int slash = text.indexOf('/', open);
            int close = text.indexOf(']', open);
            if (slash > open && close > slash) {
                try {
                    int n = Integer.parseInt(text.substring(open + 1, slash).trim());
                    if (n > step) step = n;
                } catch (NumberFormatException ignored) {
                }
            }
            idx = open + 1;
        }
        return step;
    }

    private CharSequence stylize(String text) {
        RedesignTokens.Palette p = RedesignTokens.palette(this);
        int ok = p.positive;
        int bad = 0xFFE0857A;
        int warn = 0xFFEFCA8A;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            if (end > start) {
                String line = text.substring(start, end);
                String t = line.trim();
                int colour = 0;
                boolean bold = false;
                if (t.startsWith("✓")) { colour = ok; bold = true; }
                else if (t.startsWith("✗")) { colour = bad; bold = true; }
                else if (t.startsWith("[")) { colour = p.primary; bold = true; }
                else if (t.startsWith("-") || t.startsWith("!")) { colour = p.secondary; }
                else if (t.startsWith("Warning") || t.startsWith("警告")) { colour = warn; }
                if (colour != 0) {
                    sb.setSpan(new ForegroundColorSpan(colour), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (bold) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return sb;
    }

    private final Runnable update = new Runnable() {
        @Override public void run() {
            String live = "";
            try {
                java.io.File f = liveLog;
                if (f != null && f.isFile()) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        byte[] b = new byte[(int) Math.min(f.length(), 131072)];
                        int n = in.read(b);
                        if (n > 0) live = new String(b, 0, n, "UTF-8");
                    }
                }
            } catch (Exception ignored) {
            }

            String full = transcript + live;
            if (full.length() != renderedLength) {
                renderedLength = full.length();
                out.setText(stylize(full));
            }
            heading.setText(running
                    ? Language.text(InstallConsole.this, "Installing module", "正在安装模块")
                    : Language.text(InstallConsole.this, "Installation finished", "安装已结束"));
            sub.setText(running
                    ? Language.text(InstallConsole.this,
                            "Extract → customize.sh → Stage → Reboot", "解压 → customize.sh → 暂存 → 重启")
                    : Language.text(InstallConsole.this,
                            "Reboot to activate the new version.", "重启后新版本才会生效。"));
            paintSteps(currentStep(full));
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            h.postDelayed(this, 250);
        }
    };

    @Override public void onBackPressed() {
        if (!running) super.onBackPressed();
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(update);
        super.onDestroy();
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
}
