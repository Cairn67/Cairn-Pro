// Main screen: tabs, the app allowlist, the dashboard, and settings.
package com.cairn.manager;

import android.app.Activity;

import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cairn.manager.glass.GlassComponents;
import com.cairn.manager.glass.GlassCard;
import com.cairn.manager.glass.LiquidNavBar;
import com.cairn.manager.redesign.RedesignFrame;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends androidx.activity.ComponentActivity {
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Language.wrap(base));}

    private String appVersionName = "?";
    private volatile String detectedKernelVersion = "?";
    private String VERSION = "?";   
    private final java.util.Set<Integer> superuserUids = new java.util.HashSet<>();
    private volatile int superuserRequest;

    private static final int C_BG     = DashboardUi.BG;
    private static final int C_ACCENT = DashboardUi.ACCENT;
    private static final int C_TEXT   = DashboardUi.INK;
    private static final int C_DIM    = DashboardUi.MUTED;
    private static final int C_OK     = 0xFF86E3C3;
    private static final int C_WARN   = 0xFFF1CA8F;
    private static final int C_ERR    = 0xFFF5A2A8;

    private static final int ST_UNKNOWN = 0;
    private static final int ST_WORKING = 1;
    private static final int ST_NO_ROOT = 2;
    private static final int ST_NO_KERNEL = 3;

    private FileLog flog;
    private KeyStoreBox box;
    private CairnCore core;
    private AppList appList;

    private int state = ST_UNKNOWN;
    private long k0, k1;
    private boolean keyLoaded;

    private TextView tvBigState, tvSubState, tvDetail;
    private EditText keyInput;
    private StatusMark statusMark;
    private com.cairn.manager.redesign.RedesignStatusMark redesignStatusMark;
    private StatusMark.Glyph renderedGlyph = StatusMark.Glyph.CHECKING;
    private View[] overviewSections;

    private FrameLayout content;
    private View pageHome, pageApps, pageSettings;
    private ListView lvApps;
    private EditText etSearch;
    private AppAdapter adapter;
    private final List<AppList.App> allApps = new ArrayList<>();
    private final List<AppList.App> shownApps = new ArrayList<>();
    private final Map<Integer, Integer> granted = new HashMap<>();  
    private boolean appsLoaded;

    private final Object daemonLock = new Object();

    private static final int TAB_HOME = 0, TAB_MODULES = 1, TAB_APPS = 2, TAB_SETTINGS = 3;
    private static final int REQUEST_BACKGROUND = 711;
    private int curTab = TAB_HOME;
    private boolean appsLoading;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        flog = new FileLog(this);
        
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersionName = pi.versionName;
            VERSION = pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable t) {
            VERSION = "?";
        }
        box = new KeyStoreBox(this);
        core = new CairnCore(this);
        appList = new AppList(this);
        flog.header(this, VERSION);
        
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(buildUi());
        if (b == null) redesignHome.playEntrance();
        else if (b.getBoolean("settings_open")) switchTab(TAB_SETTINGS);

        installCrashHandler();

        try {
            flog.log("NATIVE", "libcairn.so loaded, getuid()=" + Native.getuid());
        } catch (Throwable t) {
            flog.log("NATIVE", "load FAILED: " + t);
        }

        checkStoragePermission();
        loadKey();
        refreshStatusAsync(true);
        refreshSuperuserCount();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("settings_open", curTab == TAB_SETTINGS);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (flog != null) {
            refreshStatusAsync(false);
            refreshSuperuserCount();
        }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("thread=").append(thread == null ? "?" : thread.getName())
                        .append("  exception=").append(ex.getClass().getName())
                        .append(": ").append(ex.getMessage());
                flog.log("CRASH", sb.toString());

                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                for (String ln : sw.toString().split("\n")) {
                    if (!ln.trim().isEmpty()) {
                        flog.log("CRASH", ln.trim());
                    }
                }

                Throwable c = ex.getCause();
                int depth = 0;
                while (c != null && depth < 5) {
                    flog.log("CRASH", "caused by: " + c.getClass().getName()
                            + ": " + c.getMessage());
                    c = c.getCause();
                    depth++;
                }
                flog.log("CRASH", "=== Process exiting ===");
            } catch (Throwable ignored) {
            }

            if (prev != null) {
                prev.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id <= 0) return 0;
        int height = getResources().getDimensionPixelSize(id);
        return Math.max(height, 0);
    }

    private ModulePage modulePage;
    private View pageModules;
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==710 && result==RESULT_OK && data!=null && data.getData()!=null)modulePage.install(data.getData());
        
        if(req==PathPolicyActivity.REQUEST && adapter!=null)adapter.notifyDataSetChanged();
        if(req==REQUEST_BACKGROUND && result==RESULT_OK && data!=null && data.getData()!=null) {
            View root = findViewById(android.R.id.content);
            RedesignFrame.applyBackground(this, root, data.getData());
            com.cairn.manager.redesign.RedesignSettings.refreshPreview(root);
            if (navBar != null) navBar.refreshBackdrop(120L);
        }
    }
    private View buildSettingsPage() {
        return com.cairn.manager.redesign.RedesignSettings.create(this,
                l("Settings", "设置"),
                l("Appearance", "外观"),
                l("Choose image", "选择背景图"), l("Reset", "恢复默认背景"),
                l("Background image", "背景图"), l("Close", "关闭"),
                l("Language", "语言"), l("App language", "应用语言"),
                l("Management", "管理"), l("Uninstall (coming later)", "卸载（预留）"),
                l("Not available yet", "暂未开放"),
                this::chooseBackgroundImage, this::resetBackgroundImage,
                Language.choices(this), Language.selectedIndex(this), i -> Language.applyChoice(this, i));
    }

    private void chooseBackgroundImage() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_BACKGROUND);
    }

    private void resetBackgroundImage() {
        View root = findViewById(android.R.id.content);
        RedesignFrame.applyBackground(this, root, null);
        com.cairn.manager.redesign.RedesignSettings.refreshPreview(root);
        if (navBar != null) navBar.refreshBackdrop(120L);
    }

    private View buildUi() {
        dashboard = new DashboardUi(this);
        FrameLayout root = new FrameLayout(this);
        root.setFocusableInTouchMode(true);
        
        root.setBackgroundColor(RedesignFrame.pageBackground(this));
        FrameLayout backdrop = RedesignFrame.createBackground(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        content = new SwipeContent(this, () -> curTab, () -> pagesInOrder(), this::switchTab, progress -> {
            if (navBar != null) navBar.setPageSwipePosition(progress);
        });
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        
        final int statusBar = statusBarInset();
        if (statusBar > 0) {
            content.setPadding(content.getPaddingLeft(), statusBar,
                    content.getPaddingRight(), content.getPaddingBottom());
        }
        redesignHome = new com.cairn.manager.redesign.RedesignHome(this, appVersionName, this::l,
                () -> switchTab(TAB_MODULES), this::openSuperusers, () -> { flog.log("BTN", "redesign save clicked"); doSaveAndEscalate(); },
                this::clearSavedKey);
        keyInput = redesignHome.keyInput;
        tvBigState = redesignHome.stateTitle;
        tvSubState = redesignHome.stateMessage;
        tvDetail = redesignHome.stateDetail;
        redesignStatusMark = redesignHome.stateMark;
        statusMark = null;
        tvModuleCount = redesignHome.moduleCount;
        tvSuperuserCount = redesignHome.superuserCount;
        tvAuthSummary = redesignHome.authSummary;
        pageHome = redesignHome.root;
        modulePage = new ModulePage(this, core, () -> new long[]{k0,k1},
                () -> state == ST_WORKING && keyLoaded, this::renderModuleCount);
        pageModules = modulePage.root;
        pageApps = buildAppsPage();
        pageSettings = buildSettingsPage();
        
        for (View page : pagesInOrder()) {
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));
            page.setVisibility(page == pageHome ? View.VISIBLE : View.INVISIBLE);
        }
        navBar = new LiquidNavBar(this);
        navBar.setBackdropLayers(backdrop, content);
        navBar.setLabels(new String[]{l("Overview", "概览"), l("Modules", "模块"),
                l("Apps", "应用"), l("Settings", "设置")});
        navBar.setOnTabSelectedListener(this::switchTab);
        navBar.setSelectedIndex(TAB_HOME);
        FrameLayout.LayoutParams dock = new FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int gutter = Math.max(12, (getResources().getConfiguration().screenWidthDp - 520) / 2);
        dock.setMarginStart(dp(gutter));
        dock.setMarginEnd(dp(gutter));
        dock.bottomMargin = dp(4);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.addView(navBar, dock);
        bindDockScroll();
        return root;
    }

    private View[] pagesInOrder() { return new View[]{pageHome, pageModules, pageApps, pageSettings}; }
    private float dockScroll;

    private void updateDockScroll(int tab, int dy, boolean atTop) {
        if (tab != curTab || navBar == null) return;
        if (!atTop && dy == 0) return;
        float next = atTop ? 0f : Math.max(0f, Math.min(1f,
                dockScroll + dy / (float) Math.max(1, dp(dy < 0 ? 52 : 88))));
        if (next == dockScroll) { if (dy > 0) navBar.setScrollCollapse(next); return; }
        dockScroll = next;
        navBar.setScrollCollapse(next);
    }

    private void bindDockScroll() {
        watchDockScroll((ScrollView) pageHome, TAB_HOME);
        watchDockScroll(modulePage.scroll, TAB_MODULES);
        watchDockScroll((ScrollView) pageSettings, TAB_SETTINGS);
        lvApps.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            private int first = -1, top, state;
            @Override public void onScrollStateChanged(android.widget.AbsListView list, int newState) {
                state = newState;
            }
            @Override public void onScroll(android.widget.AbsListView list, int index, int visible, int total) {
                View child = list.getChildAt(0);
                if (child == null) { first = -1; return; }
                int y = child.getTop();
                if (first >= 0 && state != SCROLL_STATE_IDLE) {
                    int dy = index == first ? top - y : Integer.signum(index - first) * dp(22);
                    updateDockScroll(TAB_APPS, dy, index == 0 && y >= list.getPaddingTop());
                }
                first = index;
                top = y;
            }
        });
        
        lvApps.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private boolean armed;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = e.getY();
                        
                        armed = isAppsListAtTop();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        armed = false;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (armed && !refreshOpen && e.getY() - downY > dp(64)) {
                            armed = false;
                            refreshAppsFromPull();
                        }
                        break;
                    default:
                        break;
                }
                return false;   
            }
        });
    }

    private boolean isAppsListAtTop() {
        if (lvApps == null || lvApps.getChildCount() == 0) return false;
        if (lvApps.getFirstVisiblePosition() != 0) return false;
        View first = lvApps.getChildAt(0);
        return first != null && first.getTop() >= lvApps.getPaddingTop();
    }

    private void watchDockScroll(ScrollView scroll, int tab) {
        scroll.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            private int lastY = scroll.getScrollY();
            @Override public void onScrollChanged() {
                int y = scroll.getScrollY();
                int dy = y - lastY;
                lastY = y;
                if (dy != 0) updateDockScroll(tab, dy, y <= 0);
            }
        });
    }

    private LiquidNavBar navBar;

    private View buildHomePage() {
        LinearLayout body = dashboard.column();
        ScrollView scroll = dashboard.scroller(body);
        boolean stack = dashboard.stack(1.20f);

        LinearLayout header = dashboard.column();
        LinearLayout masthead = dashboard.row();
        TextView brand = dashboard.heading("Cairn Pro", 43);
        brand.setLetterSpacing(-.054f);
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView version = dashboard.text("v" + appVersionName, 12, C_ACCENT, true);
        version.setBackground(dashboard.surface(18, DashboardUi.RAISED, DashboardUi.LINE));
        version.setPadding(dp(12), dp(7), dp(12), dp(7));
        version.setGravity(Gravity.CENTER);
        masthead.addView(version, new LinearLayout.LayoutParams(-2, -2));
        header.addView(masthead);
        dashboard.append(header, dashboard.text(l("Device overview", "设备概览"), 13, C_DIM, false), 0);
        dashboard.append(body, header, 0);

        LinearLayout hero = dashboard.panel(27, DashboardUi.RAISED, 17);
        GradientDrawable heroSurface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A333F, 0xFF171D25});
        heroSurface.setCornerRadius(dp(27));
        heroSurface.setStroke(dp(1), DashboardUi.LINE);
        hero.setBackground(heroSurface);
        LinearLayout statusRow = dashboard.row();
        statusMark = new StatusMark(this);
        statusMark.show(StatusMark.Glyph.CHECKING, C_ACCENT);
        statusRow.addView(statusMark, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout statusCopy = dashboard.column();
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        copyLp.setMarginStart(dp(12));
        statusRow.addView(statusCopy, copyLp);
        statusCopy.addView(dashboard.text(l("System status", "运行状态"), 11, C_ACCENT, true));
        tvBigState = dashboard.text(l("Checking…", "检测中…"), 25, C_TEXT, true);
        tvBigState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        dashboard.append(statusCopy, tvBigState, 1);
        hero.addView(statusRow);
        tvSubState = dashboard.text("", 13, C_DIM, false);
        tvSubState.setLineSpacing(dp(3), 1f);
        dashboard.append(hero, tvSubState, 10);
        tvSubState.setVisibility(View.GONE);
        dashboard.append(body, hero, 21);

        LinearLayout metrics = dashboard.row();
        LinearLayout modules = dashboard.panel(20, DashboardUi.RAISED, 14);
        modules.addView(dashboard.text(l("Modules  ›", "模块  ›"), 13, C_ACCENT, true));
        tvModuleCount = dashboard.text("—", 30, C_TEXT, true);
        tvModuleCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        dashboard.append(modules, tvModuleCount, 3);
        tvModuleMeta = dashboard.text(l("After authentication", "认证后可见"), 11, C_DIM, false);
        dashboard.append(modules, tvModuleMeta, 2);
        dashboard.actionTile(modules, DashboardUi.RAISED, () -> switchTab(TAB_MODULES));

        dashboard.append(body, modules, 12);
        LinearLayout device = buildDeviceSection();
        dashboard.append(body, device, 24);
        LinearLayout superusers = dashboard.row();
        TextView superuserLabel = dashboard.text(l("Superusers", "超级用户"), 13, C_DIM, true);
        superusers.addView(superuserLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        tvSuperuserCount = dashboard.text("—", 20, C_TEXT, true);
        tvSuperuserCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        superusers.addView(tvSuperuserCount, new LinearLayout.LayoutParams(-2, dp(34)));
        dashboard.append(body, superusers, 18);

        LinearLayout auth = dashboard.panel(23, DashboardUi.SURFACE, 12);
        LinearLayout authSummary = dashboard.row();
        authSummary.setMinimumHeight(dp(57));
        authSummary.setPaddingRelative(dp(7), dp(6), dp(10), dp(6));
        LinearLayout authCopy = dashboard.column();
        authCopy.addView(dashboard.text(l("Authentication", "认证管理"), 16, C_TEXT, true));
        tvAuthSummary = dashboard.text(l("Key not configured", "未配置密钥"), 12, C_DIM, false);
        dashboard.append(authCopy, tvAuthSummary, 3);
        authSummary.addView(authCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = dashboard.text("⌄", 22, C_ACCENT, true);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        authSummary.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        auth.addView(authSummary);

        LinearLayout authFields = dashboard.column();
        authFields.setPaddingRelative(dp(6), dp(13), dp(6), dp(5));
        TextView keyLabel = dashboard.text("SUPERKEY", 11, C_DIM, true);
        keyLabel.setLetterSpacing(.10f);
        authFields.addView(keyLabel);
        keyInput = new EditText(this);
        keyInput.setId(View.generateViewId());
        keyInput.setSaveEnabled(false);
        keyLabel.setLabelFor(keyInput.getId());
        keyInput.setHint(l("32-character hexadecimal key", "32 位十六进制密钥"));
        keyInput.setHintTextColor(C_DIM);
        keyInput.setTextColor(C_TEXT);
        keyInput.setTextSize(14);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        keyInput.setTypeface(Typeface.MONOSPACE);
        keyInput.setSingleLine(true);
        keyInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        keyInput.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyInput.setLongClickable(true);
        keyInput.setTextIsSelectable(true);
        styleField(keyInput);
        dashboard.append(authFields, keyInput, 7);
        LinearLayout actions = dashboard.row();
        GlassCard save = dashboard.button(l("Save and authenticate", "保存并认证"), true, v -> {
            flog.log("BTN", "btnSave clicked");
            doSaveAndEscalate();
        });
        GlassCard clear = dashboard.button(l("Clear key", "清除密钥"), false, v -> clearSavedKey());
        dashboard.split(actions, save, clear, dashboard.stack(1.15f));
        dashboard.append(authFields, actions, 11);
        dashboard.append(auth, authFields, 0);
        authFields.setVisibility(View.GONE);
        dashboard.actionTile(authSummary, DashboardUi.RAISED, () -> {
            boolean expand = authFields.getVisibility() != View.VISIBLE;
            if (!expand) hideInput(keyInput);
            UiMotion.reset(authFields);
            authFields.setVisibility(expand ? View.VISIBLE : View.GONE);
            arrow.setRotation(expand ? 180f : 0f);
            if (expand) UiMotion.statusIn(authFields);
            authSummary.setSelected(expand);
            if (navBar != null) navBar.refreshBackdrop(UiMotion.enabled() ? 260L : 0L);
        });
        dashboard.append(body, auth, 14);
        overviewSections = new View[]{header, hero, metrics, auth};
        return scroll;
    }

    private DashboardUi dashboard;
    private com.cairn.manager.redesign.RedesignHome redesignHome;
    private TextView tvModuleCount, tvModuleMeta, tvAuthSummary, tvSuperuserCount;

    private String l(String en, String zh) { return Language.text(this, en, zh); }

    private void refreshSuperuserCount() {
        final int request = ++superuserRequest;
        if (!keyLoaded || core == null) { updateOverviewCount(tvSuperuserCount, "—"); return; }
        final long key0 = k0, key1 = k1;
        new Thread(() -> {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (int[] entry : core.listAuth(key0, key1)) {
                if (entry[0] >= 10000 && entry[1] != CairnCore.POL_NONE) ids.add(entry[0]);
            }
            runOnUiThread(() -> {
                if (request != superuserRequest || isFinishing() || isDestroyed()) return;
                superuserUids.clear();
                superuserUids.addAll(ids);
                updateOverviewCount(tvSuperuserCount, String.format(getResources().getConfiguration().getLocales().get(0), "%d", ids.size()));
            });
        }, "cairn-superuser-count").start();
    }

    private static String kernelRelease() {
        String value = android.system.Os.uname().release;
        return value == null || value.isEmpty() ? "—" : value;
    }

    private void updateOverviewCount(TextView count, String value) {
        if (count == null || android.text.TextUtils.equals(count.getText(), value)) return;
        count.setText(value);
        if (curTab == TAB_HOME && count.isShown() && count.isLaidOut()) UiMotion.statusIn(count);
    }

    private void renderModuleCount(int count) {
        if (tvModuleCount == null) return;
        updateOverviewCount(tvModuleCount, count < 0 ? "—" : String.format(getResources().getConfiguration().getLocales().get(0), "%d", count));
        if (tvModuleMeta != null) tvModuleMeta.setText(count >= 0 ? l("Open modules", "进入模块管理")
                : count == ModulePage.COUNT_LOADING ? l("Reading modules…", "正在读取…")
                : count == ModulePage.COUNT_FAILED ? l("Not available", "暂时无法读取")
                : l("After authentication", "认证后可见"));
    }

    private void styleField(EditText field) {
        android.graphics.drawable.StateListDrawable fieldBg = new android.graphics.drawable.StateListDrawable();
        fieldBg.addState(new int[]{android.R.attr.state_focused}, dashboard.surface(15, 0xFF101916, C_ACCENT));
        fieldBg.addState(new int[0], dashboard.surface(15, 0xFF101916, DashboardUi.LINE));
        field.setBackground(fieldBg);
        field.setMinimumHeight(dp(50));
        field.setPaddingRelative(dp(14), dp(9), dp(14), dp(9));
    }

    private void hideInput(View focus) {
        if (!(focus instanceof EditText) || !focus.hasFocus()) return;
        android.view.inputmethod.InputMethodManager ime =
                getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    private void clearSavedKey() {
        box.clear();
        keyInput.setText("");
        k0 = k1 = 0;
        keyLoaded = false;
        flog.log("KEY", "cleared by user");
        toast(l("Key cleared", "密钥已清除"));
        refreshStatusAsync(false);
    }

    private boolean onlySuperusers;
    private View superuserFilter;
    private void openSuperusers() {
        setSuperuserFilter(true);
        etSearch.setText("");
        switchTab(TAB_APPS);
    }
    private void setSuperuserFilter(boolean enabled) {
        onlySuperusers = enabled;
        if (superuserFilter != null) {
            int visibility = enabled ? View.VISIBLE : View.GONE;
            if (superuserFilter.getVisibility() != visibility && curTab == TAB_APPS
                    && pageApps instanceof ViewGroup && pageApps.isLaidOut() && UiMotion.enabled()) {
                android.transition.TransitionSet transition = new android.transition.TransitionSet()
                        .addTransition(new android.transition.ChangeBounds())
                        .addTransition(new android.transition.Fade().addTarget(superuserFilter));
                transition.setDuration(195L).setInterpolator(UiMotion.SETTLE);
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) pageApps, transition);
            }
            superuserFilter.setVisibility(visibility);
        }
        applyFilter();
    }
    private Drawable appsSurface(View view, int radius) {
        return com.cairn.manager.redesign.RedesignFrost.INSTANCE.drawable(view, radius);
    }

    private TextView appsAction(String label, Runnable action) {
        var palette = com.cairn.manager.redesign.RedesignTokens.palette(this);
        TextView button = dashboard.text(label, 14, palette.primary, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPaddingRelative(dp(14), dp(6), dp(14), dp(6));
        button.setBackground(appsSurface(button, 24));
        com.cairn.manager.redesign.RedesignPress.install(button);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private View buildAppsPage() {
        var palette = com.cairn.manager.redesign.RedesignTokens.palette(this);
        LinearLayout page = dashboard.column();
        page.setPadding(0, dp(20), 0, 0);
        LinearLayout header = com.cairn.manager.redesign.RedesignModules.pageHeader(this,
                l("App access", "应用授权"),
                "");
        int gutter = dp(dashboard.gutter());
        header.setPadding(gutter, 0, gutter, dp(12));
        etSearch = new EditText(this);
        etSearch.setHint(l("Search apps or packages", "搜索应用名或包名"));
        etSearch.setHintTextColor(com.cairn.manager.redesign.RedesignTokens.palette(this).secondary);
        etSearch.setTextColor(com.cairn.manager.redesign.RedesignTokens.palette(this).primary);
        etSearch.setTextSize(14);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.setSingleLine(true);
        etSearch.setMinHeight(dp(48));
        etSearch.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
        etSearch.setBackground(appsSurface(etSearch, 15));
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable value) { applyFilter(); }
        });
        dashboard.append(header, etSearch, 14);
        superuserFilter = appsAction(l("Superusers", "超级用户") + "  ×", () -> setSuperuserFilter(false));
        superuserFilter.setVisibility(View.GONE);
        LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, -2);
        chip.topMargin = dp(8);
        chip.gravity = Gravity.START;
        header.addView(superuserFilter, chip);
        page.addView(header);

        refreshBand = dashboard.column();
        refreshBand.setOrientation(LinearLayout.HORIZONTAL);
        refreshBand.setGravity(Gravity.CENTER);
        refreshBand.setBackground(appsSurface(refreshBand, 14));
        refreshBand.setVisibility(View.GONE);
        refreshLabel = dashboard.text("", 12, palette.secondary, false);
        refreshSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        refreshSpinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        spinLp.setMarginEnd(dp(8));
        refreshBand.addView(refreshSpinner, spinLp);
        refreshBand.addView(refreshLabel, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bandLp = new LinearLayout.LayoutParams(-1, 0);
        bandLp.leftMargin = gutter;
        bandLp.rightMargin = gutter;
        page.addView(refreshBand, bandLp);

        FrameLayout results = new FrameLayout(this);
        page.addView(results, new LinearLayout.LayoutParams(-1, 0, 1f));
        lvApps = new ListView(this);
        lvApps.setDivider(null);
        lvApps.setDividerHeight(dp(9));
        lvApps.setCacheColorHint(Color.TRANSPARENT);
        
        lvApps.setVerticalScrollBarEnabled(false);
        lvApps.setHorizontalScrollBarEnabled(false);
        lvApps.setScrollbarFadingEnabled(true);
        lvApps.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lvApps.setPadding(gutter, dp(2), gutter, dp(DashboardUi.DOCK_CLEARANCE));
        lvApps.setClipToPadding(false); 
        adapter = new AppAdapter();
        lvApps.setAdapter(adapter);
        results.addView(lvApps, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = dashboard.column();
        empty.setBackground(appsSurface(empty, 18));
        empty.setPadding(dp(16), dp(16), dp(16), dp(14));
        appsEmptyTitle = dashboard.text("", 17, palette.primary, true);
        appsEmptyText = dashboard.text("", 13, palette.secondary, false);
        empty.addView(appsEmptyTitle, new LinearLayout.LayoutParams(-1, -2));
        dashboard.append(empty, appsEmptyText, 5);
        appsOverview = appsAction(l("Go to overview", "前往概览"), () -> switchTab(TAB_HOME));
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-2, -2);
        action.topMargin = dp(10);
        empty.addView(appsOverview, action);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        emptyLp.setMarginStart(gutter);
        emptyLp.setMarginEnd(gutter);
        emptyLp.topMargin = dp(5);
        results.addView(empty, emptyLp);
        appsEmpty = empty;
        updateAppsPresentation();
        return page;
    }

    private View appsEmpty, appsOverview;
    private TextView appsEmptyTitle, appsEmptyText;

    private LinearLayout refreshBand;
    private TextView refreshLabel;
    private ProgressBar refreshSpinner;
    private boolean refreshOpen;
    private int refreshBandHeight;

    private void updateAppsPresentation() {
        if (appsEmpty == null) return;
        boolean available = appsLoaded && state == ST_WORKING;
        boolean hasRows = available && !shownApps.isEmpty();
        lvApps.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        appsEmpty.setVisibility(hasRows ? View.GONE : View.VISIBLE);
        etSearch.setVisibility(available ? View.VISIBLE : View.GONE);
        superuserFilter.setVisibility(available && onlySuperusers ? View.VISIBLE : View.GONE);
        appsOverview.setVisibility(state == ST_NO_ROOT || state == ST_NO_KERNEL ? View.VISIBLE : View.GONE);
        if (available) {
            appsEmptyTitle.setText(onlySuperusers && etSearch.length() == 0
                    ? l("No authorized apps", "暂无已授权应用") : l("No matching apps", "没有匹配的应用"));
            appsEmptyText.setText(onlySuperusers && etSearch.length() == 0
                    ? "" : l("Try a different name or package.", "试试其他应用名或包名。"));
        } else if (state == ST_WORKING || state == ST_UNKNOWN) {
            appsEmptyTitle.setText(l("Getting ready…", "正在准备…"));
            appsEmptyText.setText(l("The app list will appear when available.", "列表就绪后会在这里显示。"));
        } else {
            appsEmptyTitle.setText(l("Authentication required", "需要先认证"));
            appsEmptyText.setText(l("Configure your key in the overview to manage app access.", "在概览完成认证后，即可管理应用授权。"));
        }
        appsEmptyText.setVisibility(appsEmptyText.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private View buildLogPage() {
        LinearLayout page = dashboard.column();
        page.setPadding(0, dp(20), 0, 0);
        int gutter = dp(dashboard.gutter());
        LinearLayout header = dashboard.header(l("Activity", "运行日志"),
                l("Manager events and daemon diagnostics.", "查看管理器事件和 cnd 诊断记录。"));
        header.setPadding(gutter, 0, gutter, dp(14));
        LinearLayout actions = dashboard.row();
        GlassCard cnd = dashboard.button(l("View cnd logs", "查看 cnd 日志"), false, v -> showCndLog());
        GlassCard copy = dashboard.button(l("Copy all", "复制全部"), false, v -> copyManagerLog());
        dashboard.split(actions, cnd, copy, dashboard.stack(1.2f));
        dashboard.append(header, actions, 14);
        page.addView(header);
        logScroller = new ScrollView(this);
        logScroller.setFillViewport(true);
        logScroller.setClipToPadding(false);
        logScroller.setPadding(gutter, 0, gutter, dp(DashboardUi.DOCK_CLEARANCE));
        root_postLog = dashboard.text(flog.dump(), 12, C_TEXT, false);
        root_postLog.setTypeface(Typeface.MONOSPACE);
        root_postLog.setTextDirection(View.TEXT_DIRECTION_LTR);
        root_postLog.setTextIsSelectable(true);
        root_postLog.setPadding(dp(14), dp(14), dp(14), dp(14));
        root_postLog.setLineSpacing(dp(5), 1f);
        root_postLog.setBackground(dashboard.surface(18, DashboardUi.SURFACE, DashboardUi.LINE));
        logScroller.addView(root_postLog, new ScrollView.LayoutParams(-1, -2));
        page.addView(logScroller, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    private void copyManagerLog() {
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cairn-log", flog.dump()));
                toast(l("Logs copied", "日志已复制"));
            }
        } catch (RuntimeException error) {
            toast(l("Could not copy logs", "无法复制日志"));
        }
    }

    private LinearLayout buildDeviceSection() {
        LinearLayout section = dashboard.panel(22, DashboardUi.SURFACE, 16);
        section.addView(dashboard.heading(l("Device", "设备信息"), 19));
        LinearLayout facts = dashboard.row();
        facts.setGravity(Gravity.TOP);
        LinearLayout hardware = dashboard.column();
        LinearLayout software = dashboard.column();
        hardware.addView(deviceFact(l("Model", "手机型号"), Build.MODEL));
        hardware.addView(deviceFact(l("Architecture", "架构"), Build.SUPPORTED_ABIS.length == 0 ? "—" : Build.SUPPORTED_ABIS[0]));
        software.addView(deviceFact(l("Android", "Android"), Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT));
        TextView kernel = deviceFact(l("Kernel", "内核"), kernelRelease());
        kernel.setMaxLines(2);
        kernel.setTextDirection(View.TEXT_DIRECTION_LTR);
        software.addView(kernel);
        facts.addView(hardware, new LinearLayout.LayoutParams(0, -2, 1f));
        facts.addView(software, new LinearLayout.LayoutParams(0, -2, 1f));
        dashboard.append(section, facts, 10);
        return section;
    }

    private TextView deviceFact(String title, String value) {
        TextView fact = dashboard.text(title + "\n" + value, 13, DashboardUi.INK, false);
        fact.setLineSpacing(dp(3), 1f);
        fact.setPadding(dp(11), dp(10), dp(8), dp(10));
        fact.setBackground(dashboard.surface(16, DashboardUi.RAISED, 0));
        fact.setTextIsSelectable(true);
        return fact;
    }

    private void recheckStatus() {
        toast(l("Checking…", "检测中…"));
        refreshStatusAsync(true);
    }

    private void updateDevicePresentation(int status) {
        if (tvAuthSummary != null) {
            tvAuthSummary.setText(status == ST_WORKING ? l("Authenticated", "已通过认证")
                    : keyLoaded ? l("Check saved key", "检查已保存的密钥")
                    : l("Key not configured", "未配置密钥"));
        }
        refreshSuperuserCount();
        updateAppsPresentation();
    }

    private void showCndLog() {
        if (k0 == 0 && k1 == 0) {
            toast(Language.text(MainActivity.this,"Configure a key first","还没有密钥，先完成初始化"));
            return;
        }
        toast(Language.text(MainActivity.this,"Reading cnd logs…","正在读取 cnd 日志..."));
        new Thread(() -> {
            CairnCore.Res r = null;
            String text;
            try {
                r = core.cndLog(k0, k1, 500);
                text = r.text();
            } catch (Throwable e) {
                text = Language.text(MainActivity.this,"Read failed: ","读取失败: ") + e;
            }
            final String body = text;
            final CairnCore.Res rr = r;
            runOnUiThread(() -> {
                flog.log("CND", "Read cnd log rc=" + (rr == null ? -1 : rr.rc));
                CairnDialog.Builder b = new CairnDialog.Builder(this);
                b.setTitle(Language.text(MainActivity.this,"cnd runtime logs","cnd 运行日志"));
                ScrollView sv = new ScrollView(this);
                TextView tv = new TextView(this);
                tv.setText(body);
                tv.setTextSize(9.5f);
                tv.setTypeface(Typeface.MONOSPACE);tv.setTextDirection(View.TEXT_DIRECTION_LTR);tv.setGravity(Gravity.LEFT);
                tv.setTextColor(0xFFB6C0CE);
                tv.setTextIsSelectable(true);
                int pad = dp(12);
                tv.setPadding(pad, pad, pad, pad);
                sv.addView(tv);
                b.setView(sv);
                b.setPositiveButton(Language.text(this,"Close",Language.text(MainActivity.this,"Close","关闭")), null);
                b.setNeutralButton(Language.text(this,"Copy","复制"), (d, w) -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "cnd-log", body));
                        toast(Language.text(MainActivity.this,"cnd logs copied","cnd 日志已复制"));
                    } catch (Throwable e) {
                        toast(Language.text(MainActivity.this,"Copy failed: ","复制失败: ") + e);
                    }
                });
                b.show();
            });
        }).start();
    }

    private ScrollView logScroller;

    private TextView root_postLog;

    private void postLog() {
        if (root_postLog == null) return;
        String log = flog.dump();
        boolean atEnd = logScroller != null && !logScroller.canScrollVertically(1);
        if (!log.contentEquals(root_postLog.getText())) {
            root_postLog.setText(log);
        }
    }

    private void switchTab(int i) {
        View[] pages = pagesInOrder();
        if (pages == null || i < 0 || i >= pages.length || i == curTab) return;
        View outgoing = pages[curTab];
        View incoming = pages[i];
        if (outgoing == null || incoming == null) return;
        int previous = curTab;
        curTab = i;
        dockScroll = 0f;
        if (navBar != null) navBar.setScrollCollapse(0f);
        hideInput(outgoing.findFocus());
        
        if (i == TAB_APPS && appsLoaded) {
            applyFilter(true);
            refreshGrantsAsync(() -> applyFilter(true));
        }
        if (previous == TAB_HOME) {
            if (overviewSections != null) {
                for (View section : overviewSections) {
                    if (section != null) UiMotion.reset(section);
                }
            }
            if (tvBigState != null) UiMotion.reset(tvBigState);
            if (tvSubState != null) UiMotion.reset(tvSubState);
        }
        SwipeContent swipe = (SwipeContent) content;
        boolean fromSwipe = swipe.isCommitting();
        if (fromSwipe && navBar != null) navBar.completePageSwipe(i);
        swipe.resetSwipe();
        UiMotion.reset(outgoing);
        outgoing.setVisibility(View.INVISIBLE);
        UiMotion.reset(incoming);
        incoming.setVisibility(View.VISIBLE);
        if (!fromSwipe) UiMotion.pageIn(incoming, Integer.compare(i, previous));
        if (navBar != null) {
            if (navBar.getSelectedIndex() != i) navBar.setSelectedIndex(i);
            navBar.refreshBackdrop(UiMotion.enabled() ? 290L : 0L);
        }
        flog.log("UI", "Selected page=" + i);
        if (i == TAB_MODULES) modulePage.refresh();
        else if (i == TAB_APPS && state == ST_WORKING) {
            if (!appsLoaded) loadAppsAsync();
            else refreshGrantsAsync();
        }
    }

    private void toast(String s) {
        try { CairnDialog.tip(this, s); } catch (Throwable ignored) {}
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    private void loadKey() {
        String hex = box.load();
        if (hex == null) {
            keyLoaded = false;
            k0 = k1 = 0;
            return;
        }
        try {
            long[] kk = CairnCore.keyToLongs(hex);
            k0 = kk[0]; k1 = kk[1];
            keyLoaded = true;
            
            flog.log("KEY", "loaded from " + box.mode());
        } catch (Throwable t) {
            keyLoaded = false;
            flog.log("KEY", "stored key unparsable: " + t);
        }
    }

    private void doSaveAndEscalate() {
        flog.log("KEY", "doSaveAndEscalate entered");
        String hex = keyInput.getText().toString().trim();
        flog.log("KEY", "input length=" + hex.length() + " (value redacted)");
        if (hex.isEmpty()) {
            flog.log("KEY", "EMPTY -> return");
            toast(Language.text(MainActivity.this,"Enter a key","请输入密钥"));
            return;
        }
        try {
            long[] kk = CairnCore.keyToLongs(hex);
            k0 = kk[0]; k1 = kk[1];
        } catch (Throwable t) {
            toast(Language.text(MainActivity.this,"Invalid key: expected 32 hexadecimal characters","密钥格式错误，需要 32 位十六进制"));
            return;
        }
        boolean ok = box.save(hex);
        flog.log("KEY", "Key loaded (value redacted)");
        keyLoaded = true;
        flog.log("KEY", "save=" + ok + " mode=" + box.mode()
                 + " boxLog=[" + box.logText().replace("\n", "|") + "]");
        flog.log("KEY", "calling refreshStatusAsync(true)");
        refreshStatusAsync(true);
    }

    private void refreshStatusAsync(final boolean verbose) {
        new Thread(() -> {
            int st = ST_NO_ROOT;
            String detail;
            if (!keyLoaded) {
                detail = Language.text(MainActivity.this,"Root unavailable: configure a key first","root 不可用：请先配置密钥");
                flog.log("AUTH", "skip selfTest: no key");
            } else {
                flog.log("AUTH", "selfTest start (key redacted)");
                CairnCore.Res r = core.selfTest(k0, k1);
                flog.log("AUTH", "selfTest rc=" + r.rc
                        + " out=[" + r.text().replace("\n", "|") + "]");
                boolean working = r.rc == 0;
                boolean uidZero = false;
                for (String line : r.text().split("\n")) {
                    if ("CAIRN_UID=0".equals(line.trim())) uidZero = true;
                }
                working = working && uidZero;
                detectedKernelVersion = r.kernelVersion();
                if (working) {
                    st = ST_WORKING;
                    int myUid = appList.selfUid();
                    if (myUid > 0) {
                        CairnCore.Res registered = core.initSelf(k0, k1, myUid);
                        flog.log("INIT", "initSelf rc=" + registered.rc);
                    }
                    ensureDaemon();
                    CairnCore.Res ds = core.daemonStatus(k0, k1);
                    detail = Language.text(MainActivity.this,"Authentication succeeded","提权成功")
                            + "\nCairn " + r.kernelVersion() + (ds.text().isEmpty() ? ""
                            : "\n" + ds.text().trim().replace("\n", "  "));
                    if (!appVersionName.equals(detectedKernelVersion)) {
                        detail += "\n⚠ APP " + appVersionName + " ≠ Cairn Pro " + detectedKernelVersion;
                        flog.log("VERSION", "Version mismatch warning: app=" + appVersionName + " kernel=" + detectedKernelVersion);
                    }
                } else {
                    long coreError = r.coreLoadError();
                    if (coreError != 0) {
                        String reason = coreError == -13
                                ? Language.text(MainActivity.this,"Key rejected by kernel loader","内核加载器拒绝了密钥")
                                : coreError == -22 || coreError == -74
                                ? Language.text(MainActivity.this,"Bundled core does not match the running kernel","内置载荷与当前内核不匹配")
                                : Language.text(MainActivity.this,"Kernel core could not be loaded","内核核心载荷加载失败");
                        detail = reason + " (" + coreError + ")";
                    } else {
                        detail = Language.text(MainActivity.this,"Root unavailable: child authentication or execution failed (rc=","root 不可用：子进程提权或执行失败（rc=") + r.rc
                                + Language.text(MainActivity.this,"); kernel support, key validity or execution restrictions cannot be determined from this alone","），无法仅据此区分内核未支持、密钥错误或执行受限");
                    }
                }
            }
            final int fst = st;
            if (verbose && keyLoaded && st != ST_WORKING) flog.log("AUTH", detail);

            ui(() -> {
                if (isDestroyed() || isFinishing()) return;
                state = fst;
                renderState(fst);
                postLog();
                if (fst == ST_WORKING && keyLoaded) {
                    if (curTab == TAB_MODULES) modulePage.refresh();
                    else modulePage.refreshSummary();
                    if (curTab == TAB_APPS && !appsLoaded) loadAppsAsync();
                    else if (appsLoaded) refreshGrantsAsync(() -> applyFilter(true));
                } else {
                    modulePage.showLocked();
                }
            });
        }).start();
    }

    private void ensureDaemon() {
        
        synchronized (daemonLock) {
            ensureDaemonLocked();
        }
    }

    private void ensureDaemonLocked() {
        
        String st = core.selfTestNoRoot(this);
        for (String ln : st.split("\n")) {
            if (!ln.trim().isEmpty()) flog.log("NATIVE", ln);
        }

        CairnCore.Res dp = core.deployDaemon(k0, k1, this);
        for (String ln : dp.text().split("\n")) {
            if (!ln.trim().isEmpty()) flog.log("DEPLOY", ln);
        }
        CairnCore.Res sr = core.startDaemon(k0, k1);
        for (String ln : sr.text().split("\n")) {
            if (!ln.trim().isEmpty()) flog.log("START", ln);
        }
    }

    private void renderState(int st) {
        
        try {
            String title;
            String message;
            StatusMark.Glyph glyph;
            int tint;
            switch (st) {
                case ST_WORKING:
                    title = Language.text(this, "Active", "运行正常");
                    message = Language.text(this, "Kernel patch active", "内核补丁已生效");
                    glyph = StatusMark.Glyph.CHECK;
                    tint = C_OK;
                    break;
                case ST_NO_ROOT:
                    title = Language.text(this, "Root unavailable", "未获取 root");
                    message = "";
                    glyph = StatusMark.Glyph.NOTICE;
                    tint = C_WARN;
                    break;
                case ST_NO_KERNEL:
                    title = Language.text(this, "Not installed", "未安装");
                    message = Language.text(this, "No Cairn Pro patch detected in the current kernel",
                            "当前内核没有 Cairn Pro 补丁，刷入 Cairn Pro 内核镜像后再试");
                    glyph = StatusMark.Glyph.UNAVAILABLE;
                    tint = C_ERR;
                    break;
                default:
                    title = Language.text(this, "Checking…", "检测中…");
                    message = "";
                    glyph = StatusMark.Glyph.CHECKING;
                    tint = C_ACCENT;
                    break;
            }
            if (redesignHome != null) redesignHome.setAuthenticationAvailable(st == ST_NO_ROOT);
            boolean changed = renderedGlyph != glyph;
            renderedGlyph = glyph;
            if (statusMark != null) statusMark.show(glyph, tint);
            if (redesignStatusMark != null) {
                com.cairn.manager.redesign.RedesignStatusMark.Glyph mapped = glyph == StatusMark.Glyph.CHECK
                        ? com.cairn.manager.redesign.RedesignStatusMark.Glyph.CHECK
                        : glyph == StatusMark.Glyph.NOTICE
                        ? com.cairn.manager.redesign.RedesignStatusMark.Glyph.NOTICE
                        : glyph == StatusMark.Glyph.UNAVAILABLE
                        ? com.cairn.manager.redesign.RedesignStatusMark.Glyph.UNAVAILABLE
                        : com.cairn.manager.redesign.RedesignStatusMark.Glyph.CHECKING;
                redesignStatusMark.show(mapped, tint);
            }
            if (!android.text.TextUtils.equals(tvBigState.getText(), title)) tvBigState.setText(title);
            if (!android.text.TextUtils.equals(tvSubState.getText(), message)) tvSubState.setText(message);
            tvSubState.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
            
            String visibleDetail = st == ST_NO_ROOT && keyLoaded
                    ? l("Authentication failed", "认证失败") : "";
            if (!android.text.TextUtils.equals(tvDetail.getText(), visibleDetail)) tvDetail.setText(visibleDetail);
            tvDetail.setVisibility(visibleDetail.isEmpty() ? View.GONE : View.VISIBLE);
            if (changed && tvBigState.isLaidOut() && tvBigState.isShown()) {
                UiMotion.statusIn(tvBigState, tvSubState);
            }
            if (navBar != null) navBar.refreshBackdrop(changed && UiMotion.enabled() ? 350L : 0L);
            updateDevicePresentation(st);
        } catch (Throwable t) {
            
            try {
                flog.log("UIERR", "renderState failed: " + t.getClass().getName());
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                for (String ln : sw.toString().split("\n")) {
                    if (!ln.trim().isEmpty()) {
                        flog.log("UIERR", ln.trim());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void openRefreshBand(String label) {
        if (refreshBand == null || refreshOpen) return;
        refreshOpen = true;
        refreshLabel.setText(label);
        refreshBand.setVisibility(View.VISIBLE);
        refreshBand.measure(
                View.MeasureSpec.makeMeasureSpec(
                        Math.max(pageApps.getWidth() - 2 * dp(dashboard.gutter()), 1),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        refreshBandHeight = Math.max(refreshBand.getMeasuredHeight(), dp(40));
        android.animation.ValueAnimator grow = android.animation.ValueAnimator.ofInt(0, refreshBandHeight);
        grow.setDuration(UiMotion.enabled() ? 190L : 0L);
        grow.setInterpolator(UiMotion.SETTLE);
        grow.addUpdateListener(a -> {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) refreshBand.getLayoutParams();
            lp.height = (Integer) a.getAnimatedValue();
            lp.topMargin = dp(2);
            lp.bottomMargin = dp(8);
            refreshBand.setLayoutParams(lp);
        });
        grow.start();
    }

    private void closeRefreshBand() {
        if (refreshBand == null || !refreshOpen) return;
        refreshOpen = false;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) refreshBand.getLayoutParams();
        int from = lp.height;
        android.animation.ValueAnimator shrink = android.animation.ValueAnimator.ofInt(from, 0);
        shrink.setDuration(UiMotion.enabled() ? 170L : 0L);
        shrink.setInterpolator(UiMotion.SETTLE);
        shrink.addUpdateListener(a -> {
            LinearLayout.LayoutParams cur = (LinearLayout.LayoutParams) refreshBand.getLayoutParams();
            cur.height = (Integer) a.getAnimatedValue();
            if (cur.height <= 0) {
                cur.topMargin = 0;
                cur.bottomMargin = 0;
            }
            refreshBand.setLayoutParams(cur);
        });
        shrink.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                refreshBand.setVisibility(View.GONE);
            }
        });
        shrink.start();
    }

    private void refreshAppsFromPull() {
        if (appsLoading) return;
        openRefreshBand(l("Refreshing…", "正在刷新…"));
        final long started = android.os.SystemClock.uptimeMillis();
        loadAppsAsync(() -> {
            
            long spent = android.os.SystemClock.uptimeMillis() - started;
            long wait = Math.max(0L, 420L - spent);
            ui(() -> lvApps.postDelayed(this::closeRefreshBand, wait));
        });
    }

    private void loadAppsAsync() {
        loadAppsAsync(null);
    }

    private void loadAppsAsync(Runnable done) {
        if (appsLoading) return;
        appsLoading = true;
        new Thread(() -> {
            List<AppList.App> list = appList.load(true, true);
            String self = appList.selfPackage();
            List<AppList.App> keep = new ArrayList<>();
            for (AppList.App a : list) {
                if (a.pkg.equals(self)) continue;
                keep.add(a);
            }
            ui(() -> {
                appsLoading = false;
                if (isDestroyed() || isFinishing()) return;
                allApps.clear();
                allApps.addAll(keep);
                appsLoaded = true;
                
                if (state == ST_WORKING) {
                    refreshGrantsAsync(() -> {
                        applyFilter(true);
                        if (done != null) done.run();
                    });
                } else {
                    applyFilter(true);
                    if (done != null) done.run();
                }
            });
        }).start();
    }

    private void applyFilter() {
        applyFilter(false);
    }

    private void applyFilter(boolean reorder) {
        String q = etSearch == null ? "" : etSearch.getText().toString().trim().toLowerCase();
        
        final List<AppList.App> previous = new ArrayList<>(shownApps);
        shownApps.clear();
        for (AppList.App a : allApps) {
            if (onlySuperusers && granted.getOrDefault(a.uid, CairnCore.POL_NONE) == CairnCore.POL_NONE) continue;
            if (q.isEmpty()
                    || a.label.toLowerCase().contains(q)
                    || a.pkg.toLowerCase().contains(q)) {
                shownApps.add(a);
            }
        }
        if (reorder) {
            sortAuthorizedFirst();
        } else {
            
            keepCurrentOrder(previous);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateAppsPresentation();
    }

    private void keepCurrentOrder(List<AppList.App> previous) {
        final Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < previous.size(); i++) {
            String key = previous.get(i).pkg;
            if (!rank.containsKey(key)) rank.put(key, i);
        }
        shownApps.sort((x, y) -> {
            Integer rx = rank.get(x.pkg);
            Integer ry = rank.get(y.pkg);
            if (rx == null && ry == null) return 0;   
            if (rx == null) return 1;                 
            if (ry == null) return -1;
            return Integer.compare(rx, ry);
        });
    }

    private void sortAuthorizedFirst() {
        shownApps.sort((x, y) -> {
            boolean gx = granted.getOrDefault(x.uid, CairnCore.POL_NONE) != CairnCore.POL_NONE;
            boolean gy = granted.getOrDefault(y.uid, CairnCore.POL_NONE) != CairnCore.POL_NONE;
            if (gx == gy) return 0;
            return gx ? -1 : 1;
        });
    }

    private void refreshGrantsAsync() {
        refreshGrantsAsync(null);
    }

    private void refreshGrantsAsync(Runnable done) {
        if (!keyLoaded) return;
        new Thread(() -> {
            List<int[]> l = core.listAuth(k0, k1);
            final Map<Integer, Integer> m = new HashMap<>();
            for (int[] p : l) m.put(p[0], p[1]);
            flog.log("GRANT", "Allowlist entries=" + m.size());
            ui(() -> {
                granted.clear();
                granted.putAll(m);
                applyFilter();
                postLog();
                if (done != null) done.run();
            });
        }).start();
    }

    private void toggleApp(final AppList.App a, final boolean on) {
        new Thread(() -> {
            CairnCore.Res r = on
                    ? core.grant(k0, k1, a.uid, true)
                    : core.revoke(k0, k1, a.uid);
            boolean ok = (r.rc == 0);
            flog.log("GRANT", (on ? "grant " : "revoke ") + a.pkg
                    + " uid=" + a.uid + " rc=" + r.rc + " ok=" + ok);
            ui(() -> {
                if (ok) {
                    if (on) granted.put(a.uid, CairnCore.POL_AUTO);
                    else granted.remove(a.uid);
                    applyFilter();
                    refreshSuperuserCount();
                    toast((on ? Language.text(MainActivity.this,"Granted: ","已授权 ") : Language.text(MainActivity.this,"Revoked: ","已撤销 ")) + a.label);
                } else {
                    toast(Language.text(MainActivity.this,"Operation failed rc=","操作失败 rc=") + r.rc + Language.text(MainActivity.this," (see logs)","（详见日志）"));
                }
                if (adapter != null) adapter.notifyDataSetChanged();
                postLog();
            });
        }).start();
    }

    private final class AppAdapter extends BaseAdapter {

        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int i) { return shownApps.get(i); }
        @Override public long getItemId(int i) { return shownApps.get(i).uid; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(dashboard.surface(19, DashboardUi.SURFACE, DashboardUi.LINE));
                int pad = dp(16);
                row.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                row.setLayoutParams(rlp);

                LinearLayout header = new LinearLayout(MainActivity.this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(header, new LinearLayout.LayoutParams(-1, -2));
                ImageView iv = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(48), dp(48));
                ilp.setMarginEnd(dp(12));
                iv.setLayoutParams(ilp);
                header.addView(iv);

                LinearLayout mid = new LinearLayout(MainActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                header.addView(mid, mlp);

                TextView name = new TextView(MainActivity.this);
                name.setTextColor(C_TEXT);
                name.setTextSize(16);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setSingleLine(true);
                mid.addView(name);

                TextView pkg = new TextView(MainActivity.this);
                pkg.setTextColor(C_DIM);
                pkg.setTextSize(11);pkg.setTextDirection(View.TEXT_DIRECTION_LTR);
                pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                pkg.setSingleLine(true);
                pkg.setPadding(0, dp(2), 0, 0);
                mid.addView(pkg);

                CairnSwitch sw = new CairnSwitch(MainActivity.this);

                LinearLayout rootBox = new LinearLayout(MainActivity.this);
                rootBox.setOrientation(LinearLayout.VERTICAL);
                rootBox.setGravity(Gravity.CENTER);
                rootBox.setPadding(dp(10), 0, 0, 0);
                TextView rootLabel = new TextView(MainActivity.this);
                rootLabel.setText("ROOT"); rootLabel.setTextSize(9); rootLabel.setTextColor(C_DIM);
                rootBox.addView(rootLabel); rootBox.addView(sw);
                header.addView(rootBox);

                View divider = new View(MainActivity.this);
                divider.setBackgroundColor(DashboardUi.LINE);
                LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
                dividerLp.topMargin = dp(14);
                row.addView(divider, dividerLp);
                LinearLayout footer = new LinearLayout(MainActivity.this);
                footer.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(footer, new LinearLayout.LayoutParams(-1, dp(48)));
                TextView hiding = new TextView(MainActivity.this);
                hiding.setTextSize(12); hiding.setSingleLine(true);
                hiding.setEllipsize(android.text.TextUtils.TruncateAt.END);
                footer.addView(hiding, new LinearLayout.LayoutParams(0, -2, 1f));
                MoreActionsView more = new MoreActionsView(MainActivity.this, C_DIM);
                footer.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
                h = new Holder(iv, name, pkg, sw, more, hiding);
                row.setTag(h);
                cv = row;
            } else {
                h = (Holder) cv.getTag();
            }

            AppList.App a = shownApps.get(pos);
            h.more.setContentDescription(a.label + Language.text(MainActivity.this,": more settings","：更多设置"));
            h.more.setOnClickListener(v -> {
                if (!keyLoaded || state != ST_WORKING) { toast(Language.text(MainActivity.this,"Root unavailable","root 不可用")); return; }
                PathPolicyActivity.open(MainActivity.this, h.more, a.label, a.pkg, a.uid);
            });
            h.name.setText(a.label);
            h.pkg.setText(a.pkg);
            boolean configured = !PathPolicy.saved(MainActivity.this, a.uid).isEmpty();
            h.hiding.setText(configured ? Language.text(MainActivity.this,"Paths configured · live state not verified","已配置路径 · 运行状态未核对") : Language.text(MainActivity.this,"Visibility not enabled","未启用隐藏功能"));
            h.hiding.setTextColor(configured ? 0xFFE8BB78 : C_DIM);
            h.sw.setContentDescription(a.label + Language.text(MainActivity.this,": ROOT authorization","：ROOT 授权"));
            if (a.icon != null) h.icon.setImageDrawable(a.icon);
            else h.icon.setImageDrawable(null);

            Integer pol = granted.get(a.uid);
            boolean on = pol != null && pol != CairnCore.POL_NONE;

            h.sw.setOnCheckedChangeListener(null);
            
            h.sw.setCheckedNow(on);
            h.sw.setOnCheckedChangeListener((btn, checked) -> {
                if (checked == on) return;
                toggleApp(a, checked);
            });
            return cv;
        }
    }

    private static final class Holder {
        final ImageView icon;
        final TextView name, pkg, hiding;
        final CairnSwitch sw;
        final MoreActionsView more;
        Holder(ImageView i, TextView n, TextView p, CairnSwitch s, MoreActionsView m, TextView status) {
            icon = i; name = n; pkg = p; sw = s; more = m; hiding = status;
        }
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT < 30) {
            flog.log("PERM", "sdk<30, legacy storage model");
            return;
        }
        boolean granted = false;
        try {
            granted = (Boolean) Environment.class
                    .getMethod("isExternalStorageManager").invoke(null);
        } catch (Throwable t) {
            flog.log("PERM", "isExternalStorageManager err: " + t);
        }
        if (granted) {
            flog.log("PERM", "all-files access granted");
            return;
        }
        flog.log("PERM", "MANAGE_EXTERNAL_STORAGE not granted, requesting");
        try {
            String act = (String) Settings.class
                    .getField("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                    .get(null);
            Intent i = new Intent(act);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            flog.log("PERM", "cannot open settings: " + t);
        }
    }
}
