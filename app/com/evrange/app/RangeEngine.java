package com.evrange.app;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Range engine (no Android dependencies, testable on a laptop).
 * Integrates energy (V x I) and distance, keeps a rolling window of the last km,
 * per-day totals, and estimates range from recent consumption.
 */
public final class RangeEngine {
    public static final class Day {
        public double km;
        public double kwhNet;
        public double kwhRegen;

        public double kwhPer100() {
            return km < 0.5 ? Double.NaN : kwhNet / km * 100.0;
        }
    }

    private static final double MAX_KEEP_KM = 100.0;
    private static final double DEADBAND_RPM = 20.0;

    // ---- configuration ----
    public double capacityKwh = 19.14;

    /** Where speed and distance come from: AUTO = GPS while it agrees with the motor rpm, RPM = motor rpm only, GPS = GPS when there is a fix. */
    public static final int SRC_AUTO = 0, SRC_RPM = 1, SRC_GPS = 2;
    public int speedSource = SRC_AUTO;
    public double wltpKm = 0.0;
    public double reserveKm = 15.0;
    /** Calibrated against GPS: 31.0 km/h at 3000 motor rpm (R2 0.987 over 658 samples, flat across speed bands). */
    public double kmPerMotorRev = 0.0001722;
    public double defaultKwhPer100 = 14.0;
    public double windowKm = 25.0;
    public double minWindowKm = 1.0;
    public double bucketKm = 0.1;
    public long maxGapMs = 8000;

    // ---- state ----
    private final ArrayDeque<double[]> window = new ArrayDeque<double[]>(); // {km, kwh}
    private double curKm;
    private double curKwh;
    private long lastMs = -1;
    private double lastKw;
    private double lastAbsRpm;
    private int soc;
    private int lastRpm;
    private double tripKm;
    private double tripNet;
    private double tripRegen;
    private double tripRevs;
    private double allKm;
    private double allNet;
    /** Distance and net energy per drive mode (index 0 = D, 1 = S), for samples taken while the mode was known. */
    private final double[] modeKm = new double[2];
    private final double[] modeNet = new double[2];
    private final TreeMap<Long, Day> days = new TreeMap<Long, Day>();

    /** Clears everything (used when switching data source or starting demo mode). */
    public void resetAll() {
        window.clear();
        days.clear();
        curKm = 0;
        curKwh = 0;
        lastMs = -1;
        lastKw = 0;
        lastAbsRpm = 0;
        soc = 0;
        lastRpm = 0;
        allKm = 0;
        allNet = 0;
        modeKm[0] = modeKm[1] = 0;
        modeNet[0] = modeNet[1] = 0;
        resetTrip();
    }

    public void resetTrip() {
        tripKm = 0;
        tripNet = 0;
        tripRegen = 0;
        tripRevs = 0;
    }

    private static long dayIndex(long ms) {
        long off = TimeZone.getDefault().getOffset(ms);
        long x = ms + off;
        return x >= 0 ? x / 86400000L : -((-x + 86399999L) / 86400000L);
    }

    /** gpsSpeedMs: vehicle speed in m/s from GPS, or negative if unavailable. Drive mode unknown. */
    public void addSample(long tMs, int volt, int amp, int socPct, int rpm, double gpsSpeedMs) {
        addSample(tMs, volt, amp, socPct, rpm, gpsSpeedMs, -1);
    }

    /** As above, with the drive mode known at the time of the sample: 0 = D, 1 = S, anything else unknown. */
    public void addSample(long tMs, int volt, int amp, int socPct, int rpm, double gpsSpeedMs, int mode) {
        soc = socPct;
        lastRpm = rpm;
        double kwNow = volt * (double) amp / 1000.0;
        // dead-band: sensor flicker around standstill must not add phantom distance
        double absRpmNow = Math.abs(rpm) < DEADBAND_RPM ? 0 : Math.abs(rpm);
        if (lastMs >= 0) {
            long dt = tMs - lastMs;
            if (dt > 0 && dt <= maxGapMs) {
                // trapezoidal integration: average of the previous and the current reading
                double kwh = (lastKw + kwNow) / 2.0 * dt / 3.6e6;
                if (kwh < 0 && lastAbsRpm == 0 && absRpmNow == 0) {
                    kwh = 0; // energy flowing in while standing still is charging, not regen: not driving consumption
                }
                double revs = (lastAbsRpm + absRpmNow) / 2.0 * dt / 60000.0;
                double km = gpsSpeedMs >= 0 && speedSource != SRC_RPM ? gpsSpeedMs * dt / 1e6 : revs * kmPerMotorRev;
                tripKm += km;
                tripNet += kwh;
                tripRevs += revs;
                if (kwh < 0) {
                    tripRegen += -kwh;
                }
                allKm += km;
                allNet += kwh;
                if (mode == 0 || mode == 1) {
                    modeKm[mode] += km;
                    modeNet[mode] += kwh;
                }
                Long key = Long.valueOf(dayIndex(tMs));
                Day d = days.get(key);
                if (d == null) {
                    d = new Day();
                    days.put(key, d);
                }
                d.km += km;
                d.kwhNet += kwh;
                if (kwh < 0) {
                    d.kwhRegen += -kwh;
                }
                curKm += km;
                curKwh += kwh;
                if (curKm >= bucketKm) {
                    window.addLast(new double[] {curKm, curKwh});
                    curKm = 0;
                    curKwh = 0;
                    trimWindow();
                }
            }
        }
        lastMs = tMs;
        lastKw = kwNow;
        lastAbsRpm = absRpmNow;
    }

