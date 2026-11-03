package com.ts.evhook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Android glue. The polling runs on the MAIN thread (via a Handler), exactly like the unit's own
 * CAN pages. All file output happens on a separate writer thread that never calls the native
 * library. Never throws.
 */
public final class EvFeed {
    private static final String DIR = "/storage/emulated/0/TsCarInfo";
    private static final long MAX_LOG_BYTES = 10L * 1024 * 1024;
    private static final long MAX_AC_LOG_BYTES = 2L * 1024 * 1024;
    private static final long MAX_STATUS_BYTES = 256L * 1024;
    /** Where the EV Range app leaves its request for the speed-linked volume (its own folder, no permission needed). */
    private static final String APP_REQUEST = "/storage/emulated/0/Android/data/com.evrange.app/files/speedvol_request.txt";

    private static boolean started;

    private EvFeed() {
    }

    private static final class Writer extends Thread implements EvFeedCore.Sink {
        private final LinkedBlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>(2000);
        private final File dir = new File(DIR);
        private String lastStatus = "";

        Writer() {
            super("EvFeedWriter");
            setDaemon(true);
            setPriority(Thread.MIN_PRIORITY);
        }

        @Override
        public void log(String line) {
            q.offer(new String[] {"log", line});
        }

        @Override
        public void latest(String text) {
            q.offer(new String[] {"latest", text});
        }

        @Override
        public void status(String text) {
            q.offer(new String[] {"status", text});
        }

        @Override
        public void speedVol(String text) {
            q.offer(new String[] {"speedvol", text});
        }

        @Override
        public void ac(String line) {
            q.offer(new String[] {"ac", line});
        }

        @Override
        public void run() {
            while (true) {
                try {
                    String[] item = q.take();
                    handle(item);
                } catch (Throwable ignored) {
                }
            }
        }

        private void handle(String[] item) throws Exception {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (item[0].equals("log")) {
                File log = new File(dir, "ev_log.csv");
                EvFeedCore.rotateIfBig(log, MAX_LOG_BYTES);
                boolean fresh = !log.exists();
                FileOutputStream out = new FileOutputStream(log, true);
                try {
                    if (fresh) {
                        out.write(EvFeedCore.HEADER.getBytes("UTF-8"));
                    }
                    out.write(item[1].getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } else if (item[0].equals("latest")) {
                File tmp = new File(dir, "ev_latest.tmp");
                FileOutputStream out = new FileOutputStream(tmp, false);
                try {
                    out.write(item[1].getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                File latest = new File(dir, "ev_latest.csv");
                if (!tmp.renameTo(latest)) {
                    latest.delete();
                    tmp.renameTo(latest);
                }
            } else if (item[0].equals("ac")) {
                File log = new File(dir, "ac_log.csv");
                EvFeedCore.rotateIfBig(log, MAX_AC_LOG_BYTES);
                boolean fresh = !log.exists();
                FileOutputStream out = new FileOutputStream(log, true);
                try {
                    if (fresh) {
                        out.write(EvFeedCore.AC_HEADER.getBytes("UTF-8"));
                    }
                    out.write(item[1].getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } else if (item[0].equals("speedvol")) {
                File tmp = new File(dir, "evfeed_speedvol.tmp");
                FileOutputStream out = new FileOutputStream(tmp, false);
                try {
                    out.write(item[1].getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                File state = new File(dir, "evfeed_speedvol.txt");
                if (!tmp.renameTo(state)) {
                    state.delete();
                    tmp.renameTo(state);
                }
            } else {
                if (item[1].equals(lastStatus)) {
                    return;
                }
                lastStatus = item[1];
                File statusFile = new File(dir, "evfeed_status.txt");
                EvFeedCore.rotateIfBig(statusFile, MAX_STATUS_BYTES);
                FileOutputStream out = new FileOutputStream(statusFile, true);
                try {
                    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                    out.write((ts + "  " + item[1] + "\n").getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
        }
    }

    public static synchronized void start(final Context context, final long initialDelayMs) {
        if (started) {
            return;
        }
        started = true;
        try {
            final Writer writer = new Writer();
            writer.start();
            writer.status("start requested, pid=" + android.os.Process.myPid()
                    + " uid=" + android.os.Process.myUid() + " delay=" + initialDelayMs + " ms");

            // crash-loop breaker runs off the main thread and never touches the native library
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        new EvFeedCore(null, new File(DIR), writer).recordStart(System.currentTimeMillis());
                    } catch (Throwable ignored) {
                    }
                }
            }, "EvFeedBreaker").start();

            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                private EvFeedCore core;

                @Override
                public void run() {
                    try {
                        if (core == null) {
                            core = new EvFeedCore(loader(context), new File(DIR), writer,
                                    new File[] {new File(APP_REQUEST), new File(DIR, "speedvol_request.txt")});
                        }
                        long next = core.step(System.currentTimeMillis());
                        if (next > 0) {
                            handler.postDelayed(this, next);
                        }
                    } catch (Throwable th) {
                        writer.status("glue error, feed stopped: " + th);
                    }
                }
            }, initialDelayMs);
        } catch (Throwable ignored) {
        }
    }

    private static ClassLoader loader(Context context) {
        try {
            Context c = context.createPackageContext("com.ts.MainUI",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader cl = c.getClassLoader();
            if (cl != null) {
                return cl;
            }
        } catch (Throwable ignored) {
        }
        return context.getClassLoader();
    }
}
