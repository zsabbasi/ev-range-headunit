package com.evrange.app;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/** Optional GPS speed. Fails silently if there is no permission or no fix. */
public final class GpsSource implements LocationListener {
    private final LocationManager lm;
    private volatile double speedMs = -1;
    private volatile long fixAtMs;
    private boolean started;

    public GpsSource(Context ctx) {
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (started || lm == null) {
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            started = true;
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        if (started && lm != null) {
            try {
                lm.removeUpdates(this);
            } catch (Throwable ignored) {
            }
        }
        started = false;
    }

    /** Speed, altitude and accuracy of the latest fix. No coordinates are kept. */
    public static final class Fix {
        public final double speedMs;
        public final double altM;
        public final double accM;

        Fix(double speedMs, double altM, double accM) {
            this.speedMs = speedMs;
            this.altM = altM;
            this.accM = accM;
        }
    }

    private volatile double altM = Double.NaN;
    private volatile double accM = Double.NaN;

    /** Same freshness rule as speedFor, but returns speed, altitude and accuracy; null if stale. */
    public Fix fixFor(long sampleMs) {
        long now = System.currentTimeMillis();
        if (now - fixAtMs > 5000 || Math.abs(now - sampleMs) > 6000 || speedMs < 0) {
            return null;
        }
        return new Fix(speedMs, altM, accM);
    }

    /** Latest GPS speed in km/h, or -1 if there is no fresh fix. */
    public double currentKmh() {
        if (System.currentTimeMillis() - fixAtMs > 5000 || speedMs < 0) {
            return -1;
        }
        return speedMs * 3.6;
    }

    public boolean hasFix() {
        return System.currentTimeMillis() - fixAtMs < 6000;
    }

    /** Speed in m/s for a log sample taken at sampleMs, or -1 if GPS cannot vouch for it. */
    public double speedFor(long sampleMs) {
        long now = System.currentTimeMillis();
        if (now - fixAtMs > 5000 || Math.abs(now - sampleMs) > 6000) {
            return -1;
        }
        return speedMs;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l != null && l.hasSpeed()) {
            speedMs = l.getSpeed();
            altM = l.hasAltitude() ? l.getAltitude() : Double.NaN;
            accM = l.hasAccuracy() ? l.getAccuracy() : Double.NaN;
            fixAtMs = System.currentTimeMillis();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
