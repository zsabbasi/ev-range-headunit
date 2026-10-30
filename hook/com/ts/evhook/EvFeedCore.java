package com.ts.evhook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One polling step of the EV feed, with all safety brakes. No Android dependencies.
 *
 * Threading rule: step() must only ever be called on MainUI's main thread (the thread the unit's
 * own CAN pages use). File output goes through the Sink, which hands it to another thread; that
 * thread never touches the native library.
 *
 * Safety brakes (each one stops the feed for good until a human removes evfeed.tripped):
 *  - kill switch: file evfeed.disable (stops while it exists, resumes when removed)
 *  - crash-loop breaker: 3 process starts within 10 minutes
 *  - slow-poll breaker: 5 polls in a row slower than 150 ms
 *  - error breaker: 5 consecutive failures, or any linkage error
 */
public final class EvFeedCore {
    public interface Sink {
        void log(String line);

        void latest(String text);

        void status(String text);
    }

    public static final int CAN_JIMAI_FL = 356;
    public static final long POLL_MS = 2000;
    public static final long IDLE_LOG_MS = 30000;
    public static final long SLOW_MS = 150;
    public static final String HEADER =
            "epoch_ms,time,volt,amp,soc,cell_max,cell_min,motor_temp,ctrl_temp,rpm,"
                    + "speed_raw,speed_dw,bms_nlhs,bms_bsmdfk,motor_djmxdl,motor_djnjyxw,motor_sjnj,"
                    + "raw_dcdl,raw_djzs\n";

    private final ClassLoader loader;
    private final File dir;
    private final Sink sink;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private Class<?> bmsClass;
    private Class<?> motorClass;
    private Method getType;
    private Method query;
    private Method getBms;
    private Method getMotor;
    private boolean resolved;
    private boolean canTypeOk;
    private long lastTypeCheck;
    private boolean querySent;
    private int slowStreak;
    private int errorStreak;
    private boolean tripped;
    private boolean wasDisabled;
    private long lastLogMs;
    private int lastSoc = -1;

    public EvFeedCore(ClassLoader loader, File dir, Sink sink) {
        this.loader = loader;
        this.dir = dir;
        this.sink = sink;
    }

    public boolean isTripped() {
        return tripped;
    }

    private File trippedFile() {
        return new File(dir, "evfeed.tripped");
    }

    private File disableFile() {
        return new File(dir, "evfeed.disable");
    }

