package com.evrange.app;

import java.io.File;
import java.io.FileInputStream;

/**
 * The state of the car's speed-linked volume, as reported by the data feed (hook v3) in
 * evfeed_speedvol.txt, and the request file that asks the feed to change it. No Android dependencies.
 *
 * The feed writes key=value lines: epoch_ms, setting70, boost (ON, OFF or UNKNOWN), request, note.
 * The boost is applied by the unit while setting 70 is 0.
 */
public final class SpeedVol {
    public static final String STATUS_PATH = "/storage/emulated/0/TsCarInfo/evfeed_speedvol.txt";
    public static final String REQUEST_NAME = "speedvol_request.txt";
    /** The feed reports at least every 30 s while it runs; older than this means it is not running. */
    public static final long FRESH_MS = 90000;

    public static final int UNKNOWN = 0;
    public static final int BOOST_ON = 1;
    public static final int BOOST_OFF = 2;

    public static final class State {
        public final int boost;
        public final String note;

        State(int boost, String note) {
            this.boost = boost;
            this.note = note;
        }
    }

    private SpeedVol() {
    }

    /** Parses the feed's state text. Missing, stale or unreadable text gives UNKNOWN. */
    public static State parse(String text, long nowMs) {
        if (text == null) {
            return new State(UNKNOWN, "");
        }
        long at = -1;
        String boost = "";
        String note = "";
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.equals("epoch_ms")) {
                try {
                    at = Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            } else if (k.equals("boost")) {
                boost = v;
            } else if (k.equals("note")) {
                note = v;
            }
        }
        if (at < 0 || Math.abs(nowMs - at) > FRESH_MS) {
            return new State(UNKNOWN, note);
        }
        if (boost.equals("ON")) {
            return new State(BOOST_ON, note);
        }
        if (boost.equals("OFF")) {
            return new State(BOOST_OFF, note);
        }
        return new State(UNKNOWN, note);
    }

    /** Reads and parses the feed's state file; UNKNOWN if it does not exist. */
    public static State read(File f, long nowMs) {
        try {
            if (f.exists() && f.length() < 2048) {
                byte[] b = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                try {
                    int n = 0;
                    while (n < b.length) {
                        int k = in.read(b, n, b.length - n);
                        if (k < 0) {
                            break;
                        }
                        n += k;
                    }
                } finally {
                    in.close();
                }
                return parse(new String(b, "UTF-8"), nowMs);
            }
        } catch (Throwable ignored) {
        }
        return new State(UNKNOWN, "");
    }

    public static String label(State s) {
        if (s.boost == BOOST_ON) {
            return "ON (volume rises with speed)";
        }
        if (s.boost == BOOST_OFF) {
            return "OFF (stopped)";
        }
        return "unknown (data feed not running, or it needs the update)";
    }

    /** The text for the request file: "1" asks the feed to stop the speed-linked volume, "0" to restore it. */
    public static String requestText(boolean stop) {
        return stop ? "1" : "0";
    }
}
