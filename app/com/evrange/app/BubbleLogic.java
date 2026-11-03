package com.evrange.app;

/**
 * Decides when the floating range bubble may be on screen. No Android dependencies.
 *
 * Safety: selecting R puts the unit's reverse camera on screen, and the bubble must never sit over it.
 * The camera comes up before the car moves, so the default mode shows the bubble only while the car is
 * driving forward. The "always" mode still hides it while reversing (negative motor rpm) and for a few
 * seconds afterwards, but cannot know that R was just selected while the car is still standing.
 */
public final class BubbleLogic {
    public static final int OFF = 0;
    public static final int DRIVING = 1;
    public static final int ALWAYS = 2;

    public static final int MOVING_RPM = 30;
    public static final long REVERSE_HOLD_MS = 8000;

    private long lastReverseMs = Long.MIN_VALUE / 2;

    /** mode: OFF, DRIVING or ALWAYS. rpm: signed motor rpm (negative while reversing). */
    public boolean visible(long nowMs, int mode, int rpm, boolean appVisible, boolean demo, boolean snoozed) {
        if (rpm <= -MOVING_RPM) {
            lastReverseMs = nowMs;
        }
        if (mode != DRIVING && mode != ALWAYS) {
            return false;
        }
        if (appVisible || demo || snoozed) {
            return false;
        }
        if (nowMs - lastReverseMs < REVERSE_HOLD_MS) {
            return false;
        }
        if (mode == DRIVING) {
            return rpm >= MOVING_RPM;
        }
        return true;
    }
}
