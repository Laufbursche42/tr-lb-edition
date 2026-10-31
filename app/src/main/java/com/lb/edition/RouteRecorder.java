// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * Native lifecycle owner for GPS route recording, so a recorded ride survives the screen going off
 * (a WebView geolocation watch does not - it is throttled in the background). Mirrors {@link RideLogger}'s
 * arm/finalize model but records the GPS TRACK, not the telemetry snapshots:
 *
 * <ul>
 *   <li>{@link #onConnected()} resets the session.
 *   <li>{@link #onLiveData(String)} integrates the scooter's own reported speed; once ~20 m of real
 *       movement has been covered (and auto-track is on) the ride ARMS: a route file is created and
 *       {@link RouteRecorderService} starts logging GPS fixes in the foreground.
 *   <li>{@link #onDisconnected()} finalizes: the foreground service stops; the finished file waits to
 *       be imported.
 * </ul>
 *
 * <p>{@link #takeRecordedRoutes()} hands every finished route (all but the one being recorded now) to
 * the dashboard as JSON and deletes the files, so the WebView imports them into its recorded-routes
 * list. Config (auto-track on/off + point interval) is mirrored from the dashboard via
 * {@link #setConfig(boolean, int)} because the native side cannot read the WebView's localStorage.
 *
 * <p>Every public method is null/exception-safe and never throws across the JS bridge.
 */
public final class RouteRecorder {

    private static final String TAG = "lbroute";
    private static final String PREFS = "lb";                 // shared with BleManager
    private static final String KEY_AUTO = "gps_auto";        // default true
    private static final String KEY_INTERVAL_S = "gps_interval_s"; // default 5
    private static final String ROUTES_DIR = "gpsroutes";
    private static final double ARM_DISTANCE_M = 20.0;

    private final Context appCtx;

    private boolean connected = false;
    private boolean armed = false;
    private boolean stopped = false;         // Stop button pressed: block auto re-arm until reconnect
    private double armDistM = 0;
    private long armLastTs = 0;
    private String currentFileName = null;   // the file being recorded right now (not yet importable)

    /** Surfaces a recording error to the UI, e.g. the OS refusing a background foreground-service start
     *  on Android 12+. MainActivity sets this and forwards the code to the dashboard. */
    public interface Listener { void onRecordingError(String code); }
    private volatile Listener listener;
    public void setListener(Listener l) { this.listener = l; }

    RouteRecorder(Context ctx) {
        this.appCtx = ctx != null ? ctx.getApplicationContext() : null;
    }

    // ── Config mirrored from the dashboard (localStorage is not readable natively) ──

    /** Persist the dashboard's GPS-recording config so the native recorder matches it. */
    public synchronized void setConfig(boolean autoTrack, int intervalSec) {
        try {
            if (appCtx == null) return;
            if (intervalSec < 1) intervalSec = 1;
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO, autoTrack)
                    .putInt(KEY_INTERVAL_S, intervalSec)
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "setConfig failed", t);
        }
    }

    private boolean autoTrackEnabled() {
        try {
            return appCtx != null && appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private long intervalMs() {
        try {
            int s = appCtx == null ? 5 : appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_INTERVAL_S, 5);
            if (s < 1) s = 1;
            return s * 1000L;
        } catch (Throwable t) {
            return 5000L;
        }
    }

    // ── BLE session callbacks (forwarded from MainActivity) ──

    public synchronized void onConnected() {
        try {
            finalizeRide();     // defensive: close any ride left over from a previous link
            connected = true;
            armed = false;
            stopped = false;    // a fresh link re-enables auto-arm
            armDistM = 0;
            armLastTs = 0;
        } catch (Throwable t) {
            Log.e(TAG, "onConnected failed", t);
        }
    }

    public synchronized void onDisconnected() {
        try {
            finalizeRide();
            connected = false;
            armDistM = 0;
            armLastTs = 0;
        } catch (Throwable t) {
            Log.e(TAG, "onDisconnected failed", t);
        }
    }

    /** Stop button: finalize the current recording and block auto re-arm until the next reconnect. */
    public synchronized void stopRecording() {
        try {
            finalizeRide();
            stopped = true;
        } catch (Throwable t) {
            Log.e(TAG, "stopRecording failed", t);
        }
    }

    /** Integrate the scooter's own speed and arm the ride after ~20 m of real movement. */
    public synchronized void onLiveData(String json) {
        try {
            if (json == null || armed || stopped || !connected) return;
            if (!autoTrackEnabled()) return;
            JSONObject o = new JSONObject(json);
            double spd = o.optDouble("speed", 0.0);          // km/h
            long ts = o.optLong("ts", System.currentTimeMillis());
            if (armLastTs != 0 && spd > 0.0) {
                double dtH = (ts - armLastTs) / 3600000.0;
                if (dtH > 0 && dtH < 0.02) armDistM += spd * dtH * 1000.0;
            }
            armLastTs = ts;
            if (armDistM >= ARM_DISTANCE_M) arm();
        } catch (Throwable t) {
            Log.e(TAG, "onLiveData failed", t);
        }
    }

    // ── Arm / finalize ──

    private void arm() {
        try {
            File dir = routesDir();
            if (dir == null) { Log.e(TAG, "arm: no routes dir"); return; }
            long now = System.currentTimeMillis();
            String name = "route-" + now + ".ndjson";
            File f = PathGuard.childOf(dir, name);
            currentFileName = name;
            armed = true;
            if (appCtx != null) {
                Intent i = new Intent(appCtx, RouteRecorderService.class);
                i.putExtra(RouteRecorderService.EXTRA_FILE, f.getAbsolutePath());
                i.putExtra(RouteRecorderService.EXTRA_INTERVAL_MS, intervalMs());
                try {
                    appCtx.startForegroundService(i);
                } catch (Throwable t) {
                    // Android 12+ refuses a foreground-service start from the background. Undo the arm
                    // and tell the UI so the rider can start the recording manually.
                    Log.e(TAG, "startForegroundService refused", t);
                    armed = false;
                    currentFileName = null;
                    notifyError("fgservice");
                    return;
                }
            }
            Log.i(TAG, "route armed: " + name);
        } catch (Throwable t) {
            Log.e(TAG, "arm failed", t);
            armed = false;
            currentFileName = null;
        }
    }

    private void notifyError(String code) {
        Listener l = listener;
        if (l == null) return;
        try { l.onRecordingError(code); } catch (Throwable t) { Log.e(TAG, "listener failed", t); }
    }

    private void finalizeRide() {
        boolean wasArmed = armed;
        armed = false;
        currentFileName = null;
        if (wasArmed && appCtx != null) {
            try { appCtx.stopService(new Intent(appCtx, RouteRecorderService.class)); }
            catch (Throwable t) { Log.e(TAG, "stopService failed", t); }
        }
    }

    // ── Import (called from the JS bridge) ──

    /**
     * @return a JSON array of every FINISHED route (all but the one recording now), newest first, each
     * {@code {"id","start","end","points":[{lat,lon,alt,ts,speed}]}}, then DELETES those files. The
     * dashboard turns each into a saved route. "[]" if none. The active recording is never returned.
     */
    public synchronized String takeRecordedRoutes() {
        try {
            File dir = routesDir();
            if (dir == null) return "[]";
            File[] files = dir.listFiles((d, n) -> n.startsWith("route-") && n.endsWith(".ndjson"));
            if (files == null || files.length == 0) return "[]";
            Arrays.sort(files, (a, b) -> Long.compare(idOf(b), idOf(a)));   // newest first
            JSONArray out = new JSONArray();
            for (File f : files) {
                if (armed && f.getName().equals(currentFileName)) continue;   // skip the live recording
                JSONArray points = readPoints(f);
                if (points.length() == 0) { safeDelete(f); continue; }         // empty -> just drop
                JSONObject route = new JSONObject();
                long id = idOf(f);
                route.put("id", id);
                route.put("start", points.optJSONObject(0).optLong("ts", id));
                route.put("end", points.optJSONObject(points.length() - 1).optLong("ts", id));
                route.put("points", points);
                out.put(route);
                safeDelete(f);
            }
            return out.toString();
        } catch (Throwable t) {
            Log.e(TAG, "takeRecordedRoutes failed", t);
            return "[]";
        }
    }

    // ── Helpers ──

    private File routesDir() {
        try {
            if (appCtx == null) return null;
            File dir = appCtx.getExternalFilesDir(ROUTES_DIR);
            if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
                Log.e(TAG, "routesDir: mkdirs failed: " + dir);
            }
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONArray readPoints(File f) {
        JSONArray arr = new JSONArray();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try { arr.put(new JSONObject(line)); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.e(TAG, "readPoints failed", t);
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return arr;
    }

    private static long idOf(File f) {
        try {
            String n = f.getName();
            int a = n.indexOf('-');
            int b = n.lastIndexOf('.');
            if (a >= 0 && b > a + 1) return Long.parseLong(n.substring(a + 1, b));
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static void safeDelete(File f) {
        try { if (f.isFile() && !f.delete()) Log.w(TAG, "could not delete " + f.getName()); }
        catch (Throwable ignored) { }
    }
}
