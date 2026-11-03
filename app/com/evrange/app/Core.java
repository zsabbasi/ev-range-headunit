package com.evrange.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Owns the engine, the log reader and the GPS, and keeps ticking whether or not the screen is
 * showing (the foreground TrackService keeps the process alive). The activity only displays.
 * Everything here runs on the main thread.
 */
public final class Core {
    public static final String LIVE_PATH = "/storage/emulated/0/TsCarInfo/ev_log.csv";
    public static final String DEMO_PATH = "/storage/emulated/0/TsCarInfo/ev_demo.csv";
    /** The feed writes at most one line per 30 s while idle, so only call data stale after this. */
    public static final long STALE_SEC = 45;

    public static final RangeEngine engine = new RangeEngine();

    private static Context app;
    private static SharedPreferences prefs;
    private static LogReader reader;
    private static GpsSource gps;
    private static CarInfoClient car;
    private static long lastCarLogMs;
    private static int lastCarRange = -2;
    private static int lastCarMode = -2;
    private static Handler handler;
    private static boolean running;
    private static boolean demo;
    private static long lastSaveMs;

    public static boolean stale;
    private static boolean appVisible;
    public static long ageSec;
    public static String mode = "";

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 1000);
        }
    };

    private Core() {
    }

    public static boolean isDemo() {
        return demo;
    }

    public static boolean gpsFix() {
        return gps != null && gps.hasFix();
    }

    /** The car's own range in km (live mode only), or -1 if it is not available. */
    public static int carRangeKm() {
        return demo || car == null ? -1 : car.rangeKm();
    }

    /** CarRange.MODE_D, MODE_S or MODE_UNKNOWN, worked out from the car's own range and battery level. */
    public static int driveMode() {
        return demo || car == null ? CarRange.MODE_UNKNOWN : car.mode();
    }

    /** True while the app's own screen is in front: the floating bubble stays hidden then. */
    public static void setAppVisible(boolean visible) {
        appVisible = visible;
    }

    /** Asks the data feed to stop (true) or restore (false) the car's speed-linked volume. Needs feed v3. */
    public static void setSpeedVolStop(boolean stop) {
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            FileOutputStream out = new FileOutputStream(new File(dir, SpeedVol.REQUEST_NAME), false);
            try {
                out.write(SpeedVol.requestText(stop).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Fresh GPS speed in km/h, or -1. */
    public static double gpsKmh() {
        return gps == null ? -1 : gps.currentKmh();
    }

    public static SharedPreferences prefs() {
        return prefs;
    }

    /** Idempotent. demoOverride: null = keep saved mode. */
    public static synchronized void start(Context ctx, Boolean demoOverride) {
        if (app == null) {
            app = ctx.getApplicationContext();
            prefs = app.getSharedPreferences("evrange", Context.MODE_PRIVATE);
            handler = new Handler(Looper.getMainLooper());
            demo = prefs.getBoolean("demo", false);
        }
        if (demoOverride != null && demoOverride.booleanValue() != demo) {
            demo = demoOverride.booleanValue();
            prefs.edit().putBoolean("demo", demo).apply();
            running = false;
            reader = null;
        }
        if (!running) {
            running = true;
            // History version 3 (2 was the rpm calibration): charging energy used to be counted as negative
            // consumption. Drop the saved history once and rebuild it from the log with the current rules.
            if (!demo && prefs.getInt("histver", 0) < 3) {
                stateFile().delete();
                prefs.edit().putLong("pos", 0).putInt("histver", 3).apply();
            }
            applyConfig();
            loadState();
            reader = new LogReader(demo ? DEMO_PATH : LIVE_PATH, demo ? 0 : prefs.getLong("pos", 0));
            if (gps == null) {
                gps = new GpsSource(app);
            }
            gps.start();
            if (car == null) {
                car = new CarInfoClient(app);
            }
            car.start();
            handler.removeCallbacks(TICK);
            handler.post(TICK);
        }
    }

    public static void applyConfig() {
        // 19.14 kWh is this car's pack; a saved 19.0 is the old default, not a choice, and is replaced
        double cap = Double.longBitsToDouble(prefs.getLong("cap", Double.doubleToLongBits(19.14)));
        engine.capacityKwh = Math.abs(cap - 19.0) < 1e-9 ? 19.14 : cap;
        // motor rpm is the default speed source: it reacts faster than GPS, which lags
        // 240 km: the rated range the head unit's own screen shows for this car; the app's "car display"
        // row is kept consistent with that screen (the figure in some paperwork, 220 km, is not used)
        engine.wltpKm = Double.longBitsToDouble(prefs.getLong("wltp", Double.doubleToLongBits(240.0)));
        engine.reserveKm = Double.longBitsToDouble(prefs.getLong("reserve", Double.doubleToLongBits(15.0)));
        // key "kmrev2": the old "kmrev" held the uncalibrated 0.000211 default and is ignored
        engine.kmPerMotorRev = Double.longBitsToDouble(prefs.getLong("kmrev2", Double.doubleToLongBits(0.0001722)));
        engine.windowKm = prefs.getInt("window", 25);
        engine.speedSource = prefs.getInt("speedsrc", RangeEngine.SRC_RPM);
    }

    private static File stateFile() {
        return new File(app.getFilesDir(), "state.txt");
    }

    private static void loadState() {
        engine.resetAll();
        if (demo) {
            return; // demo always replays from scratch
        }
        try {
            File f = stateFile();
            if (f.exists()) {
                byte[] buf = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                try {
                    int n = 0;
                    while (n < buf.length) {
                        int k = in.read(buf, n, buf.length - n);
                        if (k < 0) {
                            break;
                        }
                        n += k;
                    }
                } finally {
                    in.close();
                }
                engine.load(new String(buf, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void saveState() {
        lastSaveMs = System.currentTimeMillis();
        if (demo) {
            return; // never persist demo data
        }
        try {
            FileOutputStream out = new FileOutputStream(stateFile(), false);
            try {
                out.write(engine.save().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (reader != null) {
                prefs.edit().putLong("pos", reader.position()).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Calibration log: rpm next to GPS speed, altitude and accuracy (no coordinates). */
    private static void appendCalibration(String text) {
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "gps_rpm_log.csv");
            boolean fresh = !f.exists();
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                if (fresh) {
                    out.write("epoch_ms,time,rpm,gps_kmh,gps_alt_m,gps_acc_m,amp,volt\n".getBytes("UTF-8"));
                }
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void step() {
        List<String> lines = reader.poll();
        StringBuilder cal = new StringBuilder();
        for (String line : lines) {
            String[] c = line.split(",");
            if (c.length < 10) {
                continue;
            }
            try {
                long ms = Long.parseLong(c[0]);
                if (!demo && engine.lastSampleMs() > 0 && ms <= engine.lastSampleMs()) {
                    continue; // already counted
                }
                int rpm = Integer.parseInt(c[9]);
                GpsSource.Fix fix = demo ? null : gps.fixFor(ms);
                // the car's drive mode applies only to samples taken just now, not to a backlog read from the log
                int dm = !demo && car != null && Math.abs(System.currentTimeMillis() - ms) <= 6000
                        ? car.mode() : CarRange.MODE_UNKNOWN;
                engine.addSample(ms, Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                        rpm, fix == null ? -1 : fix.speedMs, dm);
                if (fix != null && (Math.abs(rpm) > 20 || fix.speedMs > 0.5)) {
                    cal.append(ms).append(',').append(c[1]).append(',').append(rpm).append(',')
                            .append(String.format(java.util.Locale.US, "%.2f", fix.speedMs * 3.6)).append(',')
                            .append(Double.isNaN(fix.altM) ? "" : String.format(java.util.Locale.US, "%.1f", fix.altM))
                            .append(',')
                            .append(Double.isNaN(fix.accM) ? "" : String.format(java.util.Locale.US, "%.1f", fix.accM))
                            .append(',').append(c[3]).append(',').append(c[2]).append('\n');
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (cal.length() > 0) {
            appendCalibration(cal.toString());
        }
        long now = System.currentTimeMillis();
        logCarInfo(now);
        ageSec = engine.lastSampleMs() <= 0 ? 999999 : (now - engine.lastSampleMs()) / 1000;
        stale = !demo && ageSec > STALE_SEC;
        mode = demo ? "DEMO (recorded drive)" : "LIVE";
        Bubble.tick(app, prefs, engine, stale, appVisible, demo, driveMode());
        if (now - lastSaveMs > 30000) {
            saveState();
        }
    }

    /** Records the car's own range and the mode we infer, when they change and at least once a minute. */
    private static void logCarInfo(long now) {
        if (demo || car == null) {
            return;
        }
        int r = car.rangeKm();
        if (r < 0) {
            return;
        }
        int m = car.mode();
        if (r == lastCarRange && m == lastCarMode && now - lastCarLogMs < 60000) {
            return;
        }
        lastCarRange = r;
        lastCarMode = m;
        lastCarLogMs = now;
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "carinfo_log.csv");
            boolean fresh = !f.exists();
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                if (fresh) {
                    out.write("epoch_ms,range_km,soc_pct,mode,speed_kmh\n".getBytes("UTF-8"));
                }
                double v = engine.speedKmh(gps == null ? -1 : gps.currentKmh());
                out.write((now + "," + r + "," + car.socPct() + "," + CarRange.label(m) + ","
                        + String.format(java.util.Locale.US, "%.0f", v) + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Called from Settings. Resets and reloads when switching between demo and live. */
    public static void setDemo(boolean on) {
        if (on == demo) {
            return;
        }
        saveState();
        start(app, Boolean.valueOf(on));
    }
}
