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

    /**
     * Same as spawnRoot, but submits an external core container to the kernel loader
     * before escalating. The kernel still validates the digest, so a core that does not
     * belong to the running kernel is rejected exactly as before.
     */
    public static native long spawnRootCore(long k0, long k1, String cmdPath, String outPath,
                                            String corePath);
}
