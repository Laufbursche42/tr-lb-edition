// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that runs only during an active armed ride (started by {@link RideLogger} when
 * a ride arms; stopped on disconnect / finalize / toggle-off). It keeps the process in the
 * foreground so BLE and the 60 s ride sampling stay alive with the screen off.
 *
 * <p>Shows a small ongoing low-importance notification. Foreground service type is
 * {@code connectedDevice} (the scooter is a connected BLE device); the service is not exported.
 */
public final class RideLoggerService extends Service {

    private static final String TAG = "lbridesvc";
    static final String CHANNEL_ID = "ride_log";
    static final int NOTIF_ID = 7802;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            createChannel();
            Notification notif = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            try {
                startForeground(NOTIF_ID, buildNotification());
            } catch (Throwable t2) {
                Log.e(TAG, "startForeground fallback failed", t2);
            }
        }
        // Do not restart if the process is killed; RideLogger re-arms a fresh ride on next movement.
        return START_NOT_STICKY;
    }

    private void createChannel() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            // minSdk is 26, so notification channels always exist.
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Ride log",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Recording ride log");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "createChannel failed", t);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi =
                PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Laufbursche Edition")
                .setContentText("Recording ride log")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
