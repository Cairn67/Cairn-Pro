// Full page for editing one app's hidden paths.
package com.cairn.manager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.cairn.manager.redesign.RedesignPress;
import com.cairn.manager.redesign.RedesignTokens;

public final class PathPolicyActivity extends Activity {
    
    public static final int REQUEST = 712;

    private static final Runnable NOOP = () -> { };

    private static final String EXTRA_LABEL = "label";
    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_ANCHOR = "anchor";

    private CairnCore core;
    private long k0, k1;
    private int uid;
    private EditText editor;
    private TextView status;
    private LinearLayout controls;
    private View card, dim;
    private GradientDrawable shape;
    private ValueAnimator animator;
    private float progress;
    private boolean busy, closing, ready, changed;
    private final Rect start = new Rect();
    private final Rect end = new Rect();

    public static void open(Activity from, View anchor, String label, String pkg, int uid) {
        Intent intent = new Intent(from, PathPolicyActivity.class);
        intent.putExtra(EXTRA_LABEL, label);
        intent.putExtra(EXTRA_PKG, pkg);
        intent.putExtra(EXTRA_UID, uid);
        int[] rect = new int[]{0, 0, 0, 0};
        Rect visible = new Rect();
        if (anchor != null && anchor.getLocalVisibleRect(visible)) {
            int[] at = new int[2];
            anchor.getLocationOnScreen(at);
            visible.offset(at[0], at[1]);
            rect = new int[]{visible.left, visible.top, visible.width(), visible.height()};
        }
        intent.putExtra(EXTRA_ANCHOR, rect);
        from.startActivityForResult(intent, REQUEST);
        from.overridePendingTransition(0, 0);
    }

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent intent = getIntent();
        uid = intent.getIntExtra(EXTRA_UID, -1);
        String label = intent.getStringExtra(EXTRA_LABEL);
        String pkg = intent.getStringExtra(EXTRA_PKG);
        if (uid < 0) { finish(); return; }
        try {
            String key = new KeyStoreBox(this).load();
            long[] k = CairnCore.keyToLongs(key);
            k0 = k[0]; k1 = k[1];
            core = new CairnCore(this);
        } catch (Exception error) {
            CairnDialog.tip(this, Language.text(this, "Cannot access the manager key", "无法读取管理器密钥"));
            finish(); return;
        }
        int[] anchor = intent.getIntArrayExtra(EXTRA_ANCHOR);
        if (anchor != null && anchor[2] > 0 && anchor[3] > 0) {
            start.set(anchor[0], anchor[1], anchor[0] + anchor[2], anchor[1] + anchor[3]);
        }

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        else window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        RedesignTokens.Palette p = RedesignTokens.palette(this);
        FrameLayout overlay = new FrameLayout(this);
        dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        dim.setAlpha(0f);
        dim.setOnClickListener(v -> dismiss());
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout frame = new FrameLayout(this);
        shape = new GradientDrawable();
        shape.setColor(p.surfaceRaised | 0xff000000);
        frame.setBackground(shape);
        frame.setClipToOutline(true);
        frame.setClickable(true);
        overlay.addView(frame, new FrameLayout.LayoutParams(1, 1));
        card = frame;

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(18), dp(18), dp(18));
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2));

        TextView title = text(p.primary, 23f, true);
        title.setText(Language.text(this, "Hidden paths", "隐藏路径"));
        controls.addView(title);

        TextView identity = text(p.secondary, 13f, false);
        identity.setText((label == null ? "" : label) + "\n" + (pkg == null ? "" : pkg) + " · UID " + uid);
        identity.setPadding(0, dp(8), 0, dp(12));
        controls.addView(identity);

        TextView help = text(p.secondary, 13f, false);
        help.setText(Language.text(this,
                "One absolute path per line. Includes subdirectories. Shared UID apps share these rules. Clear all lines and apply to remove this app's rules.",
                "每行一个绝对路径,包含子目录。共享 UID 的应用共用规则。清空后应用可移除此应用的规则。"));
        controls.addView(help);

        editor = new EditText(this);
        editor.setBackgroundColor(0x14000000);
        editor.setTextColor(p.primary);
        editor.setHintTextColor(p.secondary);
        editor.setHint("/data/local/example");
        editor.setText("");
        editor.setTextSize(14f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams input = new LinearLayout.LayoutParams(-1, dp(150));
        input.topMargin = dp(14);
        controls.addView(editor, input);

        status = text(p.secondary, 12f, false);
        status.setText(Language.text(this, "Saved configuration · live state not verified", "已保存配置 · 尚未核对运行状态"));
        status.setPadding(0, dp(12), 0, dp(12));
        controls.addView(status);

        TextView scope = text(0xffefca8a, 12f, false);
        scope.setText(Language.text(this,
                "Root / ADB can still access hidden paths. Hiding applies to the selected app UID.",
                "root/ADB 仍可访问隐藏路径;隐藏仅针对所选应用的 UID。"));
        scope.setPadding(0, 0, 0, dp(8));
        controls.addView(scope);

        TextView notice = text(0xffefca8a, 12f, false);
        notice.setText(Language.text(this, "Rules are stored by cnd. Automatic boot restore is not configured yet.",
                "规则由 cnd 持久保存;开机自动恢复尚未配置。"));
        controls.addView(notice);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        controls.addView(actions);

        TextView close = action(Language.text(this, "Close", "关闭"), false, p);
        TextView apply = action(Language.text(this, "Apply paths", "应用路径"), true, p);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(52), 1f);
        left.setMarginEnd(dp(12));
        actions.addView(close, left);
        actions.addView(apply, new LinearLayout.LayoutParams(0, dp(52), 1f));

        close.setOnClickListener(v -> dismiss());
        apply.setOnClickListener(v -> applyRules());

        setContentView(overlay);
        overlay.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                  int ol, int ot, int or, int ob) {
                if (overlay.getWidth() <= 0 || overlay.getHeight() <= 0 || ready) return;
                overlay.removeOnLayoutChangeListener(this);
                measureTarget(overlay);
                render(0f);
                animate(1f, 340L, NOOP);
            }
        });
        loadRules();
    }

    private TextView text(int color, float size, boolean medium) {
        TextView view = new TextView(this);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private TextView action(String label, boolean primary, RedesignTokens.Palette p) {
        TextView view = text(primary ? p.surface : p.primary, 14f, true);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? p.accent : 0x14ffffff);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), p.line);
        view.setBackground(background);
        view.setFocusable(true);
        RedesignPress.install(view);
        return view;
    }

    private void measureTarget(View overlay) {
        int[] at = new int[2];
        overlay.getLocationOnScreen(at);
        start.offset(-at[0], -at[1]);
        int width = Math.min(Math.max(overlay.getWidth() - dp(32), 1), dp(560));
        controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int limit = Math.max(overlay.getHeight() - dp(32), 1);
        int height = Math.min(Math.max(controls.getMeasuredHeight(), 1), limit);
        int left = (overlay.getWidth() - width) / 2;
        int top = (overlay.getHeight() - height) / 2;
        end.set(left, top, left + width, top + height);
        ready = true;
    }

    private void render(float value) {
        progress = value;
        int left = mix(start.left, end.left, value);
        int top = mix(start.top, end.top, value);
        int width = Math.max(mix(start.width(), end.width(), value), 1);
        int height = Math.max(mix(start.height(), end.height(), value), 1);
        FrameLayout.LayoutParams box = new FrameLayout.LayoutParams(width, height);
        box.leftMargin = left;
        box.topMargin = top;
        box.gravity = Gravity.TOP | Gravity.START;
        card.setLayoutParams(box);
        shape.setCornerRadius(dp(14) + dp(12) * Math.max(0f, Math.min(1f, value)));
        card.invalidateOutline();
        dim.setAlpha(Math.max(0f, Math.min(1f, value)));
        
        card.setAlpha(closing ? Math.max(0f, Math.min(1f, value / .28f)) : 1f);
        if (!closing) {
            controls.setAlpha(Math.max(0f, Math.min(1f, (value - .24f) / .68f)));
            controls.setTranslationY(dp(10) * (1f - Math.max(0f, Math.min(1f, value))));
        }
    }

    private static int mix(int from, int to, float value) {
        return Math.round(from + (to - from) * value);
    }

    private void animate(final float target, long duration, final Runnable finish) {
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        if (!ValueAnimator.areAnimatorsEnabled() || progress == target) {
            render(target);
            finish.run();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(duration);
        animator.setInterpolator(target == 1f ? new PathInterpolator(.22f, .77f, .28f, 1.06f)
                : new PathInterpolator(.35f, 0f, .35f, 1f));
        animator.addUpdateListener(a -> render((Float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { finish.run(); }
        });
        animator.start();
    }

    private void dismiss() {
        if (closing) return;
        closing = true;
        hideKeyboard();
        if (!ready) { finish(); return; }
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return; }
        
        controls.animate().cancel();
        controls.animate().alpha(0f).translationY(dp(4)).setDuration(90L).start();
        animate(0f, 225L, this::finish);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && editor != null) im.hideSoftInputFromWindow(editor.getWindowToken(), 0);
    }

    @Override public void onBackPressed() {
        if (!busy) dismiss();
    }

    @Override public void finish() {
        if (changed) setResult(RESULT_OK);
        super.finish();
        overridePendingTransition(0, 0);
    }

    private void loadRules() {
        busy = true;
        editor.setEnabled(false);
        status.setText(Language.text(this, "Working...", "处理中..."));
        new Thread(() -> {
            try {
                java.util.Map<Integer, java.util.List<String>> rules =
                        PathPolicy.readOfficial(PathPolicyActivity.this, core, k0, k1);
                String text = String.join("\n", rules.getOrDefault(uid, java.util.Collections.emptyList()));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false;
                    editor.setEnabled(true);
                    editor.setText(text);
                    status.setText(Language.text(PathPolicyActivity.this,
                            "Saved configuration · live state not verified", "已保存配置 · 尚未核对运行状态"));
                });
            } catch (Exception error) {
                android.util.Log.e("CairnPath", "Official rules read failed", error);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false;
                    editor.setEnabled(true);
                    status.setText(Language.text(PathPolicyActivity.this,
                            "Not applied · check kernel support and logs", "未应用 · 请检查内核支持与日志"));
                });
            }
        }, "cairn-path-read").start();
    }

    private void applyRules() {
        if (busy) return;
        String value = editor.getText().toString();
        try {
            PathPolicy.parse(value);
        } catch (Exception error) {
            status.setText(Language.text(this, "Invalid paths or too many rules", "路径无效或规则数量超限"));
            return;
        }
        busy = true;
        editor.setEnabled(false);
        status.setText(Language.text(this, "Working...", "处理中..."));
        new Thread(() -> {
            CairnCore.Res result = PathPolicy.apply(PathPolicyActivity.this, core, k0, k1, uid, value);
            android.util.Log.i("CairnPath", "Apply result rc=" + result.rc + " output=" + result.text());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                editor.setEnabled(true);
                status.setText(result.rc == 0
                        ? Language.text(PathPolicyActivity.this, "Applied · kernel confirmed", "已应用 · 内核已确认")
                        : result.rc == -7
                        ? Language.text(PathPolicyActivity.this, "Applied, but configuration could not be saved", "已应用,但配置保存失败")
                        : Language.text(PathPolicyActivity.this, "Not applied · check kernel support and logs", "未应用 · 请检查内核支持与日志"));
                if (result.rc == 0) changed = true;
            });
        }, "cairn-path-apply").start();
    }
}
