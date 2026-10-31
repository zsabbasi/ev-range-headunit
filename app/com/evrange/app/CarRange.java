package com.evrange.app;

/**
 * The car's own range figure and drive mode, from the unit's car-info array (ICarInfoService.requestCarBaseInfo).
 *
 * Observed on the test car (see docs): index 34 ("Syyw") carries the battery level in percent and index 13
 * ("EndurOil") the car's own remaining range in km. The range is a constant per mode times the battery level:
 * about 2.43 km per percent in D (136 km at 56 %, about 243 km full) and about 2.21 km per percent in S
 * (124 km at 56 %, about 221 km full). The gear, brake, speed and tyre fields are not filled on this car.
 */
public final class CarRange {
    public static final int MODE_UNKNOWN = -1;
    public static final int MODE_D = 0;
    public static final int MODE_S = 1;

    public static final int IDX_ENDURANCE = 13;
    public static final int IDX_SOC = 34;
    public static final int MIN_ARRAY = 35;

    /** km per battery percent, as observed. */
    public static final double KM_PER_PCT_D = 136.0 / 56.0;
    public static final double KM_PER_PCT_S = 124.0 / 56.0;
    /** Halfway between the two, used to tell the modes apart. */
    public static final double THRESHOLD = (KM_PER_PCT_D + KM_PER_PCT_S) / 2.0;
    /** Below this battery level the integer rounding of the range blurs the two modes. */
    public static final int MIN_SOC = 10;

    private CarRange() {
    }

    /** The car's own range in km, or -1 if the array does not carry a usable value. */
    public static int rangeKm(int[] base) {
        if (base == null || base.length < MIN_ARRAY) {
            return -1;
        }
        int soc = base[IDX_SOC];
        int km = base[IDX_ENDURANCE];
        return soc >= 1 && soc <= 100 && km > 0 && km < 1000 ? km : -1;
    }

    public static int socPct(int[] base) {
        if (base == null || base.length < MIN_ARRAY) {
            return -1;
        }
        int soc = base[IDX_SOC];
        return soc >= 1 && soc <= 100 ? soc : -1;
    }

    /** D or S from the range-to-battery ratio; unknown when the battery is low or the ratio is implausible. */
    public static int modeFrom(int rangeKm, int socPct) {
        if (rangeKm <= 0 || socPct < MIN_SOC || socPct > 100) {
            return MODE_UNKNOWN;
        }
        double ratio = rangeKm / (double) socPct;
        if (ratio < 2.0 || ratio > 2.7) {
            return MODE_UNKNOWN;
        }
        return ratio >= THRESHOLD ? MODE_D : MODE_S;
    }

    public static int mode(int[] base) {
        return modeFrom(rangeKm(base), socPct(base));
    }

    public static String label(int mode) {
        return mode == MODE_D ? "D" : mode == MODE_S ? "S" : "?";
    }
}
