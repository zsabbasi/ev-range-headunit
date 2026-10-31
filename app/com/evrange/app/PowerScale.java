package com.evrange.app;

/**
 * Scale of the live power bar. The limits are the real peaks measured on this car (hard acceleration and
 * the strongest braking in tools/power_peaks.py output), plus an allowance so a slightly higher reading
 * (for example a full battery, which has a higher voltage) still fits on the bar.
 *
 * Measured on the test car: discharge plateau 35.1 to 35.7 kW at 112 to 114 A (a current limit), regen
 * peak 11.1 kW with the next strongest around 9.1 to 9.5 kW.
 */
public final class PowerScale {
    public static final double PEAK_DISCHARGE_KW = 35.7;
    public static final double PEAK_REGEN_KW = 11.1;
    /** Headroom added to each end of the bar. */
    public static final double ALLOWANCE = 0.15;
    /** From this share of the discharge peak the bar turns red (close to the car's own limit). */
    public static final double WARN_SHARE = 0.85;

    /** Bar ends in kW, rounded up to whole kW. Left is regen (negative), right is discharge. */
    public static final double MAX_KW = Math.ceil(PEAK_DISCHARGE_KW * (1.0 + ALLOWANCE));
    public static final double MIN_KW = -Math.ceil(PEAK_REGEN_KW * (1.0 + ALLOWANCE));

    public static final int REGEN = 0;
    public static final int NORMAL = 1;
    public static final int HIGH = 2;

    private PowerScale() {
    }

    /** Position along the bar, 0 at the regen end and 1 at the discharge end, clamped. */
    public static double fraction(double kw) {
        double f = (kw - MIN_KW) / (MAX_KW - MIN_KW);
        return Math.max(0.0, Math.min(1.0, f));
    }

    public static double zeroFraction() {
        return fraction(0.0);
    }

    public static int level(double kw) {
        if (kw < -0.05) {
            return REGEN;
        }
        return kw >= WARN_SHARE * PEAK_DISCHARGE_KW ? HIGH : NORMAL;
    }
}