    private void trimWindow() {
        double km = 0;
        for (double[] b : window) {
            km += b[0];
        }
        while (window.size() > 1 && km - window.peekFirst()[0] >= MAX_KEEP_KM) {
            km -= window.pollFirst()[0];
        }
    }

    // ---- results ----
    /** kWh/100 km over the most recent `km` kilometres, NaN if not enough distance yet. */
    public double consumption(double km) {
        double dist = curKm;
        double kwh = curKwh;
        Iterator<double[]> it = window.descendingIterator();
        while (it.hasNext() && dist < km) {
            double[] b = it.next();
            dist += b[0];
            kwh += b[1];
        }
        return dist < minWindowKm ? Double.NaN : kwh / dist * 100.0;
    }

    public double coveredKm(double km) {
        double dist = curKm;
        Iterator<double[]> it = window.descendingIterator();
        while (it.hasNext() && dist < km) {
            dist += it.next()[0];
        }
        return Math.min(dist, km);
    }

    public double tripKwhPer100() {
        return tripKm < minWindowKm ? Double.NaN : tripNet / tripKm * 100.0;
    }

    public double allTimeKwhPer100() {
        return allKm < minWindowKm ? Double.NaN : allNet / allKm * 100.0;
    }

    public double remainingKwh() {
        return capacityKwh * soc / 100.0;
    }

    public double bestKwhPer100() {
        double w = consumption(windowKm);
        if (!Double.isNaN(w) && w > 1.0) {
            return w;
        }
        double t = tripKwhPer100();
        if (!Double.isNaN(t) && t > 1.0) {
            return t;
        }
        double a = allTimeKwhPer100();
        if (!Double.isNaN(a) && a > 1.0) {
            return a;
        }
        return defaultKwhPer100;
    }

    public String bestSource() {
        double w = consumption(windowKm);
        if (!Double.isNaN(w) && w > 1.0) {
            return "last " + Math.round(coveredKm(windowKm)) + " km";
        }
        double t = tripKwhPer100();
        if (!Double.isNaN(t) && t > 1.0) {
            return "this trip";
        }
        double a = allTimeKwhPer100();
        if (!Double.isNaN(a) && a > 1.0) {
            return "all-time average";
        }
        return "default " + Math.round(defaultKwhPer100) + " kWh/100 km";
    }

    public double rangeKm() {
        return remainingKwh() / bestKwhPer100() * 100.0;
    }

    public double wltpRangeKm() {
        return wltpKm > 0 ? wltpKm * soc / 100.0 : Double.NaN;
    }

    /**
     * Current vehicle speed in km/h. The motor is geared to the wheels with no clutch, so rpm = 0 means
     * stopped. Otherwise a fresh GPS speed is preferred; without one the rpm is converted with the
     * calibrated ratio.
     *
     * @param gpsKmh fresh GPS speed in km/h, or a negative number if there is no fix
     */
    public double speedKmh(double gpsKmh) {
        double rpm = Math.abs(lastRpm);
        if (rpm < DEADBAND_RPM) {
            return 0.0;
        }
        return speedUsesGps(gpsKmh) ? gpsKmh : rpm * kmPerMotorRev * 60.0;
    }

