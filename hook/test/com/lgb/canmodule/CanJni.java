package com.lgb.canmodule;

public class CanJni {
    public static int type = 356;
    public static int amp = 500;   // raw Dcdl (500 = 0 A)
    public static int rpmRaw = 15000;
    public static int soc = 74;
    public static long delayMs = 0;
    public static boolean throwRuntime = false;
    public static boolean throwLinkage = false;
    public static int queries = 0;
    public static int reads = 0;

    public static int GetCanType() {
        return type;
    }

    public static void CanCmdQueryData(int a, int b, int c) {
        queries++;
    }

    public static void JiMaiFlGetBmsInfo(CanDataInfo.JiMaiFl_BmsInfo o) {
        reads++;
        if (throwLinkage) {
            throw new UnsatisfiedLinkError("simulated stale reference");
        }
        if (throwRuntime) {
            throw new RuntimeException("simulated failure");
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
            }
        }
        o.Dcdy = 328;
        o.Dcdl = amp;
        o.Soc = soc;
        o.CellTempMax = 73;
        o.CellTempMin = 72;
        o.UpdateOnce = 1;
    }

    public static int acInner = 0;
    public static int acFan = 0;
    public static int acReads = 0;
    public static boolean acThrow = false;
    public static String acLeft = "22";

    public static void GetACInfo(CanDataInfo.CAN_ACInfo o) {
        acReads++;
        if (acThrow) {
            throw new UnsatisfiedLinkError("simulated");
        }
        o.fgInnerLoop = acInner;
        o.nWindValue = acFan;
        o.szLtTemp = acLeft;
        o.fgAC = 1;
    }

    public static void JiMaiFlGetMotorInfo(CanDataInfo.JiMaiFl_MotorInfo o) {
        o.Djwd = 73;
        o.Djkzqwd = 74;
        o.Djzs = rpmRaw;
        o.UpdateOnce = 1;
    }
}
