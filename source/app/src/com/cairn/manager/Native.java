// Declarations for the native privilege layer.
package com.cairn.manager;

public final class Native {
    static {
        System.loadLibrary("cairn");
    }

    private Native() {}

    public static native long auth(long k0, long k1);

    public static native long probe();

    public static native long getuid();

    public static native long spawnRoot(long k0, long k1, String cmdPath, String outPath);
}
