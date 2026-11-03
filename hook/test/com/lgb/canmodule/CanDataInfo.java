package com.lgb.canmodule;

public class CanDataInfo {
    public static class JiMaiFl_BmsInfo {
        public int Bsmdfk, CellTempMax, CellTempMin, Dcdl, Dcdy, Nlhs, Soc, Update, UpdateOnce;
    }

    public static class CAN_ACInfo {
        public int PWR, fgAC, fgACMax, fgAutoAC, fgAutoMode, fgInnerLoop, fgLoopDisable, fgDFBL, fgHeat, fgPTC, fgEco,
                fgUpWind, fgParallelWind, fgDownWind, fgForeWindMode, nWindValue, nLeftTemp, nRightTemp, nInTemp, fgAcErr1, Update;
        public String szLtTemp = "", szRtTemp = "";
    }

    public static class JiMaiFl_MotorInfo {
        public int Djkzqwd, Djmxdl, Djnjyxw, Djwd, Djzs, Sjnj, Update, UpdateOnce;
    }
}
