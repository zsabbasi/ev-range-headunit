package com.evrange.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Single full-screen dashboard drawn on a 1024 x 600 reference canvas. */
public final class DashView extends View {
    public interface Listener {
        void onWindowSelected(int km);

        void onSettings();

        void onNewTrip();
    }

    private static final int BG = 0xFF0E1621;
    private static final int PANEL = 0xFF182331;
    private static final int TEXT = 0xFFEAF0F7;
    private static final int MUTED = 0xFF8DA2B8;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB84D;
    private static final int RED = 0xFFFF5C5C;
    private static final int BLUE = 0xFF4DA3FF;

    private static final int[] WINDOWS = {10, 25, 50};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF[] winBtn = {new RectF(560, 505, 650, 565), new RectF(660, 505, 750, 565),
            new RectF(760, 505, 850, 565)};
    private final RectF settingsBtn = new RectF(870, 468, 1004, 526);
    private final RectF tripBtn = new RectF(870, 534, 1004, 592);

    private RangeEngine engine;
    private Listener listener;
    private String mode = "";
    private boolean stale;
    private long dataAgeSec;
    private boolean gpsOk;

    public DashView(Context c) {
        super(c);
        setBackgroundColor(BG);
    }

    public void bind(RangeEngine e, Listener l) {
        engine = e;
        listener = l;
    }

    public void setStatus(String modeText, boolean isStale, long ageSec, boolean gps) {
        mode = modeText;
        stale = isStale;
        dataAgeSec = ageSec;
        gpsOk = gps;
        invalidate();
    }

    // ---- helpers ----
    private static String f0(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? "--" : String.valueOf(Math.round(d));
    }

