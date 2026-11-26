// Append-only diagnostic log kept in the app's private storage.
package com.cairn.manager;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FileLog {

    private static final String DIR_NAME = "cairn_logs";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FNAME =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private final File file;
    private final boolean inDownload;
    private final StringBuilder memory = new StringBuilder();
    private boolean failed;

    public FileLog(Context ctx) {
        File dir = null;
        boolean pub = false;

        File dl = new File(Environment.getExternalStorageDirectory(), "Download");
        if (canWrite(dl)) {
            dir = new File(dl, DIR_NAME);
            pub = true;
        }
        
        if (dir == null) {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) dir = ext;
        }
        
        if (dir == null) dir = ctx.getFilesDir();

        if (dir != null && !dir.exists()) dir.mkdirs();

        this.inDownload = pub;
        String name = "cairn_" + FNAME.format(new Date()) + ".log";
        this.file = (dir == null) ? null : new File(dir, name);
    }

    private static boolean canWrite(File d) {
        if (d == null || !d.isDirectory()) return false;
        File probe = new File(d, ".cairn_probe");
        try {
            if (probe.createNewFile()) { probe.delete(); return true; }
            return probe.exists() && probe.delete();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean inDownload() { return inDownload; }

    public String path() {
        return file == null ? "(no file)" : file.getAbsolutePath();
    }

    public synchronized void log(String tag, String msg) {
        String line = TS.format(new Date()) + "  [" + tag + "]  " + msg;
        memory.append(line).append('\n');
        android.util.Log.i("CairnPro", line);
        if (file != null && !failed) {
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(line);
                w.write('\n');
            } catch (IOException e) {
                failed = true;
                android.util.Log.e("CairnPro", "log write failed: " + e);
            }
        }
    }

    public synchronized String dump() {
        return memory.toString();
    }

    public void header(Context ctx, String apkVer) {
        log("ENV", "=== Cairn Pro Manager " + apkVer + " ===");
        log("ENV", "brand=" + Build.BRAND + " model=" + Build.MODEL
                + " device=" + Build.DEVICE);
        log("ENV", "android=" + Build.VERSION.RELEASE
                + " sdk=" + Build.VERSION.SDK_INT
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0
                            ? Build.SUPPORTED_ABIS[0] : "?"));
        log("ENV", "package=" + ctx.getPackageName());
        log("ENV", "logFile=" + path() + " (Download=" + inDownload + ")");
    }
}
