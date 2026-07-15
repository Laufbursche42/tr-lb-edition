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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that downloads an offline Mapsforge {@code .map} map file so the transfer keeps
 * running when the screen locks or the app is backgrounded (an in-activity {@code ExecutorService}
 * gets throttled / killed on screen-off).
 *
 * <p>One download at a time. Progress is exposed to {@link MapDownloadActivity} through static
 * observable state plus an optional {@link Listener}, and to the user through an ongoing progress
 * notification with a Cancel action. A partial wake lock and a high-perf Wi-Fi lock are held for the
 * duration so the OS keeps the CPU and radio alive while the screen is off.
 */
public class MapDownloadService extends Service {

    private static final String TAG = "lbmapdlsvc";

    static final String CHANNEL_ID = "map_dl";
    static final int NOTIF_ID = 7801;

    static final String ACTION_START         = "com.lb.edition.action.MAP_DL_START";
    static final String ACTION_START_ROUTING = "com.lb.edition.action.ROUTE_DL_START";
    static final String ACTION_CANCEL        = "com.lb.edition.action.MAP_DL_CANCEL";

    static final String EXTRA_URL     = "com.lb.edition.extra.URL";
    static final String EXTRA_DEST    = "com.lb.edition.extra.DEST";
    static final String EXTRA_BASE    = "com.lb.edition.extra.BASE";
    static final String EXTRA_DISPLAY = "com.lb.edition.extra.DISPLAY";
    /** ArrayList&lt;String&gt; of BRouter tile names (e.g. {@code E5_N45}) to fetch as {@code .rd5}. */
    static final String EXTRA_TILES   = "com.lb.edition.extra.TILES";

    // ── Observable state for the UI (null base == no MAP download running). ──
    static volatile String activeBase;
    static volatile long done, total;
    static volatile String status;

    // ── Observable state for the ROUTING-DATA download (routingActive == running). ──
    static volatile boolean routingActive;
    static volatile long routingPermille;   // overall progress, 0..1000
    static volatile String routingStatus;

    /** Optional single listener so an open {@link MapDownloadActivity} can mirror progress live. */
    interface Listener {
        void onUpdate(String base, long done, long total, String status);
        void onFinished(String base, boolean ok, String message);
        void onRoutingUpdate(long permille, long scale, String status);
        void onRoutingFinished(boolean ok, String message);
    }

    private static volatile Listener listener;
    static void setListener(Listener l) { listener = l; }

    // Route writes to the observable static MAP-download state through static setters (a static
    // method writing a static field is fine; a direct write from an instance method is not).
    private static void setActiveBase(String v) { activeBase = v; }
    private static void setDone(long v) { done = v; }
    private static void setTotal(long v) { total = v; }
    private static void setStatus(String v) { status = v; }

