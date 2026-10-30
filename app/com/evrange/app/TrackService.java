package com.evrange.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Foreground service that keeps the process (and so GPS and log tracking) alive in the background. */
public class TrackService extends Service {
    private static final String CHANNEL = "evrange_track";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "EV Range tracking",
                        NotificationManager.IMPORTANCE_MIN));
                b = new Notification.Builder(this, CHANNEL);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle("EV Range").setContentText("Tracking trips and range")
                    .setSmallIcon(android.R.drawable.ic_menu_compass);
            startForeground(1, b.build());
        } catch (Throwable ignored) {
        }
        try {
            Core.start(getApplicationContext(), null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