    private static String f1(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? "--" : String.format(Locale.US, "%.1f", d);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align a, boolean bold) {
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(a);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, p);
    }

    private void panel(Canvas c, float l, float t, float rt, float b) {
        p.setColor(PANEL);
        p.setStyle(Paint.Style.FILL);
        r.set(l, t, rt, b);
        c.drawRoundRect(r, 14, 14, p);
    }

    private void button(Canvas c, RectF b, String label, boolean selected) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(selected ? BLUE : 0xFF2A3A4D);
        c.drawRoundRect(b, 10, 10, p);
        text(c, label, b.centerX(), b.centerY() + 8, 22, selected ? 0xFF06121F : TEXT, Paint.Align.CENTER, true);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (engine == null) {
            return;
        }
        float s = Math.min(getWidth() / 1024f, getHeight() / 600f);
        c.save();
        c.translate((getWidth() - 1024 * s) / 2f, (getHeight() - 600 * s) / 2f);
        c.scale(s, s);
        drawDash(c);
        c.restore();
    }

    private void drawDash(Canvas c) {
        RangeEngine e = engine;
        double range = e.rangeKm();
        int rangeColor = stale ? MUTED : range >= 40 ? GREEN : range >= 20 ? AMBER : RED;

        // ---- status bar ----
        String gps = gpsOk ? "GPS" : "no GPS";
        String statusText = mode + (stale ? "  -  NO RECENT DATA (" + dataAgeSec + " s)" : "");
        text(c, "EV RANGE", 24, 38, 26, MUTED, Paint.Align.LEFT, true);
        text(c, statusText + "   |   " + gps, 1000, 38, 22, stale ? AMBER : MUTED, Paint.Align.RIGHT, false);

        // ---- main range panel ----
        panel(c, 16, 54, 500, 330);
        text(c, f0(range), 250, 215, 170, rangeColor, Paint.Align.CENTER, true);
        text(c, "km range", 250, 255, 30, TEXT, Paint.Align.CENTER, false);
        text(c, "based on " + e.bestSource(), 250, 290, 22, MUTED, Paint.Align.CENTER, false);
        text(c, f1(e.bestKwhPer100()) + " kWh/100 km", 250, 320, 24, TEXT, Paint.Align.CENTER, true);

        // ---- key figures ----
        panel(c, 516, 54, 1008, 330);
        float y = 90;
        text(c, "Battery", 540, y, 24, MUTED, Paint.Align.LEFT, false);
        text(c, e.soc() + " %   " + f1(e.remainingKwh()) + " kWh", 990, y, 28, TEXT, Paint.Align.RIGHT, true);
        y += 40;
        text(c, "Efficiency now", 540, y, 24, MUTED, Paint.Align.LEFT, false);
        double kmPerKwh = 100.0 / e.bestKwhPer100();
        text(c, f1(kmPerKwh) + " km/kWh", 990, y, 28, TEXT, Paint.Align.RIGHT, true);
        y += 40;
        text(c, "This trip", 540, y, 24, MUTED, Paint.Align.LEFT, false);
        text(c, f1(e.tripKm()) + " km   " + f1(e.tripKwhPer100()) + " kWh/100", 990, y, 28, TEXT,
                Paint.Align.RIGHT, true);
        y += 40;
        RangeEngine.Day today = e.days().get(Long.valueOf(RangeEngine.todayIndex()));
        text(c, "Today", 540, y, 24, MUTED, Paint.Align.LEFT, false);
        text(c, (today == null ? "--" : f1(today.km)) + " km   "
                + (today == null ? "--" : f1(today.kwhPer100())) + " kWh/100", 990, y, 28, TEXT,
                Paint.Align.RIGHT, true);
        y += 40;
        text(c, "All time", 540, y, 24, MUTED, Paint.Align.LEFT, false);
        text(c, f1(e.allTimeKwhPer100()) + " kWh/100", 990, y, 28, TEXT, Paint.Align.RIGHT, true);
        y += 40;
        double wltp = e.wltpRangeKm();
        text(c, "NEDC-based (car display)", 540, y, 24, MUTED, Paint.Align.LEFT, false);
        text(c, Double.isNaN(wltp) ? "not set" : f0(wltp) + " km", 990, y, 28,
                Double.isNaN(wltp) ? MUTED : TEXT, Paint.Align.RIGHT, true);

        // ---- live power bar (-30 kW regen ... +40 kW) ----
        panel(c, 16, 342, 1008, 414);
        double kw = e.liveKw();
        float x0 = 220, x1 = 960, zero = x0 + (x1 - x0) * 30f / 70f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2A3A4D);
        r.set(x0, 366, x1, 392);
        c.drawRoundRect(r, 8, 8, p);
        float v = (float) Math.max(-30, Math.min(40, kw));
        float xv = zero + (x1 - x0) * v / 70f;
        p.setColor(kw < 0 ? GREEN : kw > 25 ? RED : AMBER);
        r.set(Math.min(zero, xv), 366, Math.max(zero, xv), 392);
        c.drawRect(r, p);
        p.setColor(TEXT);
        c.drawRect(zero - 2, 360, zero + 2, 398, p);
        text(c, kw < -0.05 ? "REGEN" : "POWER", 36, 368, 18, kw < -0.05 ? GREEN : MUTED, Paint.Align.LEFT, true);
        text(c, f1(Math.abs(kw)) + " kW", 36, 402, 30, TEXT, Paint.Align.LEFT, true);

        // ---- last 7 days chart ----
        panel(c, 16, 424, 540, 590);
        text(c, "Last 7 days, kWh/100 km", 32, 450, 20, MUTED, Paint.Align.LEFT, false);
        long today0 = RangeEngine.todayIndex();
        float bx = 40, bw = 56, base = 572, maxH = 90, scaleMax = 25f;
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.US);
        for (int i = 6; i >= 0; i--) {
            long idx = today0 - i;
            Map<Long, RangeEngine.Day> m = e.days();
            RangeEngine.Day d = m.get(Long.valueOf(idx));
            double val = d == null ? Double.NaN : d.kwhPer100();
            float cx = bx + (6 - i) * 72;
            if (!Double.isNaN(val)) {
                float h = (float) Math.min(maxH, val / scaleMax * maxH);
                p.setStyle(Paint.Style.FILL);
                p.setColor(val < 14 ? GREEN : val < 18 ? AMBER : RED);
                r.set(cx, base - h - 18, cx + bw, base - 18);
                c.drawRoundRect(r, 6, 6, p);
                text(c, f1(val), cx + bw / 2, base - h - 24, 17, TEXT, Paint.Align.CENTER, true);
            }
            text(c, dayFmt.format(new Date(RangeEngine.dayStartMs(idx) + 3600000L)), cx + bw / 2, base, 17, MUTED,
                    Paint.Align.CENTER, false);
        }

        // ---- buttons ----
        text(c, "Estimate from last", 560, 495, 20, MUTED, Paint.Align.LEFT, false);
        for (int i = 0; i < WINDOWS.length; i++) {
            button(c, winBtn[i], WINDOWS[i] + " km", Math.round(e.windowKm) == WINDOWS[i]);
        }
        button(c, settingsBtn, "Settings", false);
        button(c, tripBtn, "New trip", false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_UP || listener == null) {
            return true;
        }
        float s = Math.min(getWidth() / 1024f, getHeight() / 600f);
        float x = (ev.getX() - (getWidth() - 1024 * s) / 2f) / s;
        float y = (ev.getY() - (getHeight() - 600 * s) / 2f) / s;
        for (int i = 0; i < winBtn.length; i++) {
            if (winBtn[i].contains(x, y)) {
                listener.onWindowSelected(WINDOWS[i]);
                return true;
            }
        }
        if (settingsBtn.contains(x, y)) {
            listener.onSettings();
        } else if (tripBtn.contains(x, y)) {
            listener.onNewTrip();
        }
        return true;
    }
}
