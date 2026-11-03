package com.evrange.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * A small floating bubble with the live range, drawn over other screens. It needs the "Draw over other
 * apps" permission (granted over adb on the unit, see docs/INSTALL.md). When it may show is decided by
 * BubbleLogic, which keeps it off the reverse camera. Everything runs on the main thread (called from Core).
 */
public final class Bubble {
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB84D;
    private static final int RED = 0xFFFF5C5C;
    private static final int BLUE = 0xFF4DA3FF;
    private static final int GREY = 0xFFD0DBE8;

    private static final BubbleLogic LOGIC = new BubbleLogic();
    private static WindowManager wm;
    private static BubbleView view;
    private static WindowManager.LayoutParams lp;
    private static boolean snoozed;
    private static long retryAtMs;

    private Bubble() {
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Once a second: shows, updates or removes the bubble. Never throws. */
    public static void tick(Context app, SharedPreferences prefs, RangeEngine e, boolean stale, boolean appVisible,
            boolean demo, int driveMode) {
        try {
            long now = System.currentTimeMillis();
            int mode = prefs.getInt("bubble", BubbleLogic.DRIVING);
            boolean want = LOGIC.visible(now, mode, e.rpm(), appVisible, demo, snoozed) && canDraw(app);
            if (!want) {
                remove();
                return;
            }
            if (view == null) {
                if (now < retryAtMs) {
                    return;
                }
                add(app, prefs);
                if (view == null) {
                    return;
                }
            }
            double range = e.rangeKm();
            boolean charging = !stale && e.charging();
            int color = stale ? GREY : charging ? BLUE : range >= 40 ? GREEN : range >= 20 ? AMBER : RED;
            String main = stale || Double.isNaN(range) || Double.isInfinite(range) ? "--" : String.valueOf(Math.round(range));
            String small = charging ? "charging" : driveMode == CarRange.MODE_S ? "S" : "";
            view.set(main, small, color);
        } catch (Throwable ignored) {
            remove();
        }
    }

    private static void add(Context app, SharedPreferences prefs) {
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            float d = app.getResources().getDisplayMetrics().density;
            int w = Math.round(112 * d);
            int h = Math.round(52 * d);
            lp = new WindowManager.LayoutParams(w, h,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            int screenW = app.getResources().getDisplayMetrics().widthPixels;
            // default spot: the empty middle of the unit's top bar (it can be dragged anywhere)
            lp.x = prefs.getInt("bx", Math.round(screenW * 0.32f));
            lp.y = prefs.getInt("by", Math.round(2 * d));
            view = new BubbleView(app, prefs);
            wm.addView(view, lp);
        } catch (Throwable t) {
            view = null;
            retryAtMs = System.currentTimeMillis() + 60000;
        }
    }

    public static void remove() {
        try {
            if (view != null && wm != null) {
                wm.removeView(view);
            }
        } catch (Throwable ignored) {
        }
        view = null;
    }

    private static final class BubbleView extends View {
        private final Context app;
        private final SharedPreferences prefs;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;
        private String main = "--";
        private String small = "";
        private int color = GREY;
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;
        private long downAt;

        BubbleView(Context app, SharedPreferences prefs) {
            super(app);
            this.app = app;
            this.prefs = prefs;
            this.d = app.getResources().getDisplayMetrics().density;
        }

        void set(String main, String small, int color) {
            if (!main.equals(this.main) || !small.equals(this.small) || color != this.color) {
                this.main = main;
                this.small = small;
                this.color = color;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float stroke = 3 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xE60B121B);
            r.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
            c.drawRoundRect(r, 16 * d, 16 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(color);
            c.drawRoundRect(r, 16 * d, 16 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(24 * d);
            boolean two = small.length() > 0;
            c.drawText(main + " km", w / 2, two ? h * 0.52f : h * 0.64f, p);
            if (two) {
                p.setTextSize(13 * d);
                p.setColor(color);
                c.drawText(small, w / 2, h * 0.84f, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    downAt = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (dragging || Math.abs(dx) > 8 * d || Math.abs(dy) > 8 * d) {
                        dragging = true;
                        lp.x = Math.max(0, startX + Math.round(dx));
                        lp.y = Math.max(0, startY + Math.round(dy));
                        try {
                            wm.updateViewLayout(this, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply();
                    } else if (System.currentTimeMillis() - downAt > 800) {
                        snoozed = true; // long press: hidden until the app process restarts
                        remove();
                    } else {
                        try {
                            Intent i = new Intent(app, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            app.startActivity(i);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
