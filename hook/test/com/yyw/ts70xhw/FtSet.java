package com.yyw.ts70xhw;

/** Test stand-in for the unit's settings class: only the two methods the feed uses. */
public class FtSet {
    public static int value = 0;
    public static int gets = 0;
    public static int sets = 0;
    public static boolean throwLinkage = false;
    public static boolean ignoreSet = false;

    public static int GetCanS(int i) {
        if (throwLinkage) {
            throw new UnsatisfiedLinkError("simulated");
        }
        gets++;
        return i == 70 ? value : 0;
    }

    public static int SetCanS(byte b, int i) {
        sets++;
        if (i == 70 && !ignoreSet) {
            value = b;
        }
        return 0;
    }
}