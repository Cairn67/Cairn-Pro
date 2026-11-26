// Client for privileged kernel operations: authentication, grants, and rule updates.
package com.cairn.manager;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class CairnCore {

    public static final int POL_NONE    = 0;
    public static final int POL_CONFIRM = 1;
    public static final int POL_AUTO    = 2;

    public static final int KERNEL_PATCHED = 1;
    public static final int KERNEL_STOCK   = 0;
    public static final int KERNEL_UNKNOWN = -3;

    public static final String BASE = "/data/adb/cairn";
    public static final String AUTH_DIR = BASE + "/auth";
    
    public static final String LOG_FILE = BASE + "/daemon.log";
    
    public static final String DAEMON_BIN = "/data/adb/cnd";
    public static final String CND_BUILD_ID = "cnd-1.0.1-access-v2-1";
    
    public static final String SH_BIN = "/data/adb/cairn/bin/sh";
    public static final String BIN_DIR = BASE + "/bin";
    public static final String MGR_UID_FILE = BASE + "/manager.uid";
    
    private final File cmdFile;
    private final File outFile;
    
    private static final java.util.concurrent.atomic.AtomicLong SEQ
            = new java.util.concurrent.atomic.AtomicLong();

    public CairnCore(Context ctx) {
        File dir = ctx.getFilesDir();
        cmdFile = new File(dir, "cairn.cmd");
        outFile = new File(dir, "cairn.out");
    }

    public static final class Res {
        public final long rc;        
        public final String out;     
        private final String transportHeader;
        public Res(long rc, String out) {
            this.rc = rc;
            String raw = out == null ? "" : out;
            int auth = raw.indexOf("[child] escalate OK");
            int end = auth < 0 ? -1 : raw.indexOf('\n', auth);
            if (auth >= 0 && (auth == 0 || raw.charAt(auth - 1) == '\n') && end >= 0) {
                int next = raw.indexOf('\n', end + 1);
                if (raw.startsWith("CAIRN_KERNEL_VERSION=", end + 1) && next >= 0) {
                    transportHeader = raw.substring(0, next + 1);
                    this.out = raw.substring(next + 1);
                    return;
                }
            }
            transportHeader = "";
            this.out = raw;
        }
        
        public long coreLoadError() {
            String raw = transportHeader + out;
            for (String line : raw.split("\n")) {
                if (line.startsWith("CAIRN_LOADER_ERROR=")) {
                    try { return Long.parseLong(line.substring("CAIRN_LOADER_ERROR=".length()).trim()); }
                    catch (NumberFormatException ignored) { return -5; }
                }
            }
            return 0;
        }
        public boolean ok() { return rc == 0; }
        public String text() { return out == null ? "" : out; }
        
        public String kernelVersion() {
            for (String line : transportHeader.split("\n")) {
                if (line.startsWith("CAIRN_KERNEL_VERSION=")) {
                    String value = line.substring("CAIRN_KERNEL_VERSION=".length()).trim();
                    if (value.matches("[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}")) return value;
                    return "unknown";
                }
            }
            return "unknown";
        }
    }

    public Res runRoot(long k0, long k1, String body) {
        return runRoot(k0, k1, body, true);
    }

    public Res runRoot(long k0, long k1, String body, boolean shell) {
        StringBuilder sb = new StringBuilder();
        if (shell) sb.append("SHELL\n");
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        
        long seq = SEQ.incrementAndGet();
        File dir = cmdFile.getParentFile();
        File myCmd = new File(dir, "cairn-" + seq + ".cmd");
        File myOut = new File(dir, "cairn-" + seq + ".out");
        try {
            writeText(myCmd, sb.toString());
        } catch (Throwable t) {
            return new Res(-3, "Failed to write script: " + t);
        }
        long rc;
        try {
            rc = Native.spawnRoot(k0, k1, myCmd.getAbsolutePath(), myOut.getAbsolutePath());
        } catch (Throwable t) {
            myCmd.delete();
            return new Res(-3, "spawnRoot failed: " + t);
        }
        String out = "";
        try { out = readText(myOut); } catch (Throwable t) {  }
        myCmd.delete();
        myOut.delete();
        return new Res(rc, out);
    }

    public Res selfTest(long k0, long k1) {
        return runRoot(k0, k1, "echo CAIRN_UID=$(id -u)\n");
    }

    public boolean isWorking(long k0, long k1) {
        Res r = selfTest(k0, k1);
        return r.rc == 0 && r.text().contains("CAIRN_UID=0");
    }

    public List<int[]> listAuth(long k0, long k1) {
        List<int[]> ret = new ArrayList<>();
        Res r = runRoot(k0, k1, DAEMON_BIN + " list 2>&1\n");
        for (String ln : r.text().split("\n")) {
            ln = ln.trim();
            if (ln.isEmpty()) continue;
            int c = ln.indexOf(':');
            if (c <= 0) continue;
            int uid, pol;
            try {
                uid = Integer.parseInt(ln.substring(0, c).trim());
                pol = Integer.parseInt(ln.substring(c + 1).trim());
            } catch (Exception e) { continue; }
            if (uid < 10000) continue;
            if (pol != POL_AUTO) pol = POL_CONFIRM;
            ret.add(new int[]{uid, pol});
        }
        return ret;
    }

    public Res grant(long k0, long k1, int uid, boolean autoAllow) {
        if (uid < 10000) return new Res(-4, "Rejected uid=" + uid + " is a system identity");
        String sh = DAEMON_BIN + " grant " + uid + (autoAllow ? " A" : "") + " 2>&1\n"
                + "echo GRANT_RC=$?\n";
        return foldSubRc(runRoot(k0, k1, sh), "GRANT_RC=");
    }

    private static Res foldSubRc(Res r, String tag) {
        int code = 0;
        boolean seen = false;
        for (String ln : r.text().split("\n")) {
            ln = ln.trim();
            if (!ln.startsWith(tag)) continue;
            seen = true;
            try { code = Integer.parseInt(ln.substring(tag.length()).trim()); }
            catch (Exception e) { code = -1; }
        }
        if (!seen) return new Res(-6, r.text() + "\n(Missing " + tag + "; script did not reach marker)");
        return new Res(code, r.text());
    }

    public Res diag(long k0, long k1, int uid) {
        String fn = AUTH_DIR + "/" + uid;
        return runRoot(k0, k1,
                "echo DIAG_uid=$(id -u)\n" +
                "echo DIAG_ctx=$(cat /proc/self/attr/current 2>&1)\n" +
                "echo DIAG_enforce=$(getenforce 2>&1)\n" +
                "echo DIAG_base=$(ls -ld " + BASE + " 2>&1)\n" +
                "echo DIAG_authdir=$(ls -ld " + AUTH_DIR + " 2>&1)\n" +
                "echo DIAG_authlist=$(ls -l " + AUTH_DIR + " 2>&1)\n" +
                "echo DIAG_daemon=$(pidof cnd 2>&1)\n" +
                "mkdir -p " + AUTH_DIR + " 2>&1; echo DIAG_mkdir=$?\n" +
                "printf A > " + fn + " 2>&1; echo DIAG_write=$?\n" +
                "chown 0:0 " + fn + " 2>&1; echo DIAG_chown=$?\n" +
                "chmod 0600 " + fn + " 2>&1; echo DIAG_chmod=$?\n" +
                "printf A > /data/local/tmp/cairn_probe 2>&1; echo DIAG_tmpwrite=$?\n" +
                "rm -f /data/local/tmp/cairn_probe 2>&1\n");
    }

    public Res cndLog(long k0, long k1, int lines) {
        if (lines <= 0) lines = 400;
        String sh = "echo CNDLOG_stat=$(ls -l " + LOG_FILE + " 2>&1)\n"
                + "echo CNDLOG_wc=$(wc -l < " + LOG_FILE + " 2>&1)\n"
                + "echo ===CNDLOG_BEGIN===\n"
                + "tail -n " + lines + " " + LOG_FILE + " 2>&1\n"
                + "echo ===CNDLOG_END===\n";
        return runRoot(k0, k1, sh);
    }

    public Res clearCndLog(long k0, long k1) {
        return runRoot(k0, k1, ": > " + LOG_FILE + "\necho CLEARED=$?\n");
    }

    public Res revoke(long k0, long k1, int uid) {
        return foldSubRc(runRoot(k0, k1,
                DAEMON_BIN + " revoke " + uid + " 2>&1\n" +
                "echo REVOKE_RC=$?\n"), "REVOKE_RC=");
    }

    public Res initSelf(long k0, long k1, int myUid) {
        return runRoot(k0, k1,
                "mkdir -p " + BASE + " " + AUTH_DIR + " " + BIN_DIR + " 2>/dev/null\n" +
                "chmod 0700 " + BASE + " 2>/dev/null\n" +
                "echo " + myUid + " > " + MGR_UID_FILE + "\n" +
                "chown 0:0 " + MGR_UID_FILE + " 2>/dev/null\n" +
                "chmod 0600 " + MGR_UID_FILE + " 2>/dev/null\n" +
                "echo MGR_UID=$(cat " + MGR_UID_FILE + ")\n");
    }

    public static java.io.File daemonBin(Context ctx) {
        return new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libcairnd.so");
    }

    public static java.io.File shBin(Context ctx) {
        return new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libcairnsh.so");
    }

    public String stageDaemon(Context ctx) {
        java.io.File f = daemonBin(ctx);
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    public String selfTestNoRoot(Context ctx) {
        StringBuilder sb = new StringBuilder();
        java.io.File bin = daemonBin(ctx);
        java.io.File res = new java.io.File(ctx.getFilesDir(), "selftest.txt");
        res.delete();
        sb.append("EXISTS=").append(bin.isFile() ? "yes" : "no").append('\n');
        sb.append("PATH=").append(bin.getAbsolutePath()).append('\n');
        sb.append("SIZE=").append(bin.length()).append('\n');
        sb.append("CANEXEC=").append(bin.canExecute() ? "yes" : "no").append('\n');
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--selftest");
            pb.redirectErrorStream(true);
            
            pb.environment().put("CAIRN_SELFTEST_OUT", res.getAbsolutePath());
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = r.readLine()) != null) sb.append("OUT:").append(ln).append('\n');
            r.close();
            sb.append("RC=").append(p.waitFor()).append('\n');
        } catch (Throwable t) {
            sb.append("ERR=").append(t).append('\n');
        }
        
        if (res.isFile()) {
            try {
                String body = readText(res);
                for (String ln : body.split("\n")) {
                    if (!ln.trim().isEmpty()) sb.append("FILE:").append(ln).append('\n');
                }
            } catch (Throwable t) {
                sb.append("FILE_ERR=").append(t).append('\n');
            }
        } else {
            sb.append("FILE=missing\n");
        }
        return sb.toString();
    }

    public Res probeAdb(long k0, long k1) {
        return runRoot(k0, k1,
                "UID\n" +
                "IDS\n" +
                "STAT /data/adb\n" +
                "STAT /data/adb/cairn\n" +
                "MKDIR /data/adb/cairn\n" +
                "MKDIR /data/adb/cairn/auth\n" +
                "STAT /data/adb/cairn/auth\n" +
                "WRITE /data/adb/cairn/auth/probe 41\n" +
                "STAT /data/adb/cairn/auth/probe\n" +
                "UID\n", false);
    }

    public Res deployDaemon(long k0, long k1, Context ctx) {
        java.io.File src = daemonBin(ctx);
        if (!src.isFile()) return new Res(-5, "libcairnd.so missing from nativeLibraryDir");
        String staged = src.getAbsolutePath();
        
        java.io.File shSrc = shBin(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p " + AUTH_DIR + " 2>/dev/null\n");
        sb.append("mkdir -p /data/adb/cairn/bin 2>/dev/null\n");
        sb.append("rm -f " + DAEMON_BIN + "\n");
        sb.append("cp " + staged + " " + DAEMON_BIN + " 2>&1\n");
        sb.append("echo CND_SIZE=$(stat -c%s " + DAEMON_BIN + " 2>/dev/null || echo 0)\n");
        sb.append("echo CND_SHA=$(sha256sum " + DAEMON_BIN + " 2>/dev/null | cut -c1-16)\n");
        if (shSrc.isFile()) {
            sb.append("rm -f " + SH_BIN + "\n");
            sb.append("cp " + shSrc.getAbsolutePath() + " " + SH_BIN + " 2>&1\n");
            sb.append("echo SH_SIZE=$(stat -c%s " + SH_BIN + " 2>/dev/null || echo 0)\n");
            sb.append("echo SH_SHA=$(sha256sum " + SH_BIN + " 2>/dev/null | cut -c1-16)\n");
        } else {
            sb.append("echo SH_SRC_MISSING=1\n");
        }
        sb.append(DAEMON_BIN + " --selftest 2>&1 | head -3 | tr '\n' '|'\necho\n");
        sb.append("cnd_build=$(" + DAEMON_BIN + " --build-id 2>/dev/null)\n");
        sb.append("echo CND_BUILD=$cnd_build\n");
        sb.append("if [ \"$cnd_build\" != '" + CND_BUILD_ID + "' ]; then echo DEPLOY_RC=12; exit 12; fi\n");
        sb.append("[ -x " + SH_BIN + " ] || { echo DEPLOY_RC=13; exit 13; }\n");
        sb.append("echo DEPLOY_RC=0\n");
        return foldSubRc(runRoot(k0, k1, sb.toString()), "DEPLOY_RC=");
    }

    public Res startDaemon(long k0, long k1) {
        
        return foldSubRc(runRoot(k0,k1,
                DAEMON_BIN + " --sync-wl 2>&1\n" +
                "cairn_sync_rc=$?\n" +
                DAEMON_BIN + " access provision-boot 2>&1\n" +
                "[ $? -eq 0 ] || cairn_sync_rc=1\n" +
                DAEMON_BIN + " access reload-modules 2>&1\n" +
                "[ $? -eq 0 ] || cairn_sync_rc=1\n" +
                "echo WL_SYNC_RC=$cairn_sync_rc\n"), "WL_SYNC_RC=");
    }

    public Res daemonStatus(long k0, long k1) {
        return runRoot(k0, k1,
                "echo DAEMON_PID=$(pidof cnd 2>/dev/null || echo none)\n" +
                
                "echo CND_BIN=$([ -x " + DAEMON_BIN + " ] && echo yes || echo no)\n" +
                "echo CND_SIZE=$(stat -c%s " + DAEMON_BIN + " 2>/dev/null || echo 0)\n" +
                
                "echo SU_REAL=$([ -e /system/bin/su ] && echo EXISTS || echo ABSENT)\n" +
                
                "echo CAIRN_BIN=$(ls " + BIN_DIR + " 2>/dev/null | tr '\\n' ',' || echo none)\n" +
                "echo MGR_UID=$(cat " + MGR_UID_FILE + " 2>/dev/null || echo none)\n" +
                
                "echo ---CND_STATUS---\n" +
                DAEMON_BIN + " status 2>&1\n");
    }

    public static long[] keyToLongs(String hex) {
        byte[] raw = KeyStoreBox.hexToBytes(hex);
        if (raw.length != 16) throw new IllegalArgumentException("Expected 16 bytes");
        return KeyStoreBox.keyToLongs(raw);
    }

    private static void writeText(File f, String s) throws Exception {
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f, false);
        try {
            fo.write(s.getBytes("UTF-8"));
            fo.flush();
        } finally {
            fo.close();
        }
    }

    private static String readText(File f) throws Exception {
        if (!f.exists()) return "";
        java.io.FileInputStream fi = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = fi.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        } finally {
            fi.close();
        }
    }
}
