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

/**
 * Foreground service that OWNS an active turn-by-turn navigation session: it keeps GPS running,
 * evaluates guidance ({@link NavGuidance}), speaks voice directions ({@link TtsHelper} /
 * {@link NavVoice}) and publishes the current {@link NavSession.State} - independent of whether
 * {@link NavActivity} or the dashboard is on screen. That is why navigation survives switching pages
 * and why the voice keeps announcing while you are on the main screen.
 *
 * <p>The service does NOT re-route itself. While NavActivity is visible it observes the off-route
 * flag and re-routes (updating {@link NavSession}); off the map screen an off-route rider just sees
 * "Recalculating" until they rejoin the route or reopen the map. Started by NavActivity on Start,
 * stopped only by an explicit End (the notification action or the Stop button) - never by leaving a
 * screen.
 */
public final class NavigationService extends Service {

    private static final String TAG = "lbnavsvc";
    static final String CHANNEL_ID = "nav";
    static final int NOTIF_ID = 7803;
    static final String ACTION_STOP = "com.lb.edition.NAV_STOP";

    private static final double ANNOUNCE_FAR_M = 200.0;
    private static final double ANNOUNCE_NEAR_M = 30.0;
    private static final double ARRIVE_ANNOUNCE_M = 60.0;

    private LocationManager locationManager;
    private TtsHelper tts;
    private NavVoice navVoice;
    private boolean voiceOn = true;

    // Announce-once-per-maneuver guards (index into maneuverIdx[]).
    private int lastFarIdx = -1;
    private int lastNearIdx = -1;
    private boolean announcedArrive = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { onLocation(location); }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopNavigation();
            return START_NOT_STICKY;
        }
        try {
            createChannel();
            Notification n = buildNotification("Navigating", "");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            try { startForeground(NOTIF_ID, buildNotification("Navigating", "")); } catch (Throwable ignored) { }
        }

        voiceOn = getSharedPreferences("nav", MODE_PRIVATE).getBoolean("voice_on", true);
        try {
            navVoice = NavVoice.forDevice();
            tts = new TtsHelper(this, navVoice.locale());
        } catch (Throwable t) {
            Log.w(TAG, "TTS init failed", t);
        }
        // Fresh run - let every maneuver announce again.
        lastFarIdx = -1;
        lastNearIdx = -1;
        announcedArrive = false;

        NavSession.begin();
        startLocationUpdates();
        return START_NOT_STICKY;
    }

    private void startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "no location permission; navigation service cannot get fixes");
                return;
            }
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;
            // Native LocationManager keeps the app free of Google Play Services (same as NavActivity).
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener, Looper.getMainLooper());
            }
        } catch (Throwable t) {
            Log.e(TAG, "startLocationUpdates failed", t);
        }
    }

    private void onLocation(Location loc) {
        if (loc == null) return;
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        NavGuidance.Fix f = NavGuidance.compute(lat, lon,
                NavSession.lats, NavSession.lons, NavSession.cumDist,
                NavSession.maneuverIdx, NavSession.maneuverText);

        if (f == null) {
            // No route loaded yet - still publish the position so the me-marker can follow.
            NavSession.publish(new NavSession.State(true, false, false, "", "↑", 0, 0, lat, lon, true));
            return;
        }

        if (f.offRoute) {
            NavSession.publish(new NavSession.State(true, false, true,
                    "Recalculating…", "↻", 0, 0, lat, lon, true));
            updateNotification("Off route", "Recalculating…");
            return;
        }

        String arrow = NavGuidance.arrowFor(f.nextText, f.arrive);
        boolean arrived = f.arrive && f.remainingM < ARRIVE_ANNOUNCE_M;
        NavSession.publish(new NavSession.State(true, arrived, false,
                f.nextText, arrow, f.distToNextM, f.remainingM, lat, lon, true));
        updateNotification(f.nextText + " in " + NavGuidance.formatMeters(f.distToNextM),
                NavGuidance.formatMeters(f.remainingM) + " to destination");
        announce(f);
    }

    private void announce(NavGuidance.Fix f) {
        if (!voiceOn || tts == null || !tts.isReady() || navVoice == null) return;
        try {
            if (f.arrive) {
                if (!announcedArrive && f.remainingM <= ARRIVE_ANNOUNCE_M) {
                    announcedArrive = true;
                    tts.speak(navVoice.arrive());
                }
                return;
            }
            String phrase = navVoice.maneuver(f.nextText);
            if (f.distToNextM <= ANNOUNCE_FAR_M && f.distToNextM > ANNOUNCE_NEAR_M && lastFarIdx != f.maneuverKey) {
                lastFarIdx = f.maneuverKey;
                tts.speak(navVoice.far(phrase));
            }
            if (f.distToNextM <= ANNOUNCE_NEAR_M && lastNearIdx != f.maneuverKey) {
                lastNearIdx = f.maneuverKey;
                tts.speak(navVoice.near(phrase));
            }
        } catch (Throwable t) {
            Log.w(TAG, "announce failed", t);
        }
    }

    /** Convenience for other components to end the session. */
    static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, NavigationService.class).setAction(ACTION_STOP));
        } catch (Throwable ignored) { }
    }

    private void stopNavigation() {
        try { if (locationManager != null) locationManager.removeUpdates(locationListener); } catch (Throwable ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        tts = null;
        try { NavSession.end(); } catch (Throwable ignored) { }
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { if (locationManager != null) locationManager.removeUpdates(locationListener); } catch (Throwable ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        tts = null;
        super.onDestroy();
    }

    // ── Notification ──

    private void createChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Navigation",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active turn-by-turn navigation");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "createChannel failed", t);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, NavigationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title != null && !title.isEmpty() ? title : "Navigation")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .addAction(0, "End navigation", stopPi)
                .build();
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
