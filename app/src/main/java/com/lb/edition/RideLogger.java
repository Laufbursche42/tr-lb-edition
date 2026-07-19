// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native ride recorder. Records one file per ride as compact NDJSON so the data is written
 * incrementally to disk (an app kill loses at most the current minute) and the ride metadata is
 * derived at read time - there is no fragile "finalize write".
 *
 * <p>Arming / sampling / finalizing (driven by {@link MainActivity} from the BLE callbacks):
 * <ul>
 *   <li>{@link #onConnected()} begins a potential session (state reset, not yet armed; no writing).
 *   <li>{@link #onLiveData(String)} keeps the latest telemetry snapshot; the FIRST time logging is
 *       enabled, the link is connected and speed &gt; 0 the ride arms: it records the start time,
 *       creates the ride file, starts {@link RideLoggerService}, and writes the first sample (t=0).
 *   <li>While armed a 60 s timer (main looper {@link Handler}) appends the latest snapshot, so
 *       samples land at t=0, 60 s, 120 s … measured from first movement.
 *   <li>{@link #onDisconnected()} - or turning the toggle off mid-ride - finalizes the ride
 *       (stops the timer + foreground service) and resets the session.
 * </ul>
 *
 * <p>Storage: {@code getExternalFilesDir("rides")/ride-<startEpochMs>.ndjson}, one compact JSON
 * object per line, flushed immediately. All ride files are kept until deleted from the app.
 *
 * <p>Every public method is null/exception-safe and never throws across the JS bridge.
 */
public final class RideLogger {

    private static final String TAG = "lbridelog";
    private static final String PREFS = "lb";                 // shared with BleManager
    private static final String KEY_ENABLED = "ride_logging"; // default false
    private static final String RIDES_DIR = "rides";
    private static final String EXPORT_DIR = "exports";       // under cacheDir (FileProvider cache-path)
    private static final long SAMPLE_INTERVAL_MS = 60_000L;   // one sample per minute after arming


    // Headline CSV columns emitted first (when present), before the rest in alphabetical order.
    private static final String[] CSV_HEADLINE = {"realSpeed", "SOC", "power", "gear"};

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean connected = false;
    private boolean armed = false;
    private String latestSnapshot = null;
    private Writer writer = null;

    RideLogger(Context ctx) {
        this.appCtx = ctx != null ? ctx.getApplicationContext() : null;
    }

    // ── Toggle (persisted in the "lb" prefs) ──

    /** @return the persisted ride-logging flag (default false). */
    public boolean isEnabled() {
        try {
            if (appCtx == null) return false;
            return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Persist the flag; turning it off mid-ride finalizes the current ride. */
    public synchronized void setEnabled(boolean on) {
        try {
            if (appCtx != null) {
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_ENABLED, on).apply();
            }
            // Turning off mid-ride: close out the current ride now.
            // Turning on: arming happens naturally on the next moving snapshot.
            if (!on) finalizeRide();
        } catch (Throwable t) {
            Log.e(TAG, "setEnabled failed", t);
        }
    }

    // ── BLE session callbacks (forwarded from MainActivity) ──

    /** Begin a potential session: reset state, no writing yet. */
    public synchronized void onConnected() {
        try {
            finalizeRide();            // defensive: close any ride left over from a previous link
            connected = true;
            armed = false;
            latestSnapshot = null;
        } catch (Throwable t) {
            Log.e(TAG, "onConnected failed", t);
        }
    }

    /** Finalize the current ride (if any) and clear the session. */
    public synchronized void onDisconnected() {
        try {
            finalizeRide();
            connected = false;
            latestSnapshot = null;
        } catch (Throwable t) {
            Log.e(TAG, "onDisconnected failed", t);
        }
    }

    /** Keep the latest snapshot; arm the ride on the first movement while enabled and connected. */
    public synchronized void onLiveData(String json) {
        try {
            if (json == null) return;
            latestSnapshot = json;
            if (armed || !connected || !isEnabled()) return;
            if (speedOf(json) > 0.0) arm();
        } catch (Throwable t) {
            Log.e(TAG, "onLiveData failed", t);
        }
    }

    // ── Arm / sample / finalize ──

    private void arm() {
        try {
            File dir = ridesDir();
            if (dir == null) {
                Log.e(TAG, "arm: no rides dir");
                return;
            }
            long now = System.currentTimeMillis();
            File f = PathGuard.childOf(dir, "ride-" + now + ".ndjson");
            Writer w;
            try {
                w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8"));
            } catch (Throwable t) {
                Log.e(TAG, "arm: open writer failed", t);
                return;
            }
            writer = w;
            armed = true;
            // First sample immediately (t=0).
            writeSample(latestSnapshot);
            // Keep the process foreground so BLE + the 60 s sampling survive the screen going off.
            startService();
            // Subsequent samples every 60 s, measured from first movement.
            main.postDelayed(sampleTask, SAMPLE_INTERVAL_MS);
            Log.i(TAG, "ride armed: " + f.getName());
        } catch (Throwable t) {
            Log.e(TAG, "arm failed", t);
        }
    }

    private final Runnable sampleTask = new Runnable() {
        @Override
        public void run() {
            synchronized (RideLogger.this) {
                if (!armed) return;
                writeSample(latestSnapshot);
                main.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        }
    };

    private void writeSample(String json) {
        if (json == null) return;
        Writer w = writer;
        if (w == null) return;
        try {
            w.write(json);   // one compact JSON object per line (NDJSON)
            w.write('\n');
            w.flush();       // flush immediately so an app kill loses at most this minute
        } catch (Throwable t) {
            Log.e(TAG, "writeSample failed", t);
        }
    }

    private void finalizeRide() {
        boolean wasArmed = armed;
        armed = false;
        main.removeCallbacks(sampleTask);
        closeWriter();
        if (wasArmed) stopService();
    }

    private void closeWriter() {
        Writer w = writer;
        writer = null;
        if (w != null) {
            try { w.flush(); } catch (Throwable ignored) { }
            try { w.close(); } catch (Throwable ignored) { }
        }
    }

    // ── Foreground service control ──

    private void startService() {
        try {
            if (appCtx == null) return;
            // minSdk is 26, so a foreground service always starts via startForegroundService().
            appCtx.startForegroundService(new Intent(appCtx, RideLoggerService.class));
        } catch (Throwable t) {
            Log.e(TAG, "startService failed", t);
        }
    }

    private void stopService() {
        try {
            if (appCtx == null) return;
            appCtx.stopService(new Intent(appCtx, RideLoggerService.class));
        } catch (Throwable t) {
            Log.e(TAG, "stopService failed", t);
        }
    }

    // ── Ride listing / export (called from the JS bridge) ──

    /**
     * @return a JSON array string, newest first, of all recorded rides. Each entry:
     * {@code {"id","start","end","durationSec","distanceKm","samples"}}. "[]" if none.
     */
    public synchronized String listRides() {
        try {
            File dir = ridesDir();
            if (dir == null) return "[]";
            File[] files = listRideFiles(dir);
            if (files == null || files.length == 0) return "[]";
            Arrays.sort(files, (a, b) -> Long.compare(rideIdOf(b), rideIdOf(a))); // newest first
            JSONArray arr = new JSONArray();
            for (File f : files) {
                arr.put(metaFrom(readSamples(f), rideIdOf(f)));
            }
            return arr.toString();
        } catch (Throwable t) {
            Log.e(TAG, "listRides failed", t);
            return "[]";
        }
    }

    /**
     * Build an export file for the given ride {@code id} ("csv" or "json") under
     * {@code cacheDir/exports} and return it, or null if the ride is unknown / the export fails.
     * MainActivity launches the share sheet on the returned file.
     */
    public synchronized File exportRide(String id, String format) {
        try {
            String safe = safeId(id);
            if (safe == null) return null;
            File src = PathGuard.childOf(ridesDir(), "ride-" + safe + ".ndjson");
            if (!src.isFile()) return null;
            File outDir = new File(appCtx.getCacheDir(), EXPORT_DIR);
            if (!outDir.exists() && !outDir.mkdirs()) {
                Log.e(TAG, "exportRide: cannot create export dir");
                return null;
            }
            boolean csv = "csv".equalsIgnoreCase(format);
            File out = PathGuard.childOf(outDir, "ride-" + safe + (csv ? ".csv" : ".json"));
            List<JSONObject> samples = readSamples(src);
            if (csv) writeCsv(samples, out);
            else writeJson(samples, parseLongSafe(safe), out);
            return (out.isFile() && out.length() > 0) ? out : null;
        } catch (Throwable t) {
            Log.e(TAG, "exportRide failed", t);
            return null;
        }
    }

    /**
     * Delete one recorded ride by {@code id}. The id is validated all-digits via {@link #safeId}
     * and resolved through the path-traversal guard before removal. No-op for an unknown / invalid
     * id. Exception-safe.
     */
    public synchronized void deleteRide(String id) {
        try {
            String safe = safeId(id);
            if (safe == null) return;
            File f = PathGuard.childOf(ridesDir(), "ride-" + safe + ".ndjson");
            if (!f.isFile()) return;
            if (!f.delete()) {
                Log.w(TAG, "deleteRide: could not delete " + f.getName());
            }
        } catch (Throwable t) {
            Log.e(TAG, "deleteRide failed", t);
        }
    }

    // ── JSON export ──

    private void writeJson(List<JSONObject> samples, long id, File out) {
        Writer w = null;
        try {
            JSONObject meta = metaFrom(samples, id);
            meta.put("fin", finOf(samples));
            JSONArray arr = new JSONArray();
            for (JSONObject o : samples) arr.put(o);
            JSONObject root = new JSONObject();
            root.put("meta", meta);
            root.put("samples", arr);
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out, false), "UTF-8"));
            w.write(root.toString());
            w.flush();
        } catch (Throwable t) {
            Log.e(TAG, "writeJson failed", t);
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) { }
        }
    }

    // ── CSV export (all main-screen values, flattened) ──

    private void writeCsv(List<JSONObject> samples, File out) {
        // Column universe: every scalar key present, PLUS every unique top[]/bottom[] name.
        Set<String> scalarKeys = new HashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (JSONObject o : samples) {
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("top".equals(k) || "bottom".equals(k)) continue;
                Object v = o.opt(k);
                if (v instanceof JSONArray || v instanceof JSONObject) continue; // scalars only
                scalarKeys.add(k);
            }
            collectNames(o.optJSONArray("top"), names);
            collectNames(o.optJSONArray("bottom"), names);
        }
        // A top/bottom name that duplicates a scalar key is dropped (the scalar already carries it).
        List<String> nameCols = new ArrayList<>();
        for (String n : names) if (!scalarKeys.contains(n)) nameCols.add(n);
        Set<String> nameColSet = new HashSet<>(nameCols);

        // Column order: ts, tsISO, headline scalars (when present), then the rest alphabetically.
        List<String> cols = new ArrayList<>();
        cols.add("ts");
        cols.add("tsISO");
        Set<String> placed = new HashSet<>();
        placed.add("ts");
        for (String h : CSV_HEADLINE) {
            if (scalarKeys.contains(h)) { cols.add(h); placed.add(h); }
        }
        List<String> rest = new ArrayList<>();
        for (String k : scalarKeys) if (!placed.contains(k)) rest.add(k);
        rest.addAll(nameCols);
        Collections.sort(rest, (a, b) -> {
            int c = a.compareToIgnoreCase(b);
            return c != 0 ? c : a.compareTo(b);
        });
        cols.addAll(rest);

        // try-with-resources guarantees the writer (and its underlying stream) is always closed.
        // The UTF-8 BOM is written as the U+FEFF character (it encodes to EF BB BF) so spreadsheets
        // render the degree sign and other units correctly.
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(out, false), "UTF-8"))) {
            w.write('\uFEFF');
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(cols.get(i)));
            }
            sb.append("\r\n");
            w.write(sb.toString());

            for (JSONObject o : samples) {
                Map<String, String> nvals = new HashMap<>();
                if (!nameColSet.isEmpty()) {
                    putNamedValues(o.optJSONArray("top"), nvals, nameColSet);
                    putNamedValues(o.optJSONArray("bottom"), nvals, nameColSet);
                }
                long ts = o.optLong("ts", 0);
                sb.setLength(0);
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) sb.append(',');
                    String col = cols.get(i);
                    String cell;
                    if ("ts".equals(col)) cell = ts > 0 ? Long.toString(ts) : "";
                    else if ("tsISO".equals(col)) cell = ts > 0 ? isoOf(ts) : "";
                    else if (nameColSet.contains(col)) cell = nvals.containsKey(col) ? nvals.get(col) : "";
                    else cell = scalarCell(o, col);
                    sb.append(csvCell(cell));
                }
                sb.append("\r\n");
                w.write(sb.toString());
            }
            w.flush();
        } catch (Throwable t) {
            Log.e(TAG, "writeCsv failed", t);
        }
    }

    // ── Metadata derivation ──

    /** Metadata for one ride, derived from its samples (no "fin" - export adds that). */
    private static JSONObject metaFrom(List<JSONObject> samples, long id) {
        long start = 0, end = 0;
        double firstMile = Double.NaN, lastMile = Double.NaN;
        for (JSONObject o : samples) {
            long ts = o.optLong("ts", 0);
            if (ts > 0) {
                if (start == 0) start = ts;
                end = ts;
            }
            double mile = o.optDouble("totalMile", Double.NaN);
            if (!Double.isNaN(mile)) {
                if (Double.isNaN(firstMile)) firstMile = mile;
                lastMile = mile;
            }
        }
        if (start == 0) start = id;      // fallback to the filename epoch
        if (end == 0) end = start;
        long durationSec = (end - start) / 1000L;
        if (durationSec < 0) durationSec = 0;
        double distanceKm = 0;
        if (!Double.isNaN(firstMile) && !Double.isNaN(lastMile)) distanceKm = lastMile - firstMile;
        if (distanceKm < 0) distanceKm = 0;
        JSONObject meta = new JSONObject();
        try {
            meta.put("id", String.valueOf(id));
            meta.put("start", start);
            meta.put("end", end);
            meta.put("durationSec", (int) durationSec);
            meta.put("distanceKm", round2(distanceKm));
            meta.put("samples", samples.size());
        } catch (JSONException ignored) {
        }
        return meta;
    }

    private static String finOf(List<JSONObject> samples) {
        String fin = "";
        for (JSONObject o : samples) {
            String bn = o.optString("btName", "");
            if (bn != null && !bn.isEmpty()) fin = bn;
        }
        return fin;
    }

    // ── File / parsing helpers ──

    private File ridesDir() {
        try {
            if (appCtx == null) return null;
            File dir = appCtx.getExternalFilesDir(RIDES_DIR);
            if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
                Log.e(TAG, "ridesDir: mkdirs failed: " + dir);
            }
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File[] listRideFiles(File dir) {
        return dir.listFiles((d, name) -> name.startsWith("ride-") && name.endsWith(".ndjson"));
    }

    /** Delete every recorded ride. @return the number of ride files deleted. */
    public synchronized int deleteAllRides() {
        int n = 0;
        try {
            File dir = ridesDir();
            if (dir == null) return 0;
            File[] files = listRideFiles(dir);
            if (files == null) return 0;
            for (File f : files) {
                try { if (f.delete()) n++; } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.e(TAG, "deleteAllRides failed", t);
        }
        return n;
    }

    /** @return the {@code <startEpochMs>} parsed from a {@code ride-<epoch>.ndjson} file name, or 0. */
    private static long rideIdOf(File f) {
        try {
            String n = f.getName();
            int a = n.indexOf('-');
            int b = n.lastIndexOf('.');
            if (a >= 0 && b > a + 1) return Long.parseLong(n.substring(a + 1, b));
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /** Read a ride's NDJSON into parsed sample objects, skipping blank / unparsable lines. */
    private static List<JSONObject> readSamples(File f) {
        List<JSONObject> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.e(TAG, "readSamples failed", t);
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void collectNames(JSONArray arr, Set<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String name = e.optString("name", "");
            if (!name.isEmpty()) out.add(name);
        }
    }

    private static void putNamedValues(JSONArray arr, Map<String, String> out, Set<String> wanted) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String name = e.optString("name", "");
            if (name.isEmpty() || !wanted.contains(name)) continue;
            out.put(name, e.optString("value", ""));
        }
    }

    private static String scalarCell(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            if (d == Math.rint(d)) return Long.toString((long) d);
            return Double.toString(d);
        }
        return String.valueOf(v);
    }

    private static String csvCell(String s) {
        if (s == null || s.isEmpty()) return "";
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ';
        if (!quote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String isoOf(long ms) {
        try {
            return java.time.Instant.ofEpochMilli(ms).toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static double speedOf(String json) {
        try {
            JSONObject o = new JSONObject(json);
            double rs = o.optDouble("realSpeed", Double.NaN);
            if (!Double.isNaN(rs)) return rs;
            return o.optDouble("speed", 0.0);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** Accept only an all-digit ride id (prevents path traversal via the file name). */
    private static String safeId(String id) {
        if (id == null) return null;
        String s = id.trim();
        if (s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return s;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
