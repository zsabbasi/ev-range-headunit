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
 * The air-conditioning state is only read and logged (acLog). One setting can be written: the unit's speed-linked volume (setting 70, see speedVol()). It only follows a
 * request file with exactly 0 or 1, at most 6 changes an hour, and its failures never stop the feed.
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

        /** The state of the car's speed-linked volume setting (key=value lines), at most every 30 s or on change. */
        default void speedVol(String text) {
        }

        /** One line of the air-conditioning log (see AC_HEADER), when the state changes and every minute. */
        default void ac(String line) {
        }
    }

    public static final int CAN_JIMAI_FL = 356;
    public static final long POLL_MS = 2000;
    public static final long IDLE_LOG_MS = 30000;
    public static final long SLOW_MS = 150;
    /** The unit's setting number for the car's speed-linked volume: 0 = boost applied, anything else = off. */
    public static final int SPEEDVOL_SETTING = 70;
    public static final long VOL_CHECK_MS = 10000;
    public static final long VOL_REPORT_MS = 30000;
    public static final int VOL_MAX_CHANGES_PER_HOUR = 6;
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

    private final File[] volRequestFiles;
    private Method ftGet;
    private Method ftSet;
    private boolean volUnavailable;
    private boolean volWriteBlocked;
    private long lastVolCheck = -VOL_CHECK_MS;
    private long lastVolReport;
    private String lastVolText = "";
    private final List<Long> volChanges = new ArrayList<Long>();

    public EvFeedCore(ClassLoader loader, File dir, Sink sink) {
        this(loader, dir, sink, new File[] {new File(dir, "speedvol_request.txt")});
    }

    /** volRequestFiles: files that may hold the wanted speed-volume state, the first existing one is used. */
    public EvFeedCore(ClassLoader loader, File dir, Sink sink, File[] volRequestFiles) {
        this.loader = loader;
        this.dir = dir;
        this.sink = sink;
        this.volRequestFiles = volRequestFiles;
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

    /** Once a log is over maxBytes it becomes the single old copy (name.1.ext) and a new file starts. Never throws. */
    public static boolean rotateIfBig(File f, long maxBytes) {
        try {
            if (f.exists() && f.length() > maxBytes) {
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                File old = new File(f.getParentFile(), dot > 0 ? n.substring(0, dot) + ".1" + n.substring(dot) : n + ".1");
                if (old.exists()) {
                    old.delete();
                }
                return f.renameTo(old);
            }
        } catch (Throwable ignored) {
        }
        return false;
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
            speedVol(nowMs);
            acLog(nowMs);
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



    // ---- air conditioning state (read-only) ----

    private static final String[] AC_INT_FIELDS = {"PWR", "fgAC", "fgACMax", "fgAutoAC", "fgAutoMode", "fgInnerLoop",
            "fgLoopDisable", "fgDFBL", "fgHeat", "fgPTC", "fgEco", "fgUpWind", "fgParallelWind", "fgDownWind",
            "fgForeWindMode", "nWindValue", "nLeftTemp", "nRightTemp", "nInTemp", "fgAcErr1"};
    private static final String[] AC_STRING_FIELDS = {"szLtTemp", "szRtTemp"};
    public static final long AC_HEARTBEAT_MS = 60000;
    public static final String AC_HEADER = "epoch_ms,time,pwr,ac,ac_max,auto_ac,auto_mode,inner_loop,loop_disable,"
            + "defrost,heat,ptc,eco,wind_up,wind_parallel,wind_down,wind_fore,fan,left_temp,right_temp,in_temp,err1,"
            + "lt_text,rt_text\n";

    private Class<?> acClass;
    private Method getAc;
    private boolean acUnavailable;
    private String lastAcState = null;
    private long lastAcLogMs;

    private static String gets(Object o, String field) throws Exception {
        Object v = o.getClass().getField(field).get(o);
        return v == null ? "" : v.toString().replace(',', ' ').replace('\n', ' ').trim();
    }

    /**
     * Logs the car's air-conditioning state (including the inner-loop / recirculation flag) when it changes and
     * at least once a minute. Read-only: GetACInfo returns what the unit has already received, exactly as the
     * unit's own AC screen reads it; nothing is sent to the car. Any failure switches only this logging off.
     */
    private void acLog(long nowMs) {
        if (acUnavailable) {
            return;
        }
        try {
            if (getAc == null) {
                Class<?> jni = Class.forName("com.lgb.canmodule.CanJni", false, loader);
                Class<?> info = Class.forName("com.lgb.canmodule.CanDataInfo", false, loader);
                for (Class<?> c : info.getDeclaredClasses()) {
                    if (c.getSimpleName().equals("CAN_ACInfo")) {
                        acClass = c;
                    }
                }
                if (acClass == null) {
                    throw new IllegalStateException("CAN_ACInfo not found");
                }
                getAc = jni.getMethod("GetACInfo", acClass);
            }
            Object ac = acClass.newInstance();
            getAc.invoke(null, ac);
            StringBuilder state = new StringBuilder();
            for (String f : AC_INT_FIELDS) {
                state.append(',').append(geti(ac, f));
            }
            for (String f : AC_STRING_FIELDS) {
                state.append(',').append(gets(ac, f));
            }
            String s = state.toString();
            if (s.equals(lastAcState) && nowMs - lastAcLogMs < AC_HEARTBEAT_MS) {
                return;
            }
            lastAcState = s;
            lastAcLogMs = nowMs;
            sink.ac(nowMs + "," + fmt.format(new Date(nowMs)) + s + "\n");
        } catch (Throwable th) {
            Throwable c = th.getCause() != null ? th.getCause() : th;
            acUnavailable = true;
            sink.status("AC logging unavailable: " + c);
        }
    }
    // ---- speed-linked volume (the one setting this feed may write) ----

    /** Wanted state from the request file: 1 = stop the speed-linked volume, 0 = restore it, -1 = no valid request. */
    private int readVolRequest() {
        for (File f : volRequestFiles) {
            try {
                if (f != null && f.exists() && f.length() <= 16) {
                    RandomAccessFile r = new RandomAccessFile(f, "r");
                    try {
                        byte[] b = new byte[(int) r.length()];
                        r.readFully(b);
                        String s = new String(b, "UTF-8").trim();
                        if (s.equals("1")) {
                            return 1;
                        }
                        if (s.equals("0")) {
                            return 0;
                        }
                    } finally {
                        r.close();
                    }
                    return -1;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private boolean volChangeAllowed(long nowMs) {
        for (int i = volChanges.size() - 1; i >= 0; i--) {
            if (nowMs - volChanges.get(i).longValue() >= 3600000L) {
                volChanges.remove(i);
            }
        }
        return volChanges.size() < VOL_MAX_CHANGES_PER_HOUR;
    }

    private void reportVol(long nowMs, int setting, int request, String note) {
        String text = "setting" + SPEEDVOL_SETTING + "=" + setting + "\n"
                + "boost=" + (setting < 0 ? "UNKNOWN" : setting == 0 ? "ON" : "OFF") + "\n"
                + "request=" + request + "\n"
                + "note=" + note + "\n";
        if (text.equals(lastVolText) && nowMs - lastVolReport < VOL_REPORT_MS) {
            return;
        }
        lastVolText = text;
        lastVolReport = nowMs;
        sink.speedVol("epoch_ms=" + nowMs + "\n" + text);
    }

    /**
     * Keeps the unit's speed-linked volume at the wanted state, checked every 10 s. Reads setting 70 and, if a
     * valid request file asks for a different state, writes it (same call as the unit's own switch). Any failure
     * here switches only this feature off; it never stops or trips the feed.
     */
    private void speedVol(long nowMs) {
        if (volUnavailable || nowMs - lastVolCheck < VOL_CHECK_MS) {
            return;
        }
        lastVolCheck = nowMs;
        int request = -1;
        try {
            if (ftGet == null) {
                Class<?> ft = Class.forName("com.yyw.ts70xhw.FtSet", false, loader);
                ftGet = ft.getMethod("GetCanS", int.class);
                ftSet = ft.getMethod("SetCanS", byte.class, int.class);
            }
            int current = ((Integer) ftGet.invoke(null, Integer.valueOf(SPEEDVOL_SETTING))).intValue();
            request = readVolRequest();
            String note = "";
            int isOff = current != 0 ? 1 : 0;
            if (request >= 0 && request != isOff) {
                if (volWriteBlocked) {
                    note = "writes blocked after an earlier read-back mismatch";
                } else if (!volChangeAllowed(nowMs)) {
                    note = "change refused: limit of " + VOL_MAX_CHANGES_PER_HOUR + " per hour";
                } else {
                    ftSet.invoke(null, Byte.valueOf((byte) request), Integer.valueOf(SPEEDVOL_SETTING));
                    volChanges.add(Long.valueOf(nowMs));
                    int after = ((Integer) ftGet.invoke(null, Integer.valueOf(SPEEDVOL_SETTING))).intValue();
                    note = "set to " + request + ", read back " + after;
                    if ((after != 0 ? 1 : 0) != request) {
                        volWriteBlocked = true;
                        note += " (differs: further writes blocked)";
                    }
                    current = after;
                }
            }
            reportVol(nowMs, current, request, note);
        } catch (Throwable th) {
            Throwable c = th.getCause() != null ? th.getCause() : th;
            volUnavailable = true;
            sink.status("speed volume control unavailable: " + c);
            sink.speedVol("epoch_ms=" + nowMs + "\nsetting" + SPEEDVOL_SETTING + "=-1\nboost=UNKNOWN\nrequest=" + request
                    + "\nnote=unavailable: " + c + "\n");
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
