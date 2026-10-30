package com.evrange.app;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Incrementally reads new complete lines appended to ev_log.csv. */
public final class LogReader {
    private static final int MAX_BYTES_PER_POLL = 2 * 1024 * 1024;

    private File file;
    private long pos;

    public LogReader(String path, long startPos) {
        this.file = new File(path);
        this.pos = startPos;
    }

    public void setPath(String path, long startPos) {
        this.file = new File(path);
        this.pos = startPos;
    }

    public long position() {
        return pos;
    }

    public String path() {
        return file.getPath();
    }

    /** Returns new data lines (header lines are skipped). Never throws. */
    public List<String> poll() {
        List<String> out = new ArrayList<String>();
        RandomAccessFile raf = null;
        try {
            if (!file.exists()) {
                return out;
            }
            long len = file.length();
            if (len < pos) {
                pos = 0; // file was rotated or replaced
            }
            if (len == pos) {
                return out;
            }
            raf = new RandomAccessFile(file, "r");
            raf.seek(pos);
            int want = (int) Math.min(len - pos, MAX_BYTES_PER_POLL);
            byte[] buf = new byte[want];
            raf.readFully(buf);
            int lastNl = -1;
            for (int i = want - 1; i >= 0; i--) {
                if (buf[i] == '\n') {
                    lastNl = i;
                    break;
                }
            }
            if (lastNl < 0) {
                return out;
            }
            String text = new String(buf, 0, lastNl + 1, "UTF-8");
            pos += lastNl + 1;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.length() > 0 && !t.startsWith("epoch_ms")) {
                    out.add(t);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }
}
