// Enumerates installed applications with their icons and UIDs.
package com.cairn.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AppList {

    public static final class App {
        public final String label;
        public final String pkg;
        public final int uid;
        public final Drawable icon;

        public App(String label, String pkg, int uid, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.uid = uid;
            this.icon = icon;
        }
    }

    private final Context ctx;
    private final PackageManager pm;

    public AppList(Context ctx) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
    }

    public static int kindOf(ApplicationInfo ai) {
        boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        return sys ? 1 : 0;
    }

    public List<App> load(boolean includeSystem, boolean needIcon) {
        List<App> out = new ArrayList<>();
        List<ApplicationInfo> list;
        try {
            list = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return out;
        }
        if (list == null) return out;

        for (ApplicationInfo ai : list) {
            if (ai == null) continue;
            boolean sys = (kindOf(ai) == 1);
            
            if (sys && !includeSystem) continue;
            String label;
            try {
                CharSequence cs = pm.getApplicationLabel(ai);
                label = cs == null ? ai.packageName : cs.toString();
            } catch (Throwable t) {
                label = ai.packageName;
            }
            Drawable ic = null;
            if (needIcon) {
                try { ic = pm.getApplicationIcon(ai); } catch (Throwable t) {  }
            }
            out.add(new App(label, ai.packageName, ai.uid, ic));
        }

        Collections.sort(out, (a, b) -> {
            
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    public String selfPackage() { return ctx.getPackageName(); }

    public int selfUid() {
        try {
            return pm.getApplicationInfo(selfPackage(), 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    public int uidOf(String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }
}
