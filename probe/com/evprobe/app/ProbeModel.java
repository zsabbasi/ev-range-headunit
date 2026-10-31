package com.evprobe.app;

import java.util.Locale;

/**
 * Pure-Java model of the unit's base car-info array (ICarInfoService.requestCarBaseInfo, 82 ints):
 * names for each index, change tracking, and a CSV row that leaves out the vehicle identification number.
 * The index layout is read from CarInfoService.requestCarBaseInfo in the unit's own software.
 */
public final class ProbeModel {
    public static final int SIZE = 82;
    public static final int VIN_FIRST = 42;
    public static final int VIN_LAST = 58;

    public static final String[] NAMES = new String[SIZE];

    static {
        String[] n = {"Avalid", "SpeedDw", "Speed", "Rpm", "WaterTemp", "TpmsDw", "Tpms0", "Tpms1", "Tpms2", "Tpms3",
                "OilDw", "AveOil", "InstantOil", "EndurOil", "Distance", "BatV", "DistanceDw", "TurnLeft", "TurnRight",
                "DriveSafe", "HighBeam", "LowBeam", "FootBrake", "HandBrake", "Stalls(gear)", "Ill", "WarnLed",
                "FrontFogLed", "RearFogLed", "Jqmwz", "Syyl", "Lqywd", "Jyyl", "Dqyl", "Syyw", "Zcbz", "PassengerSafe",
                "Bsqyw", "RearOpen", "AveSpeed", "DriveDis1", "DriveDis2"};
        for (int i = 0; i < n.length; i++) {
            NAMES[i] = n[i];
        }
        for (int i = VIN_FIRST; i <= VIN_LAST; i++) {
            NAMES[i] = "Vin" + (i - VIN_FIRST) + "(hidden)";
        }
        NAMES[59] = "WidthLight";
        NAMES[60] = "WiperSpeed";
        for (int i = 61; i <= 66; i++) {
            NAMES[i] = "Door" + (i - 61) + "(inferred)";
        }
        String[] t = {"OutTemp", "OilTemp", "DoorLock", "ChargeSta", "WarnSta", "ACC", "Fdjqdhsj", "Jqwd", "Ymtbxdwz",
                "Maintenance_Mileage", "Soc", "DriveMode", "RlSafetyBelt", "RmSafetyBelt", "RrSafetyBelt"};
        for (int i = 0; i < t.length; i++) {
            NAMES[67 + i] = t[i];
        }
    }

    /** The indices worth watching while shifting gears or changing mode. */
    public static final int[] KEY = {2, 3, 24, 78, 22, 23, 72, 77, 70, 14, 6, 7, 8, 9, 5, 69, 79};

    private int[] first;
    private int[] last;
    private int[] min;
    private int[] max;
    private int samples;

    public void add(int[] values) {
        if (values == null || values.length < SIZE) {
            return;
        }
        int[] v = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            v[i] = isVin(i) ? 0 : values[i];
        }
        if (first == null) {
            first = v.clone();
            min = v.clone();
            max = v.clone();
        }
        for (int i = 0; i < SIZE; i++) {
            min[i] = Math.min(min[i], v[i]);
            max[i] = Math.max(max[i], v[i]);
        }
        last = v;
        samples++;
    }

    public static boolean isVin(int i) {
        return i >= VIN_FIRST && i <= VIN_LAST;
    }

    public int samples() {
        return samples;
    }

    public int[] last() {
        return last;
    }

    /** True if the value at this index has taken more than one value since the first sample. */
    public boolean changed(int i) {
        return first != null && min[i] != max[i];
    }

    public int min(int i) {
        return min[i];
    }

    public int max(int i) {
        return max[i];
    }

    public int first(int i) {
        return first[i];
    }

    public static String csvHeader() {
        StringBuilder sb = new StringBuilder("epoch_ms");
        for (int i = 0; i < SIZE; i++) {
            if (!isVin(i)) {
                sb.append(',').append(i).append(':').append(NAMES[i]);
            }
        }
        return sb.toString();
    }

    /** One CSV row; the VIN columns are left out entirely. */
    public static String csvRow(long epochMs, int[] values) {
        StringBuilder sb = new StringBuilder(String.valueOf(epochMs));
        for (int i = 0; i < SIZE; i++) {
            if (!isVin(i)) {
                sb.append(',').append(values[i]);
            }
        }
        return sb.toString();
    }

    public String changedSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
            if (changed(i)) {
                sb.append(String.format(Locale.US, "%2d %-18s first %d  last %d  range %d..%d%n", i, NAMES[i],
                        first[i], last[i], min[i], max[i]));
            }
        }
        return sb.length() == 0 ? "(nothing has changed yet)\n" : sb.toString();
    }
}
