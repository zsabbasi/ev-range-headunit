package com.ts.evhook;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Manual start for testing (am startservice). Runs on the main thread of com.ts.MainUI. */
public class EvService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            EvFeed.start(getApplicationContext(), 3000);
        } catch (Throwable ignored) {
        }
        try {
            stopSelf(startId);
        } catch (Throwable ignored) {
        }
        return START_NOT_STICKY;
    }
}