    /** Stops the feed permanently (until evfeed.tripped is deleted and the process restarts). */
    public void trip(String reason) {
        tripped = true;
        sink.status("TRIPPED: " + reason);
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            FileOutputStream o = new FileOutputStream(trippedFile(), false);
            try {
                o.write((new Date().toString() + "  " + reason + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Crash-loop breaker. Call once per process start, from a non-main, non-JNI thread.
     * Appends the start time to evfeed_starts.txt and trips if there were 3 starts in 10 minutes.
     */
    public void recordStart(long nowMs) {
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, "evfeed_starts.txt");
            List<Long> keep = new ArrayList<Long>();
            if (f.exists()) {
                RandomAccessFile r = new RandomAccessFile(f, "r");
                try {
                    byte[] b = new byte[(int) Math.min(r.length(), 4096)];
                    r.readFully(b);
                    for (String s : new String(b, "UTF-8").split("\n")) {
                        try {
                            long t = Long.parseLong(s.trim());
                            if (nowMs - t < 600000L && t <= nowMs) {
                                keep.add(Long.valueOf(t));
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } finally {
                    r.close();
                }
            }
            keep.add(Long.valueOf(nowMs));
            StringBuilder sb = new StringBuilder();
            for (Long t : keep) {
                sb.append(t).append('\n');
            }
            FileOutputStream o = new FileOutputStream(f, false);
            try {
                o.write(sb.toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
            if (keep.size() >= 3) {
                trip("crash-loop: " + keep.size() + " process starts within 10 minutes");
            }
        } catch (Throwable ignored) {
        }
    }

    private void resolve() throws Exception {
        Class<?> jni = Class.forName("com.lgb.canmodule.CanJni", false, loader);
        Class<?> info = Class.forName("com.lgb.canmodule.CanDataInfo", false, loader);
        for (Class<?> c : info.getDeclaredClasses()) {
            if (c.getSimpleName().equals("JiMaiFl_BmsInfo")) {
                bmsClass = c;
            } else if (c.getSimpleName().equals("JiMaiFl_MotorInfo")) {
                motorClass = c;
            }
        }
        if (bmsClass == null || motorClass == null) {
            throw new IllegalStateException("CanDataInfo structures not found");
        }
        getType = jni.getMethod("GetCanType");
        query = jni.getMethod("CanCmdQueryData", int.class, int.class, int.class);
        getBms = jni.getMethod("JiMaiFlGetBmsInfo", bmsClass);
        getMotor = jni.getMethod("JiMaiFlGetMotorInfo", motorClass);
        resolved = true;
        sink.status("resolved CanJni via " + jni.getClassLoader());
    }

    private static int geti(Object o, String field) throws Exception {
        Field f = o.getClass().getField(field);
        return f.getInt(o);
    }

    /**
     * One poll. Returns the delay in ms until the next one, or -1 to stop permanently.
     * Reads the values requested by the PREVIOUS step, then requests fresh ones, so the main
     * thread never sleeps.
     */
    public long step(long nowMs) {
        if (tripped) {
            return -1;
        }
        try {
            if (trippedFile().exists()) {
                tripped = true;
                sink.status("evfeed.tripped present: feed stays off");
                return -1;
            }
            if (disableFile().exists()) {
                if (!wasDisabled) {
                    sink.status("evfeed.disable present: paused");
                    wasDisabled = true;
                }
                querySent = false;
                return 10000;
            }
            if (wasDisabled) {
                sink.status("evfeed.disable removed: resuming");
                wasDisabled = false;
            }

            long t0 = System.nanoTime();
            if (!resolved) {
                resolve();
            }
            if (!canTypeOk || nowMs - lastTypeCheck > 60000) {
                int type = ((Integer) getType.invoke(null)).intValue();
                lastTypeCheck = nowMs;
                canTypeOk = type == CAN_JIMAI_FL;
                if (!canTypeOk) {
                    sink.status("CAN type " + type + " (not " + CAN_JIMAI_FL + "): idle");
                    querySent = false;
                    return 30000;
                }
            }

            if (querySent) {
                Object bms = bmsClass.newInstance();
                Object motor = motorClass.newInstance();
                getBms.invoke(null, bms);
                getMotor.invoke(null, motor);
                if (geti(bms, "UpdateOnce") != 0 && geti(motor, "UpdateOnce") != 0) {
                    record(nowMs, bms, motor);
                }
            }
            query.invoke(null, 52, 0, 0);
            query.invoke(null, 54, 0, 0);
            querySent = true;

            long ms = (System.nanoTime() - t0) / 1000000L;
            if (ms > SLOW_MS) {
                slowStreak++;
                if (slowStreak >= 5) {
                    trip("slow-poll: 5 polls over " + SLOW_MS + " ms (last " + ms + " ms)");
                    return -1;
                }
            } else {
                slowStreak = 0;
            }
            errorStreak = 0;
            return POLL_MS;
        } catch (Throwable th) {
            Throwable c = th.getCause() != null ? th.getCause() : th;
            errorStreak++;
            sink.status("poll error " + errorStreak + ": " + c);
            if (c instanceof LinkageError || errorStreak >= 5) {
                trip("errors: " + c);
                return -1;
            }
            return 5000;
        }
    }

    private void record(long nowMs, Object bms, Object motor) throws Exception {
        int dcdl = geti(bms, "Dcdl");
        int djzs = geti(motor, "Djzs");
        int amp = dcdl - 500;
        int rpm = djzs - 15000;
        int soc = geti(bms, "Soc");
        boolean active = Math.abs(amp) >= 1 || Math.abs(rpm) >= 30;
        boolean due = active || soc != lastSoc || nowMs - lastLogMs >= IDLE_LOG_MS;
        if (!due) {
            return;
        }
        lastSoc = soc;
        lastLogMs = nowMs;
        String line = nowMs + "," + fmt.format(new Date(nowMs))
                + "," + geti(bms, "Dcdy")
                + "," + amp
                + "," + soc
                + "," + (geti(bms, "CellTempMax") - 40)
                + "," + (geti(bms, "CellTempMin") - 40)
                + "," + (geti(motor, "Djwd") - 40)
                + "," + (geti(motor, "Djkzqwd") - 40)
                + "," + rpm
                + ",0,0"
                + "," + geti(bms, "Nlhs")
                + "," + geti(bms, "Bsmdfk")
                + "," + geti(motor, "Djmxdl")
                + "," + geti(motor, "Djnjyxw")
                + "," + geti(motor, "Sjnj")
                + "," + dcdl + "," + djzs + "\n";
        sink.log(line);
        sink.latest(HEADER + line);
    }
}