    /**
     * True when a fresh GPS speed is used: it must agree with the rpm-derived speed (within 20 % or 5 km/h,
     * whichever is larger). While the car rolls the motor is geared to the wheels, so a GPS value that
     * contradicts the rpm is a GPS glitch and is ignored.
     */
    public boolean speedUsesGps(double gpsKmh) {
        double rpm = Math.abs(lastRpm);
        if (gpsKmh < 0 || rpm < DEADBAND_RPM || speedSource == SRC_RPM) {
            return false;
        }
        if (speedSource == SRC_GPS) {
            return true;
        }
        double fromRpm = rpm * kmPerMotorRev * 60.0;
        return Math.abs(gpsKmh - fromRpm) <= Math.max(5.0, 0.20 * fromRpm);
    }

    public double liveKw() {
        return lastKw;
    }

    /** True while the car is standing still with energy flowing into the battery: plugged in and charging. */
    public boolean charging() {
        return lastKw < -0.3 && lastAbsRpm == 0;
    }

    public int soc() {
        return soc;
    }

    public int rpm() {
        return lastRpm;
    }

    public double tripKm() {
        return tripKm;
    }

    public double tripKwhNet() {
        return tripNet;
    }

    public double tripRegenKwh() {
        return tripRegen;
    }

    public double tripRevs() {
        return tripRevs;
    }

    public long lastSampleMs() {
        return lastMs;
    }

    public TreeMap<Long, Day> days() {
        return days;
    }

    public static long dayStartMs(long dayIdx) {
        long guess = dayIdx * 86400000L;
        return guess - TimeZone.getDefault().getOffset(guess);
    }

    public static long todayIndex() {
        return dayIndex(System.currentTimeMillis());
    }

    /** kWh/100 km in drive mode 0 (D) or 1 (S), NaN until at least a kilometre has been driven in it. */
    public double modeKwhPer100(int mode) {
        return (mode != 0 && mode != 1) || modeKm[mode] < minWindowKm ? Double.NaN : modeNet[mode] / modeKm[mode] * 100.0;
    }

    public double modeKm(int mode) {
        return mode == 0 || mode == 1 ? modeKm[mode] : 0.0;
    }

    // ---- persistence ----
    public String save() {
        StringBuilder sb = new StringBuilder();
        sb.append("s,").append(lastMs).append(',').append(soc).append(',').append(tripKm).append(',')
                .append(tripNet).append(',').append(tripRegen).append(',').append(tripRevs).append(',')
                .append(allKm).append(',').append(allNet).append(',').append(curKm).append(',')
                .append(curKwh).append('\n');
        sb.append("k,").append(modeKm[0]).append(',').append(modeNet[0]).append(',').append(modeKm[1]).append(',')
                .append(modeNet[1]).append('\n');
        for (double[] b : window) {
            sb.append("w,").append(b[0]).append(',').append(b[1]).append('\n');
        }
        for (Map.Entry<Long, Day> e : days.entrySet()) {
            Day d = e.getValue();
            sb.append("d,").append(e.getKey()).append(',').append(d.km).append(',').append(d.kwhNet)
                    .append(',').append(d.kwhRegen).append('\n');
        }
        return sb.toString();
    }

    public void load(String text) {
        window.clear();
        days.clear();
        modeKm[0] = modeKm[1] = 0;
        modeNet[0] = modeNet[1] = 0;
        for (String line : text.split("\n")) {
            String[] c = line.split(",");
            try {
                if (c[0].equals("s") && c.length >= 11) {
                    lastMs = Long.parseLong(c[1]);
                    soc = Integer.parseInt(c[2]);
                    tripKm = Double.parseDouble(c[3]);
                    tripNet = Double.parseDouble(c[4]);
                    tripRegen = Double.parseDouble(c[5]);
                    tripRevs = Double.parseDouble(c[6]);
                    allKm = Double.parseDouble(c[7]);
                    allNet = Double.parseDouble(c[8]);
                    curKm = Double.parseDouble(c[9]);
                    curKwh = Double.parseDouble(c[10]);
                } else if (c[0].equals("k") && c.length >= 5) {
                    modeKm[0] = Double.parseDouble(c[1]);
                    modeNet[0] = Double.parseDouble(c[2]);
                    modeKm[1] = Double.parseDouble(c[3]);
                    modeNet[1] = Double.parseDouble(c[4]);
                } else if (c[0].equals("w") && c.length >= 3) {
                    window.addLast(new double[] {Double.parseDouble(c[1]), Double.parseDouble(c[2])});
                } else if (c[0].equals("d") && c.length >= 5) {
                    Day d = new Day();
                    d.km = Double.parseDouble(c[2]);
                    d.kwhNet = Double.parseDouble(c[3]);
                    d.kwhRegen = Double.parseDouble(c[4]);
                    days.put(Long.valueOf(c[1]), d);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
