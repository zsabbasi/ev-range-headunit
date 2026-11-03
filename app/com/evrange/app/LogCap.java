package com.evrange.app;

import java.io.File;

/**
 * Keeps a growing log file from filling the unit's storage: once it is bigger than the limit it becomes
 * the single "old" copy (name.1.ext, replacing the previous one) and a fresh file starts. So a log never
 * takes more than about twice its limit. No Android dependencies.
 */
public final class LogCap {
    private LogCap() {
    }

    public static File oldCopy(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String old = dot > 0 ? n.substring(0, dot) + ".1" + n.substring(dot) : n + ".1";
        return new File(f.getParentFile(), old);
    }

    /** Returns true if the file was rotated. Never throws. */
    public static boolean rotateIfBig(File f, long maxBytes) {
        try {
            if (f.exists() && f.length() > maxBytes) {
                File old = oldCopy(f);
                if (old.exists()) {
                    old.delete();
                }
                return f.renameTo(old);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}