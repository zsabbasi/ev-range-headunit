package com.evrange.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;

/**
 * READ-ONLY client of the head unit's car-info service. It makes exactly one kind of call, the read method
 * requestCarBaseInfo (transaction 6), once a second on its own thread, and keeps the latest range and battery
 * values. It never calls the interface's command or serial-port methods. The values come from static Java
 * fields that the unit refreshes itself, so reading them does not enter the unit's native library.
 */
public final class CarInfoClient {
    private static final String DESCRIPTOR = "com.ts.can.carinfo.ICarInfoService";
    private static final int TX_REQUEST_CAR_BASE_INFO = 6;  // the only call this class ever makes
    private static final long FRESH_MS = 5000;

    private final Context ctx;
    private HandlerThread thread;
    private Handler handler;
    private volatile IBinder binder;
    private boolean bound;
    private volatile int rangeKm = -1;
    private volatile int soc = -1;
    private volatile long atMs;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                readOnce();
            } catch (Throwable ignored) {
            }
            if (handler != null) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    public CarInfoClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.ts.MainUI", "com.ts.can.carinfo.CarInfoService"));
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable ignored) {
        }
        thread = new HandlerThread("carinfo");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(poll);
    }

    public synchronized void stop() {
        if (handler != null) {
            handler.removeCallbacks(poll);
        }
        if (thread != null) {
            thread.quit();
            thread = null;
            handler = null;
        }
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
    }

    private void readOnce() throws Exception {
        IBinder b = binder;
        if (b == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(TX_REQUEST_CAR_BASE_INFO, data, reply, 0);
            reply.readException();
            int[] values = reply.createIntArray();
            int km = CarRange.rangeKm(values);
            int s = CarRange.socPct(values);
            if (km > 0 && s > 0) {
                rangeKm = km;
                soc = s;
                atMs = System.currentTimeMillis();
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean fresh() {
        return System.currentTimeMillis() - atMs <= FRESH_MS;
    }

    /** The car's own range in km, or -1 if it has not been read in the last few seconds. */
    public int rangeKm() {
        return fresh() ? rangeKm : -1;
    }

    /** Battery level in percent as reported with the range, or -1. */
    public int socPct() {
        return fresh() ? soc : -1;
    }

    /** CarRange.MODE_D, MODE_S or MODE_UNKNOWN. */
    public int mode() {
        return fresh() ? CarRange.modeFrom(rangeKm, soc) : CarRange.MODE_UNKNOWN;
    }
}
