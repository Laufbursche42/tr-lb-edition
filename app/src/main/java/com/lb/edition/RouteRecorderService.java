// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Foreground LOCATION service that records a GPS route to a file for the duration of one ride,
 * independent of the WebView. This is what makes route recording survive the screen going off: a
 * WebView {@code navigator.geolocation.watchPosition} is throttled/frozen in the background, so the
 * track would otherwise stop after a couple hundred metres. Here the native {@link LocationManager}
 * keeps delivering fixes while the service is foreground, and each fix is appended as one NDJSON line.
 *
 * <p>Lifecycle is owned by {@link RouteRecorder}: it starts this service (passing the target file and
 * the point interval) once the scooter has actually moved, and stops it when the scooter disconnects.
 * The file is one compact JSON object per line - {@code {"lat","lon","alt","ts","speed"}} - flushed
 * immediately, so an app kill loses at most the last fix. {@link RouteRecorder#takeRecordedRoutes()}
 * hands finished files to the dashboard, which imports them into its recorded-routes list.
 */
public final class RouteRecorderService extends Service {

    private static final String TAG = "lbroutesvc";
    static final String CHANNEL_ID = "route";
    static final int NOTIF_ID = 7804;
    static final String EXTRA_FILE = "file";
    static final String EXTRA_INTERVAL_MS = "intervalMs";

    private LocationManager locationManager;
    private Writer writer;

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { onLocation(location); }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            createChannel();
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            try { startForeground(NOTIF_ID, buildNotification()); } catch (Throwable ignored) { }
        }

        String path = intent != null ? intent.getStringExtra(EXTRA_FILE) : null;
        long intervalMs = intent != null ? intent.getLongExtra(EXTRA_INTERVAL_MS, 5000L) : 5000L;
        if (intervalMs < 1000L) intervalMs = 1000L;
        openWriter(path);
        startLocationUpdates(intervalMs);
        // START_REDELIVER_INTENT: if the OS kills and restarts us, keep writing to the same file.
        return START_REDELIVER_INTENT;
    }

    private void openWriter(String path) {
        closeWriter();
        if (path == null || path.isEmpty()) { Log.e(TAG, "no route file path"); return; }
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(path), true), "UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "openWriter failed", t);
            writer = null;
        }
    }

    private void startLocationUpdates(long intervalMs) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "no location permission; route service cannot get fixes");
                return;
            }
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;
            // Native LocationManager keeps the app free of Google Play Services (same as NavActivity).
            // minDistance 0: one fix per interval regardless of movement, matching the old JS behaviour.
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, intervalMs, 0f, locationListener, Looper.getMainLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, intervalMs, 0f, locationListener, Looper.getMainLooper());
            }
        } catch (Throwable t) {
            Log.e(TAG, "startLocationUpdates failed", t);
        }
    }

    private void onLocation(Location loc) {
        if (loc == null) return;
        Writer w = writer;
        if (w == null) return;
        try {
            double lat = Math.round(loc.getLatitude() * 1e6) / 1e6;
            double lon = Math.round(loc.getLongitude() * 1e6) / 1e6;
            double alt = loc.hasAltitude() ? Math.round(loc.getAltitude() * 10.0) / 10.0 : 0.0;
            double spd = loc.hasSpeed() ? Math.round(loc.getSpeed() * 3.6 * 10.0) / 10.0 : 0.0;   // m/s -> km/h
            long ts = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
            // Compact NDJSON, one point per line, matching the JS route point shape.
            w.write("{\"lat\":" + lat + ",\"lon\":" + lon + ",\"alt\":" + alt
                    + ",\"ts\":" + ts + ",\"speed\":" + spd + "}\n");
            w.flush();   // flush immediately so an app kill loses at most this one fix
        } catch (Throwable t) {
            Log.e(TAG, "onLocation write failed", t);
        }
    }

    private void closeWriter() {
        Writer w = writer;
        writer = null;
        if (w != null) {
            try { w.flush(); } catch (Throwable ignored) { }
            try { w.close(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        try { if (locationManager != null) locationManager.removeUpdates(locationListener); } catch (Throwable ignored) { }
        closeWriter();
        super.onDestroy();
    }

    // ── Notification ──

    private void createChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Ride recording",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Recording the GPS route of the current ride");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "createChannel failed", t);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Recording ride")
                .setContentText("Tracking your route in the background")
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