    // ── Per-download state. ──
    private volatile AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastNotify;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL.equals(action)) {
            AtomicBoolean cf = cancelFlag;
            if (cf != null) cf.set(true);
            return START_NOT_STICKY;
        }

        if (ACTION_START_ROUTING.equals(action)) {
            return startRoutingDownload(intent);
        }

        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        // Only one operation at a time - ignore a start while a map or routing download runs.
        if (activeBase != null || routingActive) {
            return START_NOT_STICKY;
        }

        final String url = intent.getStringExtra(EXTRA_URL);
        final String dest = intent.getStringExtra(EXTRA_DEST);
        final String base = intent.getStringExtra(EXTRA_BASE);
        final String displayExtra = intent.getStringExtra(EXTRA_DISPLAY);
        if (url == null || dest == null || base == null) {
            return START_NOT_STICKY;
        }
        final String display = displayExtra != null ? displayExtra : base;

        // Path-traversal guard: derive the destination from OUR OWN maps dir plus the country base
        // name and verify it stays inside that dir (PathGuard.childOf), instead of trusting the raw
        // absolute EXTRA_DEST. This yields the same file the activity computed, but a crafted base
        // name can never escape getExternalFilesDir("nav")/maps.
        final File navExt = getExternalFilesDir("nav");
        if (navExt == null) {
            Log.e(TAG, "no external files dir; cannot download");
            return START_NOT_STICKY;
        }
        final File destFile;
        try {
            destFile = PathGuard.childOf(new File(navExt, "maps"), base + ".map");
        } catch (IOException e) {
            Log.e(TAG, "rejected download destination for base: " + base, e);
            return START_NOT_STICKY;
        }

        setActiveBase(base);
        setDone(0);
        setTotal(-1);
        setStatus("Connecting…");
        lastNotify = 0L;
        cancelFlag = new AtomicBoolean(false);
        final AtomicBoolean cancel = cancelFlag;

        createChannel();
        Notification notif = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
        }
        acquireLocks();

        final NavDownloader.Progress progressCb = (d, t) -> {
            setDone(d);
            setTotal(t);
            if (t > 0) {
                setStatus(String.format(Locale.US, "Downloading  %s / %s  (%d%%)",
                        NavDownloader.humanBytes(d), NavDownloader.humanBytes(t), d * 100 / t));
            } else {
                setStatus("Downloading  " + NavDownloader.humanBytes(d));
            }
            long now = System.currentTimeMillis();
            if (now - lastNotify >= 1000L) {
                lastNotify = now;
                try {
                    NotificationManager nm =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIF_ID, buildNotification());
                } catch (Throwable ignored) { }
            }
            final String s = status;
            final long fd = d, ft = t;
            handler.post(() -> {
                Listener l = listener;
                if (l != null) l.onUpdate(base, fd, ft, s);
            });
        };

        worker = new Thread(() -> {
            try {
                NavDownloader.download(url, destFile, progressCb, cancel);
                postFinished(base, true, display + " downloaded");
            } catch (NavDownloader.CancelledException ce) {
                postFinished(base, false, "Paused " + display);
            } catch (Throwable t) {
                Log.e(TAG, "map download failed: " + url, t);
                postFinished(base, false, "Download failed");
            } finally {
                releaseLocks();
                setActiveBase(null);
                try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
                stopSelf();
            }
        }, "lb-mapdl");
        worker.start();

        return START_NOT_STICKY;
    }

    private void postFinished(String base, boolean ok, String message) {
        handler.post(() -> {
            Listener l = listener;
            if (l != null) l.onFinished(base, ok, message);
        });
    }

    // ─────────────────────────────────────────────── routing-data download ──

    /**
     * Start a foreground download of the BRouter {@code .rd5} routing tiles named in
     * {@link #EXTRA_TILES} into {@code nav/segments}. Reuses the same foreground notification +
     * Wi-Fi/wake locks as the map download so the transfer survives screen-off / backgrounding /
     * a view change (a 404 = an all-sea tile with no data is skipped).
     */
    private int startRoutingDownload(Intent intent) {
        // One operation at a time (map or routing).
        if (activeBase != null || routingActive) {
            return START_NOT_STICKY;
        }
        final ArrayList<String> tiles = intent.getStringArrayListExtra(EXTRA_TILES);
        if (tiles == null || tiles.isEmpty()) {
            return START_NOT_STICKY;
        }
        final File navExt = getExternalFilesDir("nav");
        if (navExt == null) {
            Log.e(TAG, "no external files dir; cannot download routing data");
            return START_NOT_STICKY;
        }
        final File segDir = new File(navExt, "segments");
        if (!segDir.mkdirs() && !segDir.exists()) Log.w(TAG, "mkdirs failed: " + segDir);

        routingActive = true;
        routingPermille = 0;
        routingStatus = "Preparing…";
        lastNotify = 0L;
        cancelFlag = new AtomicBoolean(false);
        final AtomicBoolean cancel = cancelFlag;

        createChannel();
        Notification notif = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground (routing) failed", t);
        }
        acquireLocks();

        worker = new Thread(() -> {
            boolean ok;
            String message;
            try {
                runRoutingDownload(segDir, tiles, cancel);
                ok = !cancel.get();
                message = cancel.get()
                        ? "Routing-data download cancelled."
                        : "Routing data ready for your maps.";
            } catch (Throwable t) {
                Log.e(TAG, "routing-data download failed", t);
                String m = t.getMessage();
                ok = false;
                message = "Routing-data download failed" + (m != null ? ": " + m : "");
            } finally {
                releaseLocks();
                routingActive = false;
                try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
                stopSelf();
            }
            postRoutingFinished(ok, message);
        }, "lb-routedl");
        worker.start();

        return START_NOT_STICKY;
    }

    /** Worker: download the tiles in {@code tiles} that are still missing under {@code segDir}. */
    private void runRoutingDownload(File segDir, List<String> tiles, AtomicBoolean cancel)
            throws IOException {
        List<String> missing = new ArrayList<>();
        for (String t : tiles) {
            File seg = PathGuard.childOf(segDir, t + ".rd5");
            if (!seg.isFile() || seg.length() == 0) missing.add(t);
        }
        final int total = missing.size();
        if (total == 0) {
            routingPermille = 1000;
            updateRouting("Routing data already complete for your maps.");
            return;
        }
        int idx = 0;
        for (String t : missing) {
            if (cancel.get()) return;
            idx++;
            final int cur = idx;
            final String tile = t;
            routingPermille = (cur - 1) * 1000L / total;
            updateRouting("Downloading " + cur + "/" + total + "  (" + tile + ")…");
            File seg = PathGuard.childOf(segDir, tile + ".rd5");
            try {
                NavDownloader.download(BikeRouter.SEGMENT_BASE_URL + tile + ".rd5", seg,
                        (d, tot) -> {
                            int pct = tot > 0 ? (int) (d * 100 / tot) : 0;
                            routingPermille = ((cur - 1) * 100L + pct) * 10L / total;
                            updateRouting("Downloading " + cur + "/" + total + "  " + tile + "  "
                                    + pct + "%  (" + NavDownloader.humanBytes(d)
                                    + (tot > 0 ? "/" + NavDownloader.humanBytes(tot) : "") + ")");
                        }, cancel);
            } catch (NavDownloader.CancelledException ce) {
                return; // cancelled mid-tile; caller reports "cancelled" via the cancel flag
            } catch (NavDownloader.HttpException he) {
                // 404 → no segment for this tile (e.g. all sea) - skip it, keep going.
                if (he.code == 404) {
                    Log.i(TAG, "no BRouter segment for " + tile + " (skipping)");
                } else {
                    throw he;
                }
            }
        }
        routingPermille = 1000;
    }

    /** Publish routing progress: throttled notification refresh + a listener callback. */
    private void updateRouting(String s) {
        routingStatus = s;
        long now = System.currentTimeMillis();
        if (now - lastNotify >= 1000L) {
            lastNotify = now;
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, buildNotification());
            } catch (Throwable ignored) { }
        }
        final long pm = routingPermille;
        final String st = s;
        handler.post(() -> {
            Listener l = listener;
            if (l != null) l.onRoutingUpdate(pm, 1000, st);
        });
    }

    private void postRoutingFinished(boolean ok, String message) {
        handler.post(() -> {
            Listener l = listener;
            if (l != null) l.onRoutingFinished(ok, message);
        });
    }

    // ─────────────────────────────────────────────── notification ──

    private void createChannel() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Map download",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Offline map download progress");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "createChannel failed", t);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MapDownloadActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent cancel = new Intent(this, MapDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE);

        final String title;
        final String text;
        final int prog;
        final boolean indeterminate;
        if (routingActive) {
            title = "Downloading routing data";
            text = routingStatus != null ? routingStatus : "Downloading…";
            prog = (int) routingPermille;
            indeterminate = false;
        } else {
            long t = total, d = done;
            title = "Downloading map";
            text = status != null ? status : "Downloading…";
            prog = t > 0 ? (int) (d * 1000 / t) : 0;
            indeterminate = t <= 0;
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(1000, prog, indeterminate)
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
                .build();
    }

    // ─────────────────────────────────────────────── wake / wifi locks ──

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lb:mapdl");
                wakeLock.acquire(3L * 60L * 60L * 1000L); // generous cap; released in finally
            }
        } catch (Throwable t) {
            Log.w(TAG, "wakelock acquire failed", t);
        }
        try {
            WifiManager wm =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lb:mapdl");
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wifilock acquire failed", t);
        }
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        wifiLock = null;
        wakeLock = null;
    }

    // ─────────────────────────────────────────────── lifecycle ──

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }
}
